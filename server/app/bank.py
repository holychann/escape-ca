"""The question bank: a few hundred quiz questions made once with the writer model (tools/make_bank.py).

Every question belongs to one car device. A number question is an instruction to it with the amount as a little
puzzle ("ONE만큼 온도를 내려 줘!"); a situation question (REASON) tells a trouble the child solves by finding the
right car function ("앞 유리가 뿌예서 앞이 안 보여!"). Every question was worked out again, answered blind by a
second model and checked when it was made, and is checked again when the server starts, so a hand edit that breaks
one stops the server instead of reaching a child. An episode draws its questions from here at random; the model only
writes the story around them. The car app ships a copy of the same file for when the server cannot be reached.
"""
import json
import logging
import random
import re
from dataclasses import dataclass
from pathlib import Path

from textgate import find_forbidden, norm, too_long

from . import quiz
from .models import DraftQuestion
from .rules import FORBID, LIMITS

log = logging.getLogger("story")
BANK_FILE = Path(__file__).with_name("question_bank.json")
AGES = ("LITTLE", "CAPTAIN", "DETECTIVE")
ODD = re.compile(r"[\x00-\x1f\x7f'\"{}\[\]\\;<>_=@#$%^&|~`()‘’“”ㅤᅟᅠ​-‏﻿]|\s$")
ODD_SCRIPT = re.compile(r"[㐀-鿿぀-ヿ]| {2}| ,")
AMBIGUOUS_STEP = re.compile(r"단\s+(내렸|내리|올렸|올리|줄였|줄이|낮췄|낮추|높였|높이)")
DEVICE_NEVER = {"SEAT_HEAT": ("뜨거", "낮잠", "잠을 자", "오래 켜"), "SEAT_HEAT_ON": ("뜨거", "낮잠", "잠을 자", "오래 켜")}
# a hint for a number-word question that tells the number's place ("ONE은 맨 처음 나오는 말") gives the answer away
PLACE_WORDS = ("맨 처음", "가장 처음", "제일 처음", "바로 다음", "바로 앞", "다음에 오는", "앞에 오는", "손가락 모두", "한 손 전부")
# A situation (REASON) is the friend the child picked speaking as "나", whichever friend that is and wherever the
# story is: it names nobody, no body part only some friends have, nothing tied to one place, and no cause (the story
# before it tells why). It ends asking for the car's buttons ("차 버튼으로 나를 도와줘!", for the driver's seat "차 버튼으로
# 안전 지킴이를 도와줘!") and never names its own button. The app asks it only while the car fits it (quiz.REASON_WHEN)
# and knows neither the season nor the light, so a situation states nothing else about the car or the world it cannot
# check.
REASON_NAMES = ("루미", "펭귄", "새싹 로봇", "토끼 기관사", "수달", "사막여우", "투덜이", "반테", "코냥", "리아", "투부기",
                "파삐", "포또", "이오", "쏠랑이", "캐스핀")
REASON_BODY = ("꼬리", "날개", "수염", "털", "발톱", "부리", "등껍질", "지느러미", "앞발", "뒷발", "귀를", "귀가", "귀에")
REASON_PLACE = ("버스", "쓰레기차", "트럭", "꽃밭", "공사장", "기차", "바다", "바닷", "숲", "놀이터", "학교", "부엌", "식당",
                "수영장", "운동장")
REASON_CAUSE = ("감기", "뛰어놀", "줄넘기", "매운", "얼음 주머니", "물병", "수영", "비옷", "입김", "코코아", "춤", "달리기",
                "자전거", "물풍선", "젖은")
