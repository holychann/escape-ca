"""Checks a draft before any of it can reach a child.

Three checks, as in the plan: the shape (lengths, every scene present), the words (scary, unsafe in a car,
personal, foreign), and the answer (each scene says its own answer and never the other one). A problem in the
episode-wide lines rejects the whole draft; a problem in one scene drops only that scene, and the car plays its
own reviewed lines there instead.
"""
try:  # the private textgate library when installed, else the same checks built in (app/gate.py)
    from textgate import Verdict, find_forbidden, missing, norm, present, too_long
except ImportError:
    from .gate import Verdict, find_forbidden, missing, norm, present, too_long

from .models import Draft, Episode, EpisodeRequest, SceneText
from .rules import BUBBLE_NEVER, FORBID, LIMITS, Rule, rule_for


def bad_words(text: str) -> list[str]:
    return find_forbidden(text, FORBID)


def wish_problems(wish: str) -> list[str]:
    """A wish that asks for something scary, unsafe in a car or personal is refused before any model sees it.
    English is fine in a wish ("T-rex"); the writer still answers in Korean."""
    return find_forbidden(wish, [f for f in FORBID if f.quote_match])


def usable_success(lines: list[str]) -> list[str]:
    """Praise lines stand alone, so a bad one is left out instead of losing the whole draft."""
    return [x for x in lines if not too_long("success", x, LIMITS.success) and not bad_words(x)][:3]


def check_scene(rule: Rule, scene: SceneText, youngest: str) -> list[str]:
    problems = (
        too_long("bubble", scene.bubble, LIMITS.bubble)
        + too_long("speech", scene.speech, getattr(LIMITS.speech, youngest))
        + too_long("hint", scene.hint, LIMITS.hint)
    )
    problems += bad_words(" ".join([scene.bubble, scene.speech, scene.hint]))
    problems += [f"정답 말 빠짐: {'/'.join(g)}" for g in missing(scene.speech, rule.must)]
    problems += [f"힌트에 정답 말 빠짐: {'/'.join(g)}" for g in missing(scene.hint, rule.hint_must)]
    problems += [f"다른 정답 말: {w}" for w in present("|".join([scene.bubble, scene.speech, scene.hint]), rule.never)]
    problems += [f"말풍선에 버튼 말: {w}" for w in present(scene.bubble, BUBBLE_NEVER)]
    if rule.needs_guardian and "안전지킴이" not in norm(scene.speech):
        problems.append("안전 지킴이에게 알리는 말 빠짐")
    return problems


ART_MAX = 200


def answer_words(slot: list[str]) -> list[str]:
    """Words that tell one answer of this puzzle slot from another, so a picture must not show them.

    That is every word a variant forbids, plus the required words not shared by all variants (위쪽 / 아래쪽,
    왼쪽 / 오른쪽, 밖에서 / 안에서, the seat names). A friend's name such as 펭귄 is in every variant and stays allowed.
    """
    rules = [rule_for(k) for k in slot]
    words = {w for r in rules for w in r.never} | {w for r in rules for w in r.art_never}
    for r in rules:
        for group in r.must:
            if not all(group in other.must for other in rules):
                words.update(group)
    return sorted(words)


def art_problems(draft: Draft, req: EpisodeRequest) -> dict[str, list[str]]:
    """Problems per picture name ("cover", "p1".., "ending"); a picture with problems is simply not drawn."""
    art = draft.art
    if art is None:
        return {"cover": ["설명 없음"], "ending": ["설명 없음"], **{f"p{i}": ["설명 없음"] for i in range(1, len(req.puzzles) + 1)}}
    out: dict[str, list[str]] = {}

    def look(name: str, text: str, extra: list[str]) -> None:
        problems = too_long(name, text, ART_MAX) + bad_words(text)
        problems += [f"정답이 보이는 말: {w}" for w in present(text, extra)]
        problems += [f"버튼 말: {w}" for w in present(text, BUBBLE_NEVER)]
        if not text.strip():
            problems.append("설명 없음")
        if problems:
            out[name] = problems

    look("cover", art.cover, [])
    look("ending", art.ending, [])
    for i, slot in enumerate(req.puzzles, start=1):
        text = art.puzzles[i - 1] if i <= len(art.puzzles) else ""
        look(f"p{i}", text, answer_words(slot))
    return out


def check(draft: Draft, req: EpisodeRequest) -> Verdict:
    """Episode-wide problems go to ``whole``, scene problems to ``items`` under the scene's key."""
    v = Verdict(checked=req.keys)
    v.whole += too_long("title", draft.title, LIMITS.title)
    v.whole += too_long("intro", draft.intro, LIMITS.intro)
    v.whole += too_long("treasureGift", draft.treasureGift, LIMITS.treasure)
    v.whole += too_long("treasureBadge", draft.treasureBadge, LIMITS.treasure)
    if len(usable_success(draft.success)) < 2:
        v.whole.append("칭찬 문장 부족")
    if req.theme and draft.theme != req.theme:
        v.whole.append("고른 테마와 다름")
    if "안전지킴이" not in norm(draft.treasureGift):
        v.whole.append("선물 문장에 안전 지킴이 빠짐")
    episode_text = " ".join([draft.title, draft.intro, draft.treasureGift, draft.treasureBadge, draft.world])
    v.whole += bad_words(episode_text)

    written = {}
    for s in draft.scenes:
        written.setdefault(s.key, s)
    for key in req.keys:
        scene = written.get(key)
        problems = ["장면 없음"] if scene is None else check_scene(rule_for(key), scene, req.youngest)
        if problems:
            v.items[key] = problems
    return v


def to_episode(draft: Draft, verdict: Verdict, req: EpisodeRequest, story_id: str, model: str) -> Episode:
    written = {s.key: s for s in reversed(draft.scenes)}
    scenes = {
        k: SceneText(bubble=written[k].bubble, speech=written[k].speech, hint=written[k].hint)
        for k in req.keys
        if k not in verdict.items
    }
    return Episode(
        storyId=story_id,
        model=model,
        theme=draft.theme,
        title=draft.title,
        intro=draft.intro,
        success=usable_success(draft.success),
        treasureGift=draft.treasureGift,
        treasureBadge=draft.treasureBadge,
        scenes=scenes,
        dropped=[k for k in req.keys if k in verdict.items],
        world=draft.world,
    )
