"""One episode: draw its questions from the bank, have the story written around them, check, write once more if
the first draft was not clean, then draw its pictures."""
import logging
import os
import random
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from . import quiz
from .bank import Bank
from .checker import Verdict, art_problems, check, scene_text, to_episode
from .models import THEME_LABEL, Draft, DraftQuestion, DraftScene, Episode, EpisodeRequest
from .painter import Painter
from .prompt import SYSTEM, user_message
from .rules import rule_for
from .voice import QuotaError, Voice, cache_key
from .writer import Writer

log = logging.getLogger("story")
# the belt mission's answer key (the app's StoryKeys), the one scene drawn before and after
BELT = "BELT"

Picks = dict[str, tuple[DraftQuestion, str]]


def pick_questions(req: EpisodeRequest, bank: Bank, rnd: random.Random | None = None) -> Picks:
    """A random bank question and its hint for every quiz key, fitted to the youngest child on board. A key the bank
    has nothing for is left out, and its scene is played with the car's own question."""
    rnd = rnd or random.Random()
    picks: Picks = {}
    for key in req.keys:
        q = quiz.parse(key)
        if q is not None and (found := bank.pick(q, req.youngest, rnd)) is not None:
            picks[key] = found
    return picks


def put_questions(draft: Draft, picks: Picks) -> None:
    """The writer only writes the story: each quiz scene gets its bank question and hint here, before the checks."""
    for s in draft.scenes:
        if s.key in picks:
            s.question, s.hint = picks[s.key]


def timeless_request(req: EpisodeRequest, rnd: random.Random | None = None) -> EpisodeRequest:
    """A copy with a random theme when none is chosen: a shelf story is told without the season, which is what the
    writer would otherwise pick the theme by (the checker drops a draft whose theme differs from the request's)."""
    if req.theme:
        return req
    return req.model_copy(update={"theme": (rnd or random).choice(sorted(THEME_LABEL))})


def make_episode(req: EpisodeRequest, writer: Writer, bank: Bank, attempts: int = 2,
                 rnd: random.Random | None = None, timeless: bool = False) -> tuple[Episode, Draft] | None:
    """The cleanest usable draft and its episode, or None when every draft failed the episode-wide checks."""
    picks = pick_questions(req, bank, rnd)
    best: tuple[Verdict, Episode, Draft] | None = None
    user = user_message(req, picks, timeless)
    for attempt in range(1, attempts + 1):
        try:
            draft, model = writer.write(SYSTEM, user)
        except Exception as e:  # network, rate limit, refusal, broken JSON: all mean "no draft this time"
            log.warning("attempt %d: writer failed: %r", attempt, e)
            continue
        put_questions(draft, picks)
        verdict = check(draft, req)
        log.info("attempt %d: episode=%s scenes=%s", attempt, verdict.whole, verdict.items)
        if not verdict.usable:
            continue
        episode = to_episode(draft, verdict, req, uuid.uuid4().hex, model)
        # the draft that drops fewer scenes
        if best is None or len(verdict.items) < len(best[0].items):
            best = (verdict, episode, draft)
        if verdict.clean:
            break
    if best is None:
        return None
    verdict, episode, draft = best
    # a quiz the bank had no question for can never pass, so it is not written again
    bad = {k for k in verdict.items if quiz.parse(k) is None or k in picks}
    if bad:
        episode, fixed = repair(req, writer, episode, bad, picks, timeless)
        # the draft holds the text the episode now has, so what is shelved from it is what was checked
        draft = draft.model_copy(update={"scenes": [fixed.get(s.key, s) for s in draft.scenes]})
    return episode, draft


def repair(req: EpisodeRequest, writer: Writer, ep: Episode, bad: set[str], picks: Picks,
           timeless: bool = False) -> tuple[Episode, dict[str, DraftScene]]:
    """Asks once more for only the scenes that failed, told the story so far so they still belong to it, around the
    same questions; a scene that passes this time joins the episode, one that fails again stays with the app's own.
    Returns the episode and the scenes it fixed, by key."""
    slots = [[k for k in slot if k in bad] for slot in req.puzzles]
    sub = req.model_copy(update={"puzzles": [slot for slot in slots if slot]})
    told = (
        "\n\n이미 정해진 오늘의 이야기 (아래 장면들은 이 이야기의 빠진 부분이다. 같은 세계와 흐름으로 이어지게 쓴다)\n"
        f"- 제목: {ep.title}\n- 세계: {ep.world}\n- 첫인사: {ep.intro}\n"
        "- scenes 말고 다른 항목도 형식에 맞게 채우되, 쓰이는 것은 scenes뿐이다."
    )
    try:
        draft, _ = writer.write(SYSTEM, user_message(sub, picks, timeless) + told)
    except Exception as e:
        log.warning("repair: writer failed: %r", e)
        return ep, {}
    put_questions(draft, picks)
    verdict = check(draft, sub)
    fixed = {s.key: s for s in reversed(draft.scenes) if s.key in bad and s.key not in verdict.items}
    log.info("repair: fixed=%s still=%s", sorted(fixed), verdict.items)
    if not fixed:
        return ep, {}
    scenes = dict(ep.scenes)
    for key, s in fixed.items():
        scenes[key] = scene_text(key, s, req)
    return ep.model_copy(update={"scenes": scenes, "dropped": [k for k in ep.dropped if k not in fixed]}), fixed


