"""One episode: write, check, write once more if the first draft was not clean, then draw its pictures."""
import logging
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .checker import Verdict, art_problems, check, to_episode
from .models import Draft, Episode, EpisodeRequest
from .painter import Painter
from .prompt import SYSTEM, user_message
from .writer import Writer

log = logging.getLogger("story")


def make_episode(req: EpisodeRequest, writer: Writer, attempts: int = 2) -> tuple[Episode, Draft] | None:
    """The cleanest usable draft and its episode, or None when every draft failed the episode-wide checks."""
    best: tuple[Verdict, Episode, Draft] | None = None
    user = user_message(req)
    for attempt in range(1, attempts + 1):
        try:
            draft, model = writer.write(SYSTEM, user)
        except Exception as e:  # network, rate limit, refusal, broken JSON: all mean "no draft this time"
            log.warning("attempt %d: writer failed: %r", attempt, e)
            continue
        verdict = check(draft, req)
        log.info("attempt %d: episode=%s scenes=%s", attempt, verdict.whole, verdict.items)
        if not verdict.usable:
            continue
        episode = to_episode(draft, verdict, req, uuid.uuid4().hex, model)
        if best is None or len(verdict.items) < len(best[0].items):
            best = (verdict, episode, draft)
        if verdict.clean:
            break
    return (best[1], best[2]) if best else None


def paint_pictures(ep: Episode, draft: Draft, req: EpisodeRequest, painter: Painter, folder: Path) -> Episode:
    """Draw the cover, one picture per puzzle and the ending side by side; a picture that is refused or fails is
    left out and the app shows its own picture there."""
    art = draft.art
    skip = art_problems(draft, req)
    if skip:
        log.info("pictures not drawn: %s", skip)
    wanted: dict[str, str] = {}
    if art is not None:
        wanted["cover"] = art.cover
        wanted["ending"] = art.ending
        for i, text in enumerate(art.puzzles[: len(req.puzzles)], start=1):
            wanted[f"p{i}"] = text
    wanted = {name: text for name, text in wanted.items() if name not in skip}
    if not wanted:
        return ep

    def one(item: tuple[str, str]) -> tuple[str, bytes | None]:
        name, text = item
        try:
            return name, painter.paint(text)
        except Exception as e:  # refusal, timeout, no image part
            log.warning("picture %s failed: %r", name, e)
            return name, None

    with ThreadPoolExecutor(max_workers=len(wanted)) as pool:
        drawn = dict(pool.map(one, wanted.items()))

    out = folder / ep.storyId
    images = {}
    for name, data in drawn.items():
        if data:
            out.mkdir(parents=True, exist_ok=True)
            (out / f"{name}.webp").write_bytes(data)
            images[name] = f"/v1/images/{ep.storyId}/{name}.webp"
    scenes = dict(ep.scenes)
    for i, slot in enumerate(req.puzzles, start=1):
        if f"p{i}" in images:
            for key in slot:
                if key in scenes:
                    scenes[key] = scenes[key].model_copy(update={"picture": f"p{i}"})
    return ep.model_copy(update={"images": images, "scenes": scenes})
