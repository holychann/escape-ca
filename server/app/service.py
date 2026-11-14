"""One episode: write, check, write once more if the first draft was not clean, then draw its pictures."""
import logging
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .checker import Verdict, art_problems, check, to_episode
from .models import Draft, Episode, EpisodeRequest, SceneText
from .painter import Painter
from .prompt import SYSTEM, user_message
from .rules import rule_for
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
    if best is None:
        return None
    verdict, episode, draft = best
    if verdict.items:
        episode = repair(req, writer, episode, set(verdict.items))
    return episode, draft


def repair(req: EpisodeRequest, writer: Writer, ep: Episode, bad: set[str]) -> Episode:
    """Asks once more for only the scenes that failed, told the story so far so they still belong to it; a scene
    that passes this time joins the episode, one that fails again stays with the app's own lines."""
    slots = [[k for k in slot if k in bad] for slot in req.puzzles]
    sub = req.model_copy(update={"puzzles": [slot for slot in slots if slot]})
    told = (
        "\n\n이미 정해진 오늘의 이야기 (아래 장면들은 이 이야기의 빠진 부분이다. 같은 세계와 흐름으로 이어지게 쓴다)\n"
        f"- 제목: {ep.title}\n- 세계: {ep.world}\n- 첫인사: {ep.intro}\n"
        "- scenes 말고 다른 항목도 형식에 맞게 채우되, 쓰이는 것은 scenes뿐이다."
    )
    try:
        draft, _ = writer.write(SYSTEM, user_message(sub) + told)
    except Exception as e:
        log.warning("repair: writer failed: %r", e)
        return ep
    verdict = check(draft, sub)
    fixed = {s.key: s for s in draft.scenes if s.key in bad and s.key not in verdict.items}
    log.info("repair: fixed=%s still=%s", sorted(fixed), verdict.items)
    if not fixed:
        return ep
    scenes = dict(ep.scenes)
    for key, s in fixed.items():
        scenes[key] = SceneText(bubble=s.bubble, speech=s.speech, hint=s.hint)
    return ep.model_copy(update={"scenes": scenes, "dropped": [k for k in ep.dropped if k not in fixed]})


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
            # an image model adds steam to a soup pot unasked, so what would give an answer away is also forbidden here
            avoid = sorted({w for key in req.puzzles[i - 1] for w in rule_for(key).art_never})
            wanted[f"p{i}"] = text + (f" (그림에 절대 넣지 말 것: {', '.join(avoid)})" if avoid else "")
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
    pictures = {}
    for i, slot in enumerate(req.puzzles, start=1):
        if f"p{i}" in images:
            for key in slot:
                # the picture shows the world, never the answer, so it suits the app's own lines for this key too
                pictures[key] = f"p{i}"
                if key in scenes:
                    scenes[key] = scenes[key].model_copy(update={"picture": f"p{i}"})
    return ep.model_copy(update={"images": images, "scenes": scenes, "pictures": pictures})