def paint_pictures(ep: Episode, draft: Draft, req: EpisodeRequest, painter: Painter, folder: Path) -> Episode:
    """Draw the cover and one picture per puzzle, side by side. A picture that is refused or fails is left out and the
    app shows its own picture there."""
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
    wanted = {name: text for name, text in wanted.items() if name not in skip}
    if not wanted:
        return ep
    # 벨트 딸깍 미션 is drawn twice, before the click and after it (p3, p3_ok), by a painter that knows the child seat
    belt = {f"p{i}" for i, slot in enumerate(req.puzzles, start=1) if BELT in slot}
    paint_belt, buckle = getattr(painter, "paint_belt", None), getattr(painter, "buckle", None)

    def draw(name: str, text: str) -> list[tuple[str, bytes | None]]:
        try:
            if name not in belt or paint_belt is None:
                return [(name, painter.paint(text))]
            before = paint_belt(text)
        except Exception as e:  # refusal, timeout, no image part
            log.warning("picture %s failed: %r", name, e)
            return [(name, None)]
        # edited from the picture before, so only the belt and the friend's pose change; without it the app shows the cover
        try:
            after = buckle(before) if buckle is not None else None
        except Exception as e:
            log.warning("picture %s_ok failed: %r", name, e)
            after = None
        return [(name, before), (f"{name}_ok", after)]

    # two at a time, so one episode does not use the image model's whole minute at once (IMAGE_PARALLEL)
    with ThreadPoolExecutor(max_workers=max(1, min(len(wanted), int(os.environ.get("IMAGE_PARALLEL", "2"))))) as pool:
        drawn = dict(pair for pairs in pool.map(lambda item: draw(*item), wanted.items()) for pair in pairs)

    out = folder / ep.storyId
    images = {}
    for name, data in drawn.items():
        if data:
            out.mkdir(parents=True, exist_ok=True)
            (out / f"{name}.webp").write_bytes(data)
            images[name] = f"/v1/images/{ep.storyId}/{name}.webp"
    scenes_out = dict(ep.scenes)
    pictures = {}
    for i, slot in enumerate(req.puzzles, start=1):
        if f"p{i}" in images:
            for key in slot:
                # the picture shows the world, never the answer, so it suits the app's own lines for this key too
                pictures[key] = f"p{i}"
                if key in scenes_out:
                    scenes_out[key] = scenes_out[key].model_copy(update={"picture": f"p{i}"})
    return ep.model_copy(update={"images": images, "scenes": scenes_out, "pictures": pictures})


def tidy_line(text: str) -> str:
    """The line as it is made and kept: spaces collapsed, ends trimmed (the app keys its own copy the same way)."""
    return " ".join(text.split())


class _LineLock:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.waiters = 0


# one lock per line being made, removed as soon as the last caller finishes so keys never accumulate in memory
_making: dict[str, _LineLock] = {}
_making_lock = threading.Lock()


def _touch(path: Path) -> None:
    try:
        os.utime(path, None)
    except OSError:
        pass


def speak_line(text: str, voice: Voice | None, cache: Path) -> bytes | None:
    """The line read aloud: from [cache] when it was made before with this voice and style, otherwise made now and
    kept. None when there is no voice or every key failed (the app then shows the line as a caption)."""
    text = tidy_line(text)
    key = cache_key(voice, text)
    cached = cache / f"{key}.wav"
    if cached.is_file():
        _touch(cached)
        return cached.read_bytes()
    if voice is None:
        return None
    with _making_lock:
        entry = _making.get(key)
        if entry is None:
            entry = _making[key] = _LineLock()
        entry.waiters += 1
    try:
        with entry.lock:
            if cached.is_file():
                _touch(cached)
                return cached.read_bytes()
            try:
                data = voice.speak(text)
            except QuotaError as e:
                log.warning("voice: quota, no sound for this line: %s", e)
                return None
            except Exception as e:  # refusal, timeout, no audio part, every key failed
                log.warning("voice failed: %s", type(e).__name__)
                return None
            if data:
                cache.mkdir(parents=True, exist_ok=True)
                tmp = cached.with_suffix(".part")
                tmp.write_bytes(data)
                tmp.replace(cached)
            return data or None
    finally:
        with _making_lock:
            entry.waiters -= 1
            if entry.waiters <= 0:
                _making.pop(key, None)