REASON_ENDING = {"SEAT_HEAT_ON": "차 버튼으로 안전 지킴이를 도와줘!", "SEAT_HEAT_OFF": "차 버튼으로 안전 지킴이를 도와줘!"}
REASON_ENDING_DEFAULT = "차 버튼으로 나를 도와줘!"
# 존댓말 at the end of a sentence: every question and hint is said to the child in 반말
POLITE = re.compile(r"(요|니다)[.!?,]|(요|니다)$")
REASON_LEVEL = re.compile(r"(제일|가장)\s*(낮|높|세|약|차갑|뜨겁|따뜻)|(모두|다|전부|빠짐없이)\s*켜|세게\s*(잘\s*)?나오|하나도 빠짐")
REASON_CLAIM = re.compile(r"넉넉|잘 나오|잘 불어|손을 대|닫혀 있")
REASON_CABIN = re.compile(r"차 안이?\s*(추워|춥|쌀쌀|서늘|더워|덥|후끈|찜통)")
REASON_OUTSIDE = re.compile(r"비가 와|비 오는|비 그친|눈 오|눈이 와|가을|겨울|여름|단풍|낙엽|저녁|밤하늘|밤에|해가 져|햇볕|쨍쨍|날씨")
REASON_HARD = ("매캐", "매연", "거름", "대시보드", "흥건", "다이얼", "충전")
REASON_HARD_LITTLE = ("쌀쌀", "고약", "빠짐없이")
REASON_HINT_NEVER = ("눈송이", "반짝이는지", "화면에서", "보일러")
REASON_WIND = re.compile(r"(시원한|따뜻한|차가운|찬|미지근한|뜨뜻한)\s*바람|바람이\s*(시원|따뜻|차갑|미지근)")
# the wind's warmth a situation may tell, where its condition makes it true
REASON_WIND_OK = {("AC", 1): ("미지근", "시원"), ("AC", 0): ("차가", "찬", "시원"), ("TEMP_DOWN", 1): ("시원",)}
REASON_DIAL_NEVER = {
    ("SEAT_HEAT_ON", 1): ("뜨거", "낮잠", "잠을 자", "잠들", "오래 켜", "녹"),
    ("SEAT_HEAT_OFF", 0): ("식혀",),
    ("DEFROST", 1): ("끓",),
    ("TEMP_DOWN", 1): ("녹",),
    ("LIGHT_CHILD", 1): ("자리만", "깜깜", "어두", "어둡", "지하"),
    ("LIGHTS_OFF", 0): ("천장", "환해", "환하", "눈부", "깜깜", "밤하늘"),
}
# a switch a situation says is on (1) or off (0): only the state its own condition already holds
REASON_SWITCHES = {"AC": "에어컨", "SEAT_HEAT": "열선", "LIGHTS": "독서등", "RECIRC": "내기 순환", "DEFROST": "성에 제거"}
REASON_HOLDS = {("AC", 1): {"AC": 0}, ("AC", 0): {"AC": 1}, ("TEMP_UP", 1): {"AC": 0}, ("TEMP_DOWN", 1): {"AC": 1},
                ("RECIRC", 1): {"RECIRC": 0}, ("RECIRC", 0): {"RECIRC": 1}, ("DEFROST", 1): {"DEFROST": 0},
                ("SEAT_HEAT_ON", 1): {"SEAT_HEAT": 0}, ("SEAT_HEAT_OFF", 0): {"SEAT_HEAT": 1}}


@dataclass(frozen=True)
class Entry:
    id: str
    kind: str
    dial: str
    age: str
    text: str
    hint: str
    # what the question works out to: the device's value for a number question, and for a situation (REASON) the
    # value of the function it needs (1 on or the way it goes, 0 off)
    answer: int | None = None
    expr: str = ""
    word: str = ""
    terms: tuple[int, ...] = ()

    def to_json(self) -> dict:
        out = {"id": self.id, "kind": self.kind, "dial": self.dial, "age": self.age, "text": self.text, "hint": self.hint}
        if self.answer is not None:
            out["answer"] = self.answer
        if self.expr:
            out["expr"] = self.expr
        if self.word:
            out["word"] = self.word
        if self.terms:
            out["terms"] = list(self.terms)
        return out

    @staticmethod
    def from_json(d: dict) -> "Entry":
        return Entry(
            id=d["id"], kind=d["kind"], dial=d["dial"], age=d["age"], text=d["text"], hint=d["hint"],
            answer=d.get("answer"), expr=d.get("expr", ""), word=d.get("word", ""), terms=tuple(d.get("terms", ())),
        )


