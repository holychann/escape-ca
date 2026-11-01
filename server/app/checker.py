"""Checks a draft before any of it can reach a child.

Three checks, as in the plan: the shape (lengths, every scene present), the words (scary, unsafe in a car,
personal, foreign), and the answer (each scene says its own answer and never the other one). A problem in the
episode-wide lines rejects the whole draft; a problem in one scene drops only that scene, and the car plays its
own reviewed lines there instead.
"""
import re
from dataclasses import dataclass, field

from .keys import Rule, rule_for
from .models import Draft, Episode, EpisodeRequest, SceneText

SPEECH_MAX = {"LITTLE": 100, "CAPTAIN": 140, "DETECTIVE": 180}
BUBBLE_MAX = 18
HINT_MAX = 70
TITLE_MAX = 20
INTRO_MAX = 180
SUCCESS_MAX = 60
TREASURE_MAX = 90

# scary, hurtful or rude: the car says these to a child with nobody choosing them first
HARMFUL = [
    r"죽(?!순)", r"피(가|를|투성이)", "귀신", "유령", "괴물", "좀비", "악마", "저주", "살인", r"총(을|으로|알|소리)",
    r"칼(을|로|날)", "폭탄", "폭발", "불타", r"불이\s*나", "화재", "다쳐", "다치", "상처", "비명", "무서", "공포",
    "무시무시", "감옥", "납치", "바보", "멍청", "꺼져", "짜증", "미워", "혼나", "혼날",
]
# would have the family do something unsafe with the car
CAR_UNSAFE = [
    r"(?<![가-힣])차\s*문", r"창문을?\s*열", r"밖으로\s*나가", r"차에서\s*내", r"벨트를?\s*(풀|빼)",
    r"운전(해|하|을)", "출발", "시동", "기어를", "기어 레버", "핸들", "브레이크", "액셀", "엑셀", "일어서",
]
# the app never asks anything about the child
PERSONAL = [r"이름(을|이)\s*뭐", "주소", "전화번호", "비밀번호", r"사진(을|찍)", r"몇\s*살"]
FOREIGN = r"[A-Za-z]|https?:|www\."

_PUNCT = re.compile(r"[\s.,!?~…·'\"“”‘’()\-]")


def norm(text: str) -> str:
    """Spacing and punctuation vary ("하나, 둘, 셋!" / "하나 둘 셋"), the words do not."""
    return _PUNCT.sub("", text)


def bad_words(text: str) -> list[str]:
    found = []
    for group, patterns in (("무서운 말", HARMFUL), ("차 안전", CAR_UNSAFE), ("개인정보", PERSONAL)):
        for p in patterns:
            m = re.search(p, text)
            if m:
                found.append(f"{group}: {m.group(0)}")
    if re.search(FOREIGN, text):
        found.append("영어나 주소")
    return found


def _too_long(name: str, text: str, limit: int) -> list[str]:
    if not text.strip():
        return [f"{name} 비어 있음"]
    return [f"{name} 길이 {len(text)} > {limit}"] if len(text) > limit else []


def usable_success(lines: list[str]) -> list[str]:
    """Praise lines stand alone, so a bad one is left out instead of losing the whole draft."""
    return [x for x in lines if not _too_long("success", x, SUCCESS_MAX) and not bad_words(x)][:3]


def check_scene(rule: Rule, scene: SceneText, youngest: str) -> list[str]:
    problems = (
        _too_long("bubble", scene.bubble, BUBBLE_MAX)
        + _too_long("speech", scene.speech, SPEECH_MAX[youngest])
        + _too_long("hint", scene.hint, HINT_MAX)
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
    v.episode += _too_long("title", draft.title, TITLE_MAX)
    v.episode += _too_long("intro", draft.intro, INTRO_MAX)
    v.episode += _too_long("treasureGift", draft.treasureGift, TREASURE_MAX)
    v.episode += _too_long("treasureBadge", draft.treasureBadge, TREASURE_MAX)
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
