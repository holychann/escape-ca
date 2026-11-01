"""Checks a draft before any of it can reach a child.

Three checks, as in the plan: the shape (lengths, every scene present), the words (scary, unsafe in a car,
personal, foreign), and the answer (each scene says its own answer and never the other one). A problem in the
episode-wide lines rejects the whole draft; a problem in one scene drops only that scene, and the car plays its
own reviewed lines there instead.
"""
import re
from dataclasses import dataclass, field

from .models import Draft, Episode, EpisodeRequest, SceneText
from .rules import FORBID, LIMITS, Rule, rule_for

_PUNCT = re.compile(r"[\s.,!?~…·'\"“”‘’()\-]")


def norm(text: str) -> str:
    """Spacing and punctuation vary ("하나, 둘, 셋!" / "하나 둘 셋"), the words do not."""
    return _PUNCT.sub("", text)


def bad_words(text: str) -> list[str]:
    found = []
    for group in FORBID:
        for p in group.patterns:
            m = p.search(text)
            if m:
                found.append(f"{group.label}: {m.group(0)}" if group.quote_match else group.label)
    return found


def _too_long(name: str, text: str, limit: int) -> list[str]:
    if not text.strip():
        return [f"{name} 비어 있음"]
    return [f"{name} 길이 {len(text)} > {limit}"] if len(text) > limit else []


def usable_success(lines: list[str]) -> list[str]:
    """Praise lines stand alone, so a bad one is left out instead of losing the whole draft."""
    return [x for x in lines if not _too_long("success", x, LIMITS.success) and not bad_words(x)][:3]


def check_scene(rule: Rule, scene: SceneText, youngest: str) -> list[str]:
    problems = (
        _too_long("bubble", scene.bubble, LIMITS.bubble)
        + _too_long("speech", scene.speech, getattr(LIMITS.speech, youngest))
        + _too_long("hint", scene.hint, LIMITS.hint)
    )
    problems += bad_words(" ".join([scene.bubble, scene.speech, scene.hint]))
    speech = norm(scene.speech)
    hint = norm(scene.hint)
    everything = norm(scene.bubble) + "|" + speech + "|" + hint
    for group in rule.must:
        if not any(norm(w) in speech for w in group):
            problems.append(f"정답 말 빠짐: {'/'.join(group)}")
    for group in rule.hint_must:
        if not any(norm(w) in hint for w in group):
            problems.append(f"힌트에 정답 말 빠짐: {'/'.join(group)}")
    for w in rule.never:
        if norm(w) in everything:
            problems.append(f"다른 정답 말: {w}")
    if rule.needs_guardian and "안전지킴이" not in speech:
        problems.append("안전 지킴이에게 부탁하지 않음")
    return problems


@dataclass
class Verdict:
    episode: list[str] = field(default_factory=list)
    scenes: dict[str, list[str]] = field(default_factory=dict)

    @property
    def usable(self) -> bool:
        return not self.episode

    @property
    def clean(self) -> bool:
        return not self.episode and not self.scenes


def check(draft: Draft, req: EpisodeRequest) -> Verdict:
    v = Verdict()
    v.episode += _too_long("title", draft.title, LIMITS.title)
    v.episode += _too_long("intro", draft.intro, LIMITS.intro)
    v.episode += _too_long("treasureGift", draft.treasureGift, LIMITS.treasure)
    v.episode += _too_long("treasureBadge", draft.treasureBadge, LIMITS.treasure)
    if len(usable_success(draft.success)) < 2:
        v.episode.append("칭찬 문장 부족")
    if req.theme and draft.theme != req.theme:
        v.episode.append("고른 테마와 다름")
    episode_text = " ".join([draft.title, draft.intro, draft.treasureGift, draft.treasureBadge])
    v.episode += bad_words(episode_text)

    written = {}
    for s in draft.scenes:
        written.setdefault(s.key, s)
    for key in req.keys:
        scene = written.get(key)
        problems = ["장면 없음"] if scene is None else check_scene(rule_for(key), scene, req.youngest)
        if problems:
            v.scenes[key] = problems
    return v


def to_episode(draft: Draft, verdict: Verdict, req: EpisodeRequest, story_id: str, model: str) -> Episode:
    written = {s.key: s for s in reversed(draft.scenes)}
    scenes = {
        k: SceneText(bubble=written[k].bubble, speech=written[k].speech, hint=written[k].hint)
        for k in req.keys
        if k not in verdict.scenes
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
        dropped=[k for k in req.keys if k in verdict.scenes],
    )
