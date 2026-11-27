"""An episode's pictures: all are started together, the episode is sent once the cover is in, and the others go on
being drawn after it. A picture that fails is drawn once more; one that fails again is marked failed, and the car
then shows its own picture for that scene.

While a picture is still being drawn /v1/images answers 202 (try again soon); a failed one is simply not there (404).
"""
import logging
import os
import threading
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor, wait
from pathlib import Path

from . import quiz
from .checker import art_problems
from .models import Draft, Episode, EpisodeRequest
from .painter import Painter
from .rules import rule_for

log = logging.getLogger("story")
# the belt mission's answer key (the app's StoryKeys), the one scene drawn before and after
BELT = "BELT"
# a picture is drawn at most this many times
TRIES = 2
# how long the episode waits for its cover before it is sent without one (the car then shows the theme picture)
COVER_WAIT_SECONDS = 90.0

PENDING, DONE, FAILED = "pending", "done", "failed"


def wanted_pictures(draft: Draft, req: EpisodeRequest) -> dict[str, str]:
    """What to draw, by picture name: the cover and one picture per puzzle, from the writer's art descriptions, less
    the ones whose description would give an answer away."""
    wanted: dict[str, str] = {}
    art = draft.art
    skip = art_problems(draft, req)
    if skip:
        log.info("pictures not drawn: %s", skip)
    if art is not None:
        wanted["cover"] = art.cover
        for i, text in enumerate(art.puzzles[: len(req.puzzles)], start=1):
            # an image model adds steam to a soup pot unasked, so what would give an answer away is also forbidden here
            avoid = sorted({w for key in req.puzzles[i - 1] if quiz.parse(key) is None for w in rule_for(key).art_never})
            wanted[f"p{i}"] = text + (f" (그림에 절대 넣지 말 것: {', '.join(avoid)})" if avoid else "")
    return {name: text for name, text in wanted.items() if name not in skip}


def _tries(name: str, make: Callable[[], bytes | None]) -> bytes | None:
    for attempt in range(1, TRIES + 1):
        try:
            data = make()
            if data:
                return data
            log.warning("picture %s came back empty (try %d)", name, attempt)
        except Exception as e:  # refusal, timeout, no image part
            log.warning("picture %s failed (try %d): %r", name, attempt, e)
    return None


class Drawing:
    """The pictures of one episode and how far each one is: pending, done or failed."""

    def __init__(self, ep: Episode, draft: Draft, req: EpisodeRequest, painter: Painter, folder: Path):
        self.ep, self.req, self.painter = ep, req, painter
        self.folder = folder / ep.storyId
        self.wanted = wanted_pictures(draft, req)
        # 벨트 딸깍 미션 is drawn twice, before the click and after it (p3, p3_ok), by a painter that knows the child seat
        self.belt = {f"p{i}" for i, slot in enumerate(req.puzzles, start=1) if BELT in slot}
        self.paint_belt = getattr(painter, "paint_belt", None)
        self.buckle = getattr(painter, "buckle", None)
        names = list(self.wanted)
        if self.paint_belt is not None and self.buckle is not None:
            names += [f"{n}_ok" for n in self.wanted if n in self.belt]
        self.lock = threading.Lock()
        self.status: dict[str, str] = {n: PENDING for n in names}
        self.cover_settled = threading.Event()
        if "cover" not in self.status:
            self.cover_settled.set()

    @property
    def story_id(self) -> str:
        return self.ep.storyId

    def state(self, name: str) -> str | None:
        with self.lock:
            return self.status.get(name)

    @property
    def failed(self) -> list[str]:
        with self.lock:
            return sorted(n for n, s in self.status.items() if s == FAILED)

    def _settle(self, name: str, data: bytes | None) -> None:
        if data:
            self.folder.mkdir(parents=True, exist_ok=True)
            # written under another name and moved, so /v1/images never serves half a file
            tmp = self.folder / f".{name}.tmp"
            tmp.write_bytes(data)
            os.replace(tmp, self.folder / f"{name}.webp")
        with self.lock:
            self.status[name] = DONE if data else FAILED
        if name == "cover":
            self.cover_settled.set()

    def _draw(self, name: str, text: str) -> None:
        if name not in self.belt or self.paint_belt is None:
            self._settle(name, _tries(name, lambda: self.painter.paint(text)))
            return
        before = _tries(name, lambda: self.paint_belt(text))
        self._settle(name, before)
        if f"{name}_ok" in self.status:
            # edited from the picture before, so only the belt and the friend's pose change
            after = _tries(f"{name}_ok", lambda: self.buckle(before)) if before else None
            self._settle(f"{name}_ok", after)

    def draw_all(self) -> None:
        """Draws every picture, the cover first in line, side by side (an episode has at most seven); IMAGE_PARALLEL
        lowers it when the image model's per-minute limit is near."""
        if not self.wanted:
            return
        workers = max(1, min(len(self.wanted), int(os.environ.get("IMAGE_PARALLEL", "6"))))
        order = sorted(self.wanted, key=lambda n: n != "cover")
        with ThreadPoolExecutor(max_workers=workers, thread_name_prefix=f"draw-{self.story_id[:6]}") as pool:
            wait([pool.submit(self._draw, n, self.wanted[n]) for n in order])

    def start(self, run: Callable[[Callable[[], None]], object], on_done: Callable[["Drawing"], None]) -> Episode:
        """Hands the drawing to ``run`` and returns the episode as soon as the cover is in (or has failed twice)."""

        def job() -> None:
            try:
                self.draw_all()
            except Exception:
                log.exception("pictures of %s stopped", self.story_id)
                with self.lock:
                    for n, s in self.status.items():
                        if s == PENDING:
                            self.status[n] = FAILED
                self.cover_settled.set()
            on_done(self)

        run(job)
        if not self.cover_settled.wait(COVER_WAIT_SECONDS):
            log.warning("cover of %s is late: the episode goes without it", self.story_id)
        return self.episode()

    def episode(self) -> Episode:
        """The episode naming every picture that is in or still coming; a failed one is left out, so its scene plays
        the car's own picture."""
        with self.lock:
            live = {n for n, s in self.status.items() if s != FAILED}
            cover_late = self.status.get("cover") == PENDING
        if cover_late:
            # an episode sent without its cover shows the theme; the cover is not asked for later
            live.discard("cover")
        images = {n: f"/v1/images/{self.story_id}/{n}.webp" for n in self.status if n in live}
        scenes = dict(self.ep.scenes)
        pictures = {}
        for i, slot in enumerate(self.req.puzzles, start=1):
            if f"p{i}" in live:
                for key in slot:
                    # the picture shows the world, never the answer, so it suits the app's own lines for this key too
                    pictures[key] = f"p{i}"
                    if key in scenes:
                        scenes[key] = scenes[key].model_copy(update={"picture": f"p{i}"})
        return self.ep.model_copy(update={"images": images, "scenes": scenes, "pictures": pictures})