def question(e: Entry) -> DraftQuestion:
    return DraftQuestion(kind=e.kind, text=e.text, expr=e.expr, word=e.word, terms=list(e.terms))


def problems(e: Entry) -> list[str]:
    """Why an entry cannot be used, empty when it can: the same checks a written question gets, and it must be
    about its device."""
    if e.kind not in quiz.KINDS or e.age not in AGES or e.dial not in quiz.DIALS:
        return [f"모르는 종류, 장치나 나이: {e.kind} {e.dial} {e.age}"]
    if e.kind not in quiz.KINDS_FOR[e.dial]:
        return [f"{e.dial}에는 {e.kind} 문제를 내지 않음"]
    lo, hi = quiz.values(e.kind, e.dial)
    if e.answer is None or not lo <= e.answer <= hi:
        return [f"정답은 {lo}~{hi}: {e.answer}"]
    probe = quiz.QuizKey(e.kind, e.dial, e.answer)
    q = question(e)
    out = quiz.check_question(probe, q, e.age)
    # a doubling list (1, 2, 4) also reads as growing by 1, 2, 3, so the question has to say so
    if e.kind == "PATTERN" and len({b - a for a, b in zip(e.terms, e.terms[1:])}) > 1 and "배" not in e.text:
        out.append("곱으로 커지는 수열은 문장에 '두 배씩'처럼 적어야 함")
    out += too_long("hint", e.hint, LIMITS.hint)
    word = quiz.english_word(probe, q)
    # an English question's one word may be in its hint too ("WIND를 한 글자씩 짚어 봐"), and so may counting in
    # English ("ONE, TWO 하고 세어 봐")
    hint = quiz.hint_without_english(e.hint, word)
    out += find_forbidden(" ".join([quiz.without_word(e.text, word), hint]), FORBID)
    # a model's output can end in stray quotes or control characters ("…세어 봐.','"), which the car would read out
    out += [f"이상한 글자: {name}" for name, s in [("문제", e.text), ("힌트", e.hint)]
            if ODD.search(s) or ODD_SCRIPT.search(s)]
    out += [f"문장 끝이 이상함: {name}" for name, s in [("문제", e.text), ("힌트", e.hint)] if s.strip()[-1:] not in ".?!"]
    # heard aloud, "2단 내렸어" is also "2단으로 내렸어": a step change says 더 or 만큼
    if AMBIGUOUS_STEP.search(e.text):
        out.append("단 바꾸기가 두 가지로 들림: '2단 더 올렸어', '2단만큼 내렸어'처럼")
    # another count dressed up as the device's value ("누른 횟수가 열선 단계라면")
    if re.search(r"(단계|개수|세기|온도)(이)?라면", e.text):
        out.append("다른 수를 장치 값으로 갖다 붙임")
    out += [f"이 장치에 맞지 않는 말: {w}" for w in DEVICE_NEVER.get(e.dial, ()) if w in e.text]
    out += [f"존댓말: {name}" for name, s in [("문제", e.text), ("힌트", e.hint)] if POLITE.search(s.strip())]
    if e.kind == "REASON":
        return out + reason_problems(e)
    # the question is the car instruction itself ("ONE만큼 온도를 내려 줘!"), not a question answered first
    things, verbs = quiz.INSTRUCTION[e.dial]
    said = norm(e.text)
    if not any(norm(w) in said for w in things) or not any(norm(v) in said for v in verbs):
        out.append(f"차 조작 지시가 아님: {'/'.join(things)}와 {'/'.join(verbs)} 중 하나가 있어야 함")
    if "몇" in e.text or e.text.strip().endswith("?"):
        out.append("묻는 문장이 아니라 지시하는 문장이어야 함")
    if not any(w in norm(e.text) for w in quiz.DEVICE_WORDS[e.dial]):
        out.append(f"장치 이야기가 아님: {', '.join(quiz.DEVICE_WORDS[e.dial])} 중 하나가 없음")
    if quiz.leaks(quiz.without_word(e.hint, word), e.answer) or quiz.leaks(quiz.without_word(e.text, word), e.answer):
        out.append("정답 수가 보임")
    elif word in quiz.NUMBER_WORDS and (said := [w for w in PLACE_WORDS if norm(w) in norm(e.hint)]):
        out.append(f"힌트가 숫자의 차례를 알려 줌: {', '.join(said)}")
    elif word and word not in quiz.NUMBER_WORDS and re.search(
            rf"(?<![A-Za-z]){quiz.ENGLISH_OF[e.answer]}(?![A-Za-z])", e.hint + " " + e.text, re.IGNORECASE):
        # a letter-counting question whose hint counts up to the answer in English ("CAT … ONE, TWO, THREE")
        out.append("정답 수가 영어로 보임")
    return out


