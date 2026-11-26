"""Checks a draft before any of it can reach a child.

Three checks, as in the plan: the shape (lengths, every scene present), the words (scary, unsafe in a car,
personal, foreign), and the answer (each scene says its own answer and never the other one). A problem in the
episode-wide lines rejects the whole draft; a problem in one scene drops only that scene, and the car plays its
own reviewed lines there instead.
"""
import re

from textgate import Verdict, too_long

from . import quiz
from .models import FRIEND_LABEL, Draft, DraftQuestion, DraftScene, Episode, EpisodeRequest, Question, SceneText
from .rules import BUBBLE_NEVER, EVERYWHERE_NEVER, FORBID, LIMITS, Rule, rule_for
from .text_guard import clean_text, find_forbidden, missing, norm, polite, present


def bad_words(text: str) -> list[str]:
    return find_forbidden(text, FORBID)


def wish_problems(wish: str) -> list[str]:
    """A wish that asks for something scary, unsafe in a car or personal is refused before any model sees it.
    English is fine in a wish ("T-rex"); the writer still answers in Korean."""
    return find_forbidden(wish, [f for f in FORBID if f.quote_match])


def speak_problems(text: str) -> list[str]:
    """Checks a line sent to POST /v1/speak before synthesizing (and potentially falling back to the paid key).
    Allows an English quiz word inside a Korean sentence ("two만큼 온도를 올려 줘!"), while blocking scary/unsafe/personal
    words, URLs, and non-Korean spam."""
    clean = clean_text(text)
    out = find_forbidden(text, [f for f in FORBID if f.quote_match])
    if re.search(r"https?:|www\.", clean, re.IGNORECASE):
        out.append("영어나 주소")
    if not re.search(r"[가-힣]", clean):
        out.append("우리말 없음")
    return out


# the chosen friend is the only one in the story: Lumi and the old crew are gone, and the other book friends stay out
GONE = ("루미", "펭귄 선장", "사막여우", "토끼 기관사", "수달 요리사", "새싹 로봇", "투덜이 기계")


def other_names(text: str, req: EpisodeRequest) -> list[str]:
    mine = FRIEND_LABEL[req.friend][0]
    names = [n for n, *_ in FRIEND_LABEL.values() if n != mine] + list(GONE)
    return [f"다른 인물: {n}" for n in names if n in text]


# the result tells the change as part of the story's place, never the real car ("차 안이 따뜻해졌어"); a story vehicle
# ("기차 안", "마차에서") is fine, so 차 must start a word
REAL_CAR = re.compile(r"(?<![가-힣])차\s?(안|속)|(?<![가-힣])차에서|우리\s?차(?![가-힣])")


def result_problems(scene: DraftScene, req: EpisodeRequest) -> list[str]:
    """The line said once the answer is in may name the answer, since the child has just put it in. A bad one is
    left out on its own (the car says its own praise there) instead of losing the scene."""
    text = scene.result
    problems = too_long("result", text, getattr(LIMITS.result, req.youngest)) + bad_words(text)
    if REAL_CAR.search(text):
        problems.append("진짜 차를 말함")
    if polite(text):
        problems.append("존댓말")
    problems += [f"쓰지 말 말: {w}" for w in present(text, EVERYWHERE_NEVER)]
    return problems + other_names(text, req)


def check_scene(rule: Rule, scene: SceneText, youngest: str) -> list[str]:
    problems = (
        too_long("bubble", scene.bubble, LIMITS.bubble)
        + too_long("speech", scene.speech, getattr(LIMITS.speech, youngest))
        + too_long("hint", scene.hint, LIMITS.hint)
    )
    problems += bad_words(" ".join([scene.bubble, scene.speech, scene.hint]))
    if any(polite(t) for t in (scene.bubble, scene.speech, scene.hint)):
        problems.append("존댓말")
    problems += [f"정답 말 빠짐: {'/'.join(g)}" for g in missing(scene.speech, rule.must)]
    problems += [f"힌트에 정답 말 빠짐: {'/'.join(g)}" for g in missing(scene.hint, rule.hint_must)]
    problems += [f"다른 정답 말: {w}" for w in present("|".join([scene.bubble, scene.speech, scene.hint]), rule.never)]
    problems += [f"말풍선에 버튼 말: {w}" for w in present(scene.bubble, BUBBLE_NEVER)]
    if rule.needs_guardian and "안전지킴이" not in norm(scene.speech):
        problems.append("안전 지킴이에게 알리는 말 빠짐")
    return problems


def check_quiz_scene(q: quiz.QuizKey, scene: DraftScene, youngest: str) -> list[str]:
    """A quiz scene: the question works out to the app's answer at this age, and no line gives the answer away."""
    problems = (
        too_long("bubble", scene.bubble, LIMITS.bubble)
        + too_long("speech", scene.speech, getattr(LIMITS.speech, youngest))
        + too_long("hint", scene.hint, LIMITS.hint)
    )
    question = scene.question
    problems += quiz.check_question(q, question, youngest)
    if question is None:
        return problems
    # the English question's one word is the only English the car may say, and the bank's hint may also count in
    # English (bank.problems allows the same)
    word = quiz.english_word(q, question)
    asked = quiz.without_word(question.text, word)
    problems += bad_words(" ".join([scene.bubble, scene.speech, quiz.hint_without_english(scene.hint, word), asked]))
    if any(polite(t) for t in (scene.bubble, scene.speech)):
        problems.append("존댓말")
    problems += [f"말풍선에 버튼 말: {w}" for w in present(scene.bubble, BUBBLE_NEVER)]
    # only the words written for this episode: the bank's hint was checked when it was made and suits any device
    problems += [f"반대로 들리는 말: {w}" for w in present(" ".join([scene.bubble, scene.speech]), quiz.other_way(q))]
    if q.kind == "REASON":
        # the child works the function out from the trouble, so the story never names the button or tells what to do
        problems += [f"이야기에 버튼 말: {w}" for w in present(scene.speech, quiz.BUTTON_NAMES)]
        problems += [f"이야기에 조작 말: {w}" for w in quiz.action_words(scene.speech)]
    else:
        lines = {"말풍선": scene.bubble, "이야기": scene.speech, "힌트": scene.hint, "문제": question.text}
        problems += [f"{name}에 정답 수" for name, text in lines.items() if quiz.leaks(text, q.value)]
    return problems


