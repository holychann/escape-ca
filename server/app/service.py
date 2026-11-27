"""One episode: draw its questions from the bank, have the story written around them, check, write once more if
the first draft was not clean. Its pictures are drawn in app/pictures.py."""
import logging
import os
import random
import threading
import uuid
from pathlib import Path

from . import quiz
from .bank import Bank
from .checker import Verdict, check, scene_text, to_episode
from .models import THEME_LABEL, Draft, DraftQuestion, DraftScene, Episode, EpisodeRequest
from .prompt import SYSTEM, user_message
from .voice import QuotaError, Voice, cache_key
from .writer import Writer

log = logging.getLogger("story")

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