def switch_claims(text: str) -> dict[str, int]:
    """The switches a sentence says are on (1) or off (0): "에어컨은 켜져 있는데도" is {"AC": 1}."""
    out = {}
    for key, name in REASON_SWITCHES.items():
        mid = r"[은는이가을를]?\s*(계속\s*|이미\s*|벌써\s*)?"
        if re.search(name + mid + r"(켜져|켜 둔|켜 두|켜 둬|켜 놓|켜놓)", text) or re.search(name + r"을 끄면", text):
            out[key] = 1
        elif re.search(name + mid + r"(꺼져|꺼 둔|꺼 두)", text):
            out[key] = 0
    return out


def reason_problems(e: Entry) -> list[str]:
    """A situation tells a trouble and asks for the car's buttons; the child finds which one. See REASON_SPEAKER and
    the rules after it."""
    out = []
    side = (e.dial, e.answer)
    both = e.text + " " + e.hint
    if e.age == "LITTLE" and e.dial in quiz.REASON_NOT_LITTLE:
        out.append(f"4~6세에는 내지 않는 조작: {e.dial}")
    if "차 버튼" not in e.text:
        out.append("차 버튼으로 풀라는 부탁이 없음")
    if "기능" in both:
        out.append("기능이라는 말")
    if "몇" in e.text or e.text.strip()[-1:] not in ".!":
        out.append("묻는 문장이 아니라 상황을 말하고 부탁하는 문장이어야 함")
    out += [f"정답 버튼 이름: {w}" for w in quiz.REASON_OWN[e.dial] if w in both]
    out += [f"이름: {w}" for w in REASON_NAMES if w in both]
    out += [f"몸 특징: {w}" for w in REASON_BODY if w in both]
    out += [f"장소에 묶인 말: {w}" for w in REASON_PLACE if w in both]
    out += [f"까닭(이야기가 정함): {w}" for w in REASON_CAUSE if w in e.text]
    ending = REASON_ENDING.get(e.dial, REASON_ENDING_DEFAULT)
    if not e.text.strip().endswith(ending):
        out.append(f"끝맺음이 '{ending}'가 아님")
    for name, rx in [("단계나 개수를 단정", REASON_LEVEL), ("차 상태를 단정", REASON_CLAIM), ("차 안 온도를 단정", REASON_CABIN),
                     ("날씨, 계절, 밤낮을 단정", REASON_OUTSIDE)]:
        if m := rx.search(e.text):
            out.append(f"{name}: {m.group()}")
    for m in REASON_WIND.finditer(e.text):
        if not any(ok in m.group() for ok in REASON_WIND_OK.get(side, ())):
            out.append(f"바람 온도를 단정: {m.group()}")
    out += [f"어려운 말: {w}" for w in REASON_HARD if w in both]
    if e.age == "LITTLE":
        out += [f"4~6세에 어려운 말: {w}" for w in REASON_HARD_LITTLE if w in both]
    out += [f"이 조작에 맞지 않는 말: {w}" for w in REASON_DIAL_NEVER.get(side, ()) if w in both]
    out += [f"힌트에 쓰지 않을 말: {w}" for w in REASON_HINT_NEVER if w in e.hint]
    if e.dial == "TEMP_UP" and "데워 주는 버튼" in e.hint:
        out.append("힌트가 의자 데우기로 들림")
    holds = REASON_HOLDS.get(side, {})
    if said := {k: v for k, v in switch_claims(e.text).items() if holds.get(k) != v}:
        out.append(f"조건에 없는 버튼 상태를 말함: {said}")
    if side == ("AC", 0) and not re.search(r"켜져|켜 두|켜 둔", e.text):
        out.append("켜져 있는 것을 끄라는 단서가 없음")
    if side == ("LIGHTS_OFF", 0) and "하나도" not in e.text:
        out.append("목표가 작은 불이 하나도 안 켜져 있게가 아님")
    if e.dial == "FAN_DOWN" and "얼굴" in e.text and (e.age == "LITTLE" or "쪽은 그대로" not in e.text):
        out.append("얼굴로 부는 바람: 방향 바꾸기로도 풀림")
    return out