def scene_text(key: str, scene: DraftScene, req: EpisodeRequest) -> SceneText:
    """What the car gets for one checked scene; its result line only when that passes too."""
    q = quiz.parse(key)
    question = None
    if q is not None and scene.question is not None:
        question = to_question(q, scene.question)
    result = scene.result.strip() if not result_problems(scene, req) else ""
    return SceneText(bubble=scene.bubble, speech=scene.speech, hint=scene.hint, result=result, question=question)


def to_question(q: quiz.QuizKey, dq: DraftQuestion) -> Question:
    """Only the parts of the question's kind reach the car."""
    return Question(
        kind=q.kind,
        text=dq.text.strip(),
        expr=dq.expr.strip() if q.kind == "MATH" else "",
        word=dq.word.strip().upper() if q.kind == "ENGLISH" else "",
        terms=list(dq.terms) if q.kind == "PATTERN" else [],
    )


ART_MAX = 200


def answer_words(slot: list[str]) -> list[str]:
    """Words that tell one answer of this puzzle slot from another, so a picture must not show them.

    For a quiz that is the answer number. For the belt mission, every word a variant forbids, plus the required
    words not shared by all variants.
    """
    quizzes = [q for k in slot if (q := quiz.parse(k)) is not None]
    if quizzes:
        return sorted({w for q in quizzes for w in quiz.answer_words(q)})
    rules = [rule_for(k) for k in slot]
    words = {w for r in rules for w in r.never} | {w for r in rules for w in r.art_never}
    for r in rules:
        for group in r.must:
            if not all(group in other.must for other in rules):
                words.update(group)
    return sorted(words)


def art_problems(draft: Draft, req: EpisodeRequest) -> dict[str, list[str]]:
    """Problems per picture name ("cover", "p1"..); a picture with problems is simply not drawn."""
    art = draft.art
    if art is None:
        return {"cover": ["설명 없음"], **{f"p{i}": ["설명 없음"] for i in range(1, len(req.puzzles) + 1)}}
    out: dict[str, list[str]] = {}

    def look(name: str, text: str, extra: list[str]) -> None:
        problems = too_long(name, text, ART_MAX) + bad_words(text) + other_names(text, req)
        problems += [f"정답이 보이는 말: {w}" for w in present(text, extra)]
        problems += [f"버튼 말: {w}" for w in present(text, BUBBLE_NEVER)]
        if problems:
            out[name] = problems

    look("cover", art.cover, [])
    for i, slot in enumerate(req.puzzles, start=1):
        text = art.puzzles[i - 1] if i <= len(art.puzzles) else ""
        look(f"p{i}", text, answer_words(slot))
    return out


def check(draft: Draft, req: EpisodeRequest) -> Verdict:
    """Episode-wide problems go to ``whole``, scene problems to ``items`` under the scene's key."""
    v = Verdict(checked=req.keys)
    v.whole += too_long("title", draft.title, LIMITS.title)
    v.whole += too_long("intro", draft.intro, LIMITS.intro)
    v.whole += too_long("treasureBadge", draft.treasureBadge, LIMITS.treasure)
    if req.theme and draft.theme != req.theme:
        v.whole.append("고른 테마와 다름")
    if FRIEND_LABEL[req.friend][0] not in draft.intro:
        v.whole.append("첫인사에 고른 친구 소개 빠짐")
    episode_text = " ".join([draft.title, draft.intro, draft.treasureBadge, draft.world])
    v.whole += bad_words(episode_text)
    if any(polite(t) for t in (draft.title, draft.intro, draft.treasureBadge)):
        v.whole.append("존댓말")
    v.whole += other_names(episode_text, req)

    written = {}
    for s in draft.scenes:
        written.setdefault(s.key, s)
    for key in req.keys:
        scene = written.get(key)
        if scene is None:
            problems = ["장면 없음"]
        elif (q := quiz.parse(key)) is not None:
            problems = check_quiz_scene(q, scene, req.youngest)
        else:
            problems = check_scene(rule_for(key), scene, req.youngest)
        if scene is not None:
            # a quiz scene's hint is the bank's, checked when the bank was made
            written_here = [scene.bubble, scene.speech] + ([scene.hint] if quiz.parse(key) is None else [])
            problems += other_names(" ".join(written_here), req)
        if problems:
            v.items[key] = problems
    return v


def to_episode(draft: Draft, verdict: Verdict, req: EpisodeRequest, story_id: str, model: str) -> Episode:
    written = {s.key: s for s in reversed(draft.scenes)}
    scenes = {k: scene_text(k, written[k], req) for k in req.keys if k not in verdict.items}
    return Episode(
        storyId=story_id,
        model=model,
        theme=draft.theme,
        title=draft.title,
        intro=draft.intro,
        treasureBadge=draft.treasureBadge,
        scenes=scenes,
        dropped=[k for k in req.keys if k in verdict.items],
        world=draft.world,
    )
