"""One episode: draw its questions from the bank, have the story written around them, check, write once more if
the first draft was not clean, then draw its pictures."""
import logging
import random
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from . import quiz
from .bank import Bank
from .checker import Verdict, art_problems, check, friend_problems, scene_text, to_episode
from .models import Draft, DraftQuestion, Episode, EpisodeRequest
from .painter import Painter
from .prompt import SYSTEM, user_message
from .rules import rule_for
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


def make_episode(req: EpisodeRequest, writer: Writer, bank: Bank, attempts: int = 2,
                 rnd: random.Random | None = None) -> tuple[Episode, Draft] | None:
    """The cleanest usable draft and its episode, or None when every draft failed the episode-wide checks."""
    picks = pick_questions(req, bank, rnd)
    best: tuple[Verdict, Episode, Draft] | None = None
    user = user_message(req, picks)
    for attempt in range(1, attempts + 1):
        try:
            draft, model = writer.write(SYSTEM, user)
        except Exception as e:  # network, rate limit, refusal, broken JSON: all mean "no draft this time"
            log.warning("attempt %d: writer failed: %r", attempt, e)
            continue
        put_questions(draft, picks)
        verdict = check(draft, req)
        friend = friend_problems(draft.friend, req)
        log.info("attempt %d: episode=%s scenes=%s friend=%s", attempt, verdict.whole, verdict.items, friend)
        if not verdict.usable:
            continue
        episode = to_episode(draft, verdict, req, uuid.uuid4().hex, model)
        # fewer dropped scenes first; with as many, a story that brings its new friend
        if best is None or _score(verdict, episode) < _score(best[0], best[1]):
            best = (verdict, episode, draft)
        if verdict.clean and episode.friend is not None:
            break
    if best is None:
        return None
    verdict, episode, draft = best
    # a quiz the bank had no question for can never pass, so it is not written again
    bad = {k for k in verdict.items if quiz.parse(k) is None or k in picks}
    if bad:
        episode = repair(req, writer, episode, bad, picks)
    return episode, draft


def _score(verdict: Verdict, episode: Episode) -> tuple[int, bool]:
    return len(verdict.items), episode.friend is None


def repair(req: EpisodeRequest, writer: Writer, ep: Episode, bad: set[str], picks: Picks) -> Episode:
    """Asks once more for only the scenes that failed, told the story so far so they still belong to it, around the
    same questions; a scene that passes this time joins the episode, one that fails again stays with the app's own."""
    slots = [[k for k in slot if k in bad] for slot in req.puzzles]
    sub = req.model_copy(update={"puzzles": [slot for slot in slots if slot]})
    told = (
        "\n\n이미 정해진 오늘의 이야기 (아래 장면들은 이 이야기의 빠진 부분이다. 같은 세계와 흐름으로 이어지게 쓴다)\n"
        f"- 제목: {ep.title}\n- 세계: {ep.world}\n- 첫인사: {ep.intro}\n"
        "- scenes 말고 다른 항목도 형식에 맞게 채우되, 쓰이는 것은 scenes뿐이다."
    )
    try:
        draft, _ = writer.write(SYSTEM, user_message(sub, picks) + told)
    except Exception as e:
        log.warning("repair: writer failed: %r", e)
        return ep
    put_questions(draft, picks)
    verdict = check(draft, sub)
    fixed = {s.key: s for s in reversed(draft.scenes) if s.key in bad and s.key not in verdict.items}
    log.info("repair: fixed=%s still=%s", sorted(fixed), verdict.items)
    if not fixed:
        return ep
    scenes = dict(ep.scenes)
    for key, s in fixed.items():
        scenes[key] = scene_text(key, s)
    return ep.model_copy(update={"scenes": scenes, "dropped": [k for k in ep.dropped if k not in fixed]})


def paint_pictures(ep: Episode, draft: Draft, req: EpisodeRequest, painter: Painter, folder: Path, scenes: bool = True) -> Episode:
    """Draw today's new friend's portrait and, when [scenes], the cover, one picture per puzzle and the ending.

    Everything is drawn side by side, except that the cover and the ending wait for the portrait so they can show the
    same friend. A picture that is refused or fails is left out and the app shows its own picture there.
    """
    wanted: dict[str, str] = {}
    if scenes:
        art = draft.art
        skip = art_problems(draft, req)
        if skip:
            log.info("pictures not drawn: %s", skip)
        if art is not None:
            wanted["cover"] = art.cover
            wanted["ending"] = art.ending
            for i, text in enumerate(art.puzzles[: len(req.puzzles)], start=1):
                # an image model adds steam to a soup pot unasked, so what would give an answer away is also forbidden here
                avoid = sorted({w for key in req.puzzles[i - 1] if quiz.parse(key) is None for w in rule_for(key).art_never})
                wanted[f"p{i}"] = text + (f" (그림에 절대 넣지 말 것: {', '.join(avoid)})" if avoid else "")
        wanted = {name: text for name, text in wanted.items() if name not in skip}
    friend = ep.friend
    if not wanted and friend is None:
        return ep

    def draw(name: str, job) -> tuple[str, bytes | None]:
        try:
            return name, job()
        except Exception as e:  # refusal, timeout, no image part
            log.warning("picture %s failed: %r", name, e)
            return name, None

    drawn: dict[str, bytes | None] = {}
    with ThreadPoolExecutor(max_workers=len(wanted) + 1) as pool:
        later = {"cover", "ending"} if friend is not None else set()
        first = {n: pool.submit(draw, n, lambda t=t: painter.paint(t)) for n, t in wanted.items() if n not in later}
        if friend is not None:
            face = f"{friend.name}. {friend.look}" + (f" (사는 곳: {ep.world})" if ep.world else "")
            drawn["friend"] = pool.submit(draw, "friend", lambda: painter.portrait(face)).result()[1]
            portrait = drawn["friend"]
            first |= {n: pool.submit(draw, n, lambda t=wanted[n]: painter.paint(t, portrait)) for n in later if n in wanted}
        drawn |= dict(f.result() for f in first.values())

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