class Bank:
    def __init__(self, entries: list[Entry]):
        self.entries = entries

    @staticmethod
    def fits(e: Entry, q: quiz.QuizKey) -> bool:
        return e.kind == q.kind and e.dial == q.dial and e.answer == q.value

    def candidates(self, q: quiz.QuizKey, age: str) -> list[Entry]:
        """Questions for this key at this age; when there are none, the ones made for a younger age (easier is fine)."""
        for a in reversed(AGES[: AGES.index(age) + 1]):
            found = [e for e in self.entries if e.age == a and self.fits(e, q)]
            if found:
                return found
        return []

    def pick(self, q: quiz.QuizKey, age: str, rnd: random.Random) -> tuple[DraftQuestion, str] | None:
        """A random question for this key and its hint, or None when the bank has none (the car asks its own)."""
        found = self.candidates(q, age)
        if not found:
            return None
        e = rnd.choice(found)
        return question(e), e.hint


def read(path: Path = BANK_FILE) -> list[Entry]:
    if not path.is_file():
        log.warning("no question bank at %s: every quiz scene will be played with the car's own questions", path)
        return []
    return [Entry.from_json(d) for d in json.loads(path.read_text(encoding="utf-8"))["questions"]]


def load(path: Path = BANK_FILE) -> Bank:
    """Raises ValueError naming every broken entry: a bank that fails its own checks must not start."""
    entries = read(path)
    broken = {e.id: p for e in entries if (p := problems(e))}
    if broken:
        raise ValueError(f"question bank has broken entries: {broken}")
    ids = [e.id for e in entries]
    if len(set(ids)) != len(ids):
        raise ValueError("question bank has duplicate ids")
    return Bank(entries)


DIAL_ORDER = ("FAN", "TEMP_UP", "TEMP_DOWN", "LIGHTS", "SEAT_HEAT", "AC", "RECIRC", "DEFROST", "FAN_UP", "FAN_DOWN",
              "SEAT_HEAT_ON", "SEAT_HEAT_OFF", "LIGHT_CHILD", "LIGHTS_OFF")


def write(entries: list[Entry], path: Path = BANK_FILE) -> None:
    kinds = {k: i for i, k in enumerate(quiz.KINDS)}
    entries = sorted(entries, key=lambda e: (DIAL_ORDER.index(e.dial), kinds[e.kind], AGES.index(e.age),
                                             e.answer if e.answer is not None else -1, e.id))
    body = {"version": 2, "questions": [e.to_json() for e in entries]}
    path.write_text(json.dumps(body, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
