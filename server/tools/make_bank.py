"""Makes the question bank (app/question_bank.json, and a copy in the car app's assets) with Claude, once.

Every question is about one car device and is one of two sorts:
  - a number question (계산, 영어, 규칙 찾기): an instruction to the device with the amount as a little puzzle
    ("ONE만큼 온도를 내려 줘!")
  - a situation (추론, REASON): a trouble the child solves by finding the right car function and using it
    ("앞 유리가 뿌예서 앞이 안 보여!" is solved with the front defroster)
and must pass three checks before it goes in:
  1. app/bank.py problems(): the answer is worked out again from the formula, number word or number list, the
     age's difficulty is kept, the device is in the question (a situation names no button but has a word pointing at
     its own function), and nothing scary, unsafe or giving the answer away
  2. a second, smaller model reads only the question and must come to the same answer: the amount for a number
     question, and for a situation the right one of all the situation functions, listed in a shuffled order
  3. no question is in the bank twice
Run it again to fill the cells that are still short; questions already in the bank stay (--fresh starts over). It
calls the models, so it costs money (a full bank is a few dozen writer calls and a few hundred short checker calls).

    .\\.venv\\Scripts\\python -m tools.make_bank --md ../../차근차근탐험단_문제은행.md     # also writes every question as a list to read
"""
import argparse
import json
import logging
import os
import random
import re
import shutil
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import anthropic

from app import bank, quiz
from app.text_guard import norm
from app.writer import DEFAULT_MODEL, SOLVER_CLAUDE, SOLVER_TOKENS, picked, solver_prompt

log = logging.getLogger("bank")
HERE = Path(__file__).resolve().parent
CAR_COPY = HERE.parent.parent / "car" / "app" / "src" / "main" / "assets" / "question_bank.json"

AGES = bank.AGES
ROUNDS = 4
# number questions per (answer value, age) for each device's number kinds
PER_VALUE = {
    "FAN": {"MATH": 2, "ENGLISH": 1, "PATTERN": 1},
    "LIGHTS": {"MATH": 2, "ENGLISH": 1, "PATTERN": 1},
    "SEAT_HEAT": {"MATH": 2, "ENGLISH": 1},
    "TEMP_UP": {"MATH": 2, "ENGLISH": 1},
    "TEMP_DOWN": {"MATH": 2, "ENGLISH": 1},
}
# situations per (device, value) and age (none for the 4~6 on quiz.REASON_NOT_LITTLE)
REASONS = {side: 3 for side in quiz.REASON_ACTIONS}

AGE_LABEL = {"LITTLE": "4~6세(꼬마 탐험대)", "CAPTAIN": "7~9세(탐험대장)", "DETECTIVE": "10~12세(수석 탐정)"}
KIND_LABEL = quiz.KIND_LABEL
REASON_SECTION = "추론(상황 해결)"
DIAL_LABEL = {"FAN": "풍량(바람 세기)", "TEMP_UP": "온도 올리기", "TEMP_DOWN": "온도 내리기", "LIGHTS": "독서등",
              "SEAT_HEAT": "운전석 시트 열선", "AC": "에어컨", "RECIRC": "내기 순환", "DEFROST": "앞 유리 성에 제거",
              "FAN_UP": "바람 세게", "FAN_DOWN": "바람 약하게", "SEAT_HEAT_ON": "운전석 열선 켜기",
              "SEAT_HEAT_OFF": "운전석 열선 끄기", "LIGHT_CHILD": "뒷자리 독서등 켜기", "LIGHTS_OFF": "독서등 모두 끄기"}

THEME = {
    "FAN": "바람 세기(1~8단)를 맞추는 지시다. 지시대로 하면 바람이 몇 단이 되는지가 답이다. 예: '바람 세기를 2 더하기 1단으로 맞춰 줘!', '바람 세기를 THREE단으로 맞춰 줘!', '바람 세기를 1단, 2단, 3단 다음 단으로 맞춰 줘!'.",
    "TEMP_UP": "계산과 영어는 온도를 몇 도 올리라는 지시다. 지시대로 하면 몇 도를 올리는지가 답(1~3도)이다. 예: 'ONE만큼 온도를 올려 줘!', '1 더하기 1도만큼 온도를 올려 줘!'. 상황 추리는 내 몸이 으슬으슬 떨려서 차 안 공기를 조금 따뜻하게 하는 상황이고, 온도를 조금이라도 올리면 정답이다. 의자 데우기를 막는다. 예: '내 몸이 으슬으슬 떨려. 의자 말고 차 안 공기를 따뜻하게, 차 버튼으로 나를 도와줘!'",
    "TEMP_DOWN": "계산과 영어는 온도를 몇 도 내리라는 지시다. 지시대로 하면 몇 도를 내리는지가 답(1~3도)이다. 예: 'TWO만큼 온도를 내려 줘!', '3 빼기 1도만큼 온도를 내려 줘!'. 상황 추리는 에어컨이 켜져 있는데도 내가 아직 조금 더운 상황이고, 온도를 조금이라도 내리면 정답이다. 바람 세게를 막는다. 예: '에어컨은 켜져 있는데 나는 아직 조금 더워. 바람이 세지면 맞춰 둔 퍼즐 조각이 흩어지니까 차 버튼으로 나를 도와줘!'",
    "LIGHTS": "켜진 독서등(자리 위 작은 불)의 개수를 맞추는 지시다. 지시대로 하면 독서등이 모두 몇 개 켜지는지가 답(1~4개)이다. 예: '켜진 독서등이 모두 TWO개가 되게 해 줘!', '켜진 독서등이 모두 1 더하기 2개가 되게 해 줘!'.",
    "SEAT_HEAT": "운전석 의자 열선 단계(1~3단)를 맞추는 지시다. 지시대로 하면 열선이 몇 단이 되는지가 답이다. 예: '의자 열선을 TWO단으로 맞춰 줘!', '의자 열선을 3 빼기 2단으로 맞춰 줘!'.",
    "AC": "에어컨을 켜거나 끄면 풀리는 상황이다. answer 1(켜기)은 내가 더운데 바람은 나와도 시원하지 않은 상황(예: '나 땀이 뻘뻘 나, 바람은 나오는데 안 시원해. 창문은 닫아 둔 채로 차 버튼으로 나를 도와줘!'), answer 0(끄기)은 내가 추운데 더울 때 켜는 시원한 바람이 켜져 있는 상황(예: '나 담요를 덮어도 오들오들 추운데 더울 때 켜는 시원한 바람이 켜져 있어. 온도 숫자는 그대로 두고 차 버튼으로 나를 도와줘!')이다.",
    "RECIRC": "내기 순환을 켜거나 끄면 풀리는 상황이다. answer 1(켜기, 바깥 공기 막기)은 바람 구멍으로 냄새나 먼지가 들어오고 그것이 차 밖에서 오는 것 같은 상황(예: '바람 구멍 앞에만 가면 이상한 냄새가 나서 콜록콜록 기침이 나와. 냄새는 차 밖에서 오는 것 같으니 바람은 계속 나오게 두고 차 버튼으로 나를 도와줘!'), answer 0(끄기, 바깥 공기 들이기)은 같은 공기만 돌아서 답답한데 창문은 닫아 둬야 하는 상황(예: '같은 공기만 빙글빙글 돌아서 나는 숨이 답답해. 창문은 닫아 둔 채로, 차 버튼으로 나를 도와줘!')이다.",
    "DEFROST": "앞 유리 성에 제거를 켜면 풀리는 상황이다. 앞 유리 안쪽이 뿌예지려고 하고, 와이퍼는 바깥만 닦는다. 예: '앞 유리 안쪽이 뿌예지려고 해, 와이퍼는 바깥만 닦아. 차 버튼으로 나를 도와줘!'",
    "FAN_UP": "바람을 지금보다 세게 하면 풀리는 상황이다. 예: '내 바람개비가 느릿느릿 돌아. 덥지도 춥지도 않은데, 빙글빙글 빨리 돌게 차 버튼으로 나를 도와줘!'",
    "FAN_DOWN": "바람을 지금보다 약하게 하면 풀리는 상황이다. 바람은 조금 나와야 한다. 예: '바람이 쌩쌩 불어서 내 그림 종이가 날아가. 바람은 조금 나와야 하니까 차 버튼으로 나를 도와줘!'",
    "SEAT_HEAT_ON": "운전석 열선을 켜면 풀리는 상황이다. 안전 지킴이 의자만 차갑고 차 안 공기는 괜찮다. 예: '안전 지킴이 의자가 차가워졌어. 차 안 공기 말고 의자를 데우게 차 버튼으로 안전 지킴이를 도와줘!'",
    "SEAT_HEAT_OFF": "운전석 열선을 끄면 풀리는 상황이다. 안전 지킴이 의자가 계속 데워져서 뜨끈하다. 예: '안전 지킴이 의자가 찐빵처럼 계속 뜨끈해. 의자가 그만 데워지게 차 버튼으로 안전 지킴이를 도와줘!'",
    "LIGHT_CHILD": "뒷자리 독서등을 켜면 풀리는 상황이다. 어둡다고 하지 않고, 내가 준 그림이 개미만큼 작아서 네 자리가 더 밝아야 한다고 쓴다. 독서등은 아이가 벨트를 맨 채로 직접 누른다. 예: '내가 준 그림 지도가 개미만큼 작아서 네 자리에 빛이 더 있어야 잘 보여. 창문은 깨끗해, 차 버튼으로 나를 도와줘!'",
    "LIGHTS_OFF": "켜져 있는 독서등을 모두 끄면 풀리는 상황이다. 밝기를 말하지 않고, 작은 불이 하나도 안 켜져 있어야 한다고 쓴다. 예: '나 반짝 별 스티커를 보고 싶어. 하나만 말고 작은 불이 하나도 안 켜져 있게 차 버튼으로 나를 도와줘!'",
}

SYSTEM = """너는 주차한 차 안에서 아이와 부모가 함께 하는 놀이의 퀴즈 출제자다. 아이가 고른 친구(강아지, 고양이, 토끼, 거북이 등 누구든)가 네 문장을 차 스피커로 아이에게 그대로 읽어 준다. 문제는 두 가지다. 계산, 영어, 규칙 찾기는 차에 하는 지시이고, 지시 속의 수가 작은 퀴즈로 숨어 있다. 상황 추리는 곤란한 상황 하나를 들려주고, 아이가 그 상황을 풀 차 기능을 스스로 찾게 한다. 아이가 차 기능을 바꾸는 것이 곧 정답을 넣는 것이다. 예: 'ONE만큼 온도를 내려 줘!'(아이는 ONE이 1인 걸 알고 온도를 1도 내린다), '앞 유리가 뿌예서 앞이 안 보여!'(아이는 앞 유리 성에 제거를 켠다).

지시 문장 (계산, 영어, 규칙 찾기)
- text는 한 문장의 지시로 쓰고 느낌표나 마침표로 끝낸다. '몇일까?'처럼 묻지 않는다.
- 장치 이름과 할 일을 분명히 넣는다: 바람 세기를 ~단으로 맞춰 줘, ~만큼 온도를 올려 줘, ~만큼 온도를 내려 줘, 켜진 독서등이 모두 ~개가 되게 해 줘, 의자 열선을 ~단으로 맞춰 줘.
- 수는 퀴즈로만 나온다(2 더하기 1, THREE, 1단 2단 3단 다음). 정답 수 자체는 text와 hint 어디에도 숫자나 말로 쓰지 않는다.
- 아이가 귀로 듣고 바로 풀 수 있게 짧고 쉽게 쓴다. 이야기 꾸밈은 넣지 않는다(이야기는 따로 들려준다).

상황 문장 (상황 추리, REASON)
- 고른 친구가 '나'로 아이에게 하는 말이다. 아이는 '너, 네'로 부르고, 친구 이름, 루미, 다른 등장인물은 쓰지 않는다. 어느 동물 친구가 말해도 맞게 꼬리, 날개, 털, 수염, 귀, 발톱, 부리, 등껍질 같은 몸의 특징은 쓰지 않는다. 다정한 반말로 쓰고 '~요', '~니다'로 끝내지 않는다.
- 어느 장소에서 놀아도 맞게 버스, 꽃밭, 기차, 바다, 숲, 놀이터처럼 장소에 묶인 것은 쓰지 않는다. 바람개비, 깃발, 종이, 그림 지도처럼 어디에나 있을 수 있는 물건은 괜찮다.
- 까닭(감기 걸려서, 뛰어놀아서, 입김 때문에, 얼음 주머니를 올려 둬서, 젖은 옷 때문에)은 쓰지 않는다. 문제 앞 이야기가 까닭을 정한다. 다만 판단에 꼭 필요한 단서(냄새가 차 밖에서 온다)는 남긴다.
- 곤란한 상황을 말하고 '차 버튼으로 나를 도와줘!'로 끝낸다. 운전석 의자 문제만 '차 버튼으로 안전 지킴이를 도와줘!'로 끝낸다. '기능'이라는 말은 text와 hint에 쓰지 않는다. 느낌표나 마침표로 끝내고 묻지 않는다.
- 정답 버튼의 이름은 text와 hint에 쓰지 않는다. 다른 버튼 이름은 그 버튼이 답이 아니라고 막는 말('온도 숫자는 그대로 두고', '창문은 닫아 둔 채로')에만 쓴다.
- 앱은 나오는 조건이 맞을 때만 이 문제를 낸다. 그 조건이 보장하지 않는 차 상태(다른 버튼이 켜졌는지, 몇 단인지, 몇 개 켜졌는지, 바람이 시원한지)와 날씨, 계절, 낮밤, 밝기는 단정하지 않는다.
- 상황은 차 조작 하나만 가리키게 쓰고, 아이가 하기 쉬운 다른 조작(바람 끄기, 창문 열기, 바람 방향 바꾸기, 와이퍼)은 막는 말로 막는다.
- answer에는 요청에 적힌 값(1은 켜기나 그쪽으로 바꾸기, 0은 끄기)을 그대로 적는다.

나이 난이도 (꼭 지킨다, 어려우면 아이가 못 푼다)
- 4~6세: 5 이하 수의 덧셈이나 뺄셈 한 번, 숫자 영단어 ONE~TEN, 1씩 커지거나 작아지는 수 3개, 단서 하나로 바로 알 수 있는 상황(70자 이하, 막는 말은 하나까지).
- 7~9세: 10 이하 수의 덧셈이나 뺄셈 한 번, 숫자 영단어 ONE~TEN, 1이나 2씩 커지거나 작아지는 수 3개, 쉬운 답 하나를 막는 말이 들어 있는 상황(90자 이하).
- 10~12세: 덧셈, 뺄셈, 곱셈 두 번까지(곱하는 수는 5 이하, 수는 12 이하), 숫자 영단어나 쉬운 영어 단어의 글자 수, 1~3씩 커지거나 작아지는 수, 단서 두 개를 함께 따져야 풀리는 상황(120자 이하).

문제 종류
- 계산(MATH): expr에 text 속 셈을 숫자와 + - * 괄호로만 적는다(예: 2+1, 3-1, 2*2). text에는 셈을 '2 더하기 1', '3 빼기 1', '2 곱하기 2'처럼 말로 쓴다. 정답과 같은 수는 식에 넣지 않는다.
- 영어(ENGLISH): word에 영어 단어 하나를 대문자로 적고 text에도 그대로 쓴다. 숫자 영단어(ONE~TEN)면 그 수만큼(예: 'TWO만큼 온도를 내려 줘!'). 10~12세는 'WIND의 글자 수만큼 바람 세기를 맞춰 줘!'처럼 장치와 관계있는 쉬운 단어의 글자 수도 쓸 수 있다.
- 규칙 찾기(PATTERN): terms에 수 3개를 차례로 적고, 그다음 수가 답이 되게 한다. text는 '바람 세기를 1단, 2단, 3단 다음 단으로 맞춰 줘!'처럼 쓴다.
- 상황 추리(REASON): 위의 상황 문장 규칙대로 쓴다. expr과 word는 "", terms는 []로 둔다.
- 계산, 영어, 규칙 찾기의 answer에는 지시대로 했을 때의 값(단계, 개수, 올리거나 내릴 온도)을 적는다.

반드시 지킬 것
- hint는 막혔을 때 듣는 도움말 한 문장(70자 이내)이다. 푸는 방법만 알려 주고 답이나 숫자의 차례('맨 처음 나오는 말', '바로 다음 수'), 버튼 이름은 말하지 않는다.
- 무섭거나 다치는 말, 차 문이나 창문 열기, 운전, 출발 같은 말은 쓰지 않는다. 열선을 켜는 것은 따뜻하게 하려는 것으로만 쓰고, 오래 켜 두거나 잠드는 이야기는 쓰지 않는다.
- 영어는 영어 문제의 단어 하나만 쓴다. 괄호, 따옴표, 특수 문자, 이모지는 쓰지 않는다.
- 문제마다 수와 표현을 다르게 해서 서로 겹치지 않게 한다.
- 쓰지 않는 칸은 비워 둔다(expr, word는 "", terms는 []).

다른 모델이 문장만 읽고 다시 푼다. 지시는 장치 값이 얼마가 되는지, 상황은 모든 차 기능 목록 가운데 어느 것으로 풀리는지 답하게 해서, 답이 다르면 그 문제는 버려진다. 헷갈리지 않게, 답이 하나로 정해지게 쓴다."""

NUMBER_SOLVER = ("너는 어린이 퀴즈를 푸는 사람이다. 차에 하는 지시를 읽고, 지시대로 하면 장치 값(바람 단계, 켜진 독서등 개수, 열선 단계, "
                 "올리거나 내릴 온도의 크기)이 얼마가 되는지 아라비아 숫자 하나로만 답한다.")
REASON_SOLVER = "너는 어린이 퀴즈를 푸는 사람이다. 차 안의 상황을 읽고, 그 상황을 풀 차 기능을 보기에서 하나만 골라 번호를 숫자 하나로만 답한다."


def schema() -> dict:
    item = {
        "type": "object",
        "properties": {
            "kind": {"type": "string", "enum": list(quiz.KINDS)}, "answer": {"type": "integer"},
            "text": {"type": "string"}, "expr": {"type": "string"}, "word": {"type": "string"},
            "terms": {"type": "array", "items": {"type": "integer"}}, "hint": {"type": "string"},
        },
        "required": ["kind", "answer", "text", "expr", "word", "terms", "hint"],
        "additionalProperties": False,
    }
    return {"type": "object", "properties": {"questions": {"type": "array", "items": item}},
            "required": ["questions"], "additionalProperties": False}


def level_rule(kind: str, age: str) -> str:
    level = quiz.LEVELS[age]
    if kind == "MATH":
        return f"계산 난이도: {level.label}"
    if kind == "PATTERN":
        return f"규칙 찾기 난이도: {quiz.PATTERN_RULE[age]}. 수는 0부터 {level.max_term}까지"
    if kind == "ENGLISH":
        top = quiz.ENGLISH_OF[level.english_max]
        if level.english_letters:
            return f"영어 난이도: 숫자 영단어 ONE~{top}, 또는 장치와 관계있는 2~8글자 쉬운 단어의 글자 수 세기"
        return f"영어 난이도: 숫자 영단어 ONE~{top}만(글자 수 세기는 쓰지 않음)"
    return f"상황 추리 난이도: {quiz.REASON_RULE[age]}"


def request_lines(dial: str, age: str, need: dict, have: list[str]) -> list[str]:
    """What the writer is asked for one device and age: the cells still short, (kind, value) -> how many."""
    lines = [f"{AGE_LABEL[age]} 아이를 위한 '{DIAL_LABEL[dial]}' 문제를 만들어 줘.", f"장치: {THEME[dial]}"]
    kinds = sorted({k for k, _ in need}, key=quiz.KINDS.index)
    if any(k != "REASON" for k in kinds):
        lines.append(f"계산, 영어, 규칙 찾기의 text에는 다음 말 중 하나를 꼭 넣는다: {', '.join(quiz.DEVICE_WORDS[dial])}")
    for value in sorted(v for k, v in need if k == "REASON"):
        lines.append(f"상황 추리(answer {value}, {quiz.REASON_ACTIONS[(dial, value)]})는 {quiz.REASON_WHEN[(dial, value)]}만 나온다. "
                     "그 상태와 맞게 쓴다")
    lines += [level_rule(k, age) for k in kinds]
    asks = []
    for (kind, value), n in sorted(need.items(), key=lambda x: (quiz.KINDS.index(x[0][0]), x[0][1])):
        if kind == "REASON":
            asks.append(f"상황 추리(kind REASON, answer {value}: {quiz.REASON_ACTIONS[(dial, value)]}) {n}개")
        else:
            asks.append(f"{KIND_LABEL[kind]}(kind {kind}) 답 {value}인 문제 {n}개")
    lines.append("만들 문제: " + ", ".join(asks))
    if have:
        lines.append("이미 있는 문제(겹치지 않게): " + " / ".join(have[-40:]))
    return lines


def ask(client, model: str, dial: str, age: str, need: dict, have: list[str]) -> list[dict]:
    response = client.beta.messages.create(
        model=model, max_tokens=16000, system=SYSTEM,
        messages=[{"role": "user", "content": "\n".join(request_lines(dial, age, need, have))}],
        output_config={"effort": "medium", "format": {"type": "json_schema", "schema": schema()}},
    )
    text = next((b.text for b in response.content if b.type == "text"), "{}")
    return json.loads(text).get("questions", [])


def _answer(client, system: str, content: str) -> str:
    r = client.messages.create(
        model=os.environ.get("SOLVER_MODEL", SOLVER_CLAUDE), max_tokens=SOLVER_TOKENS,
        system=system, messages=[{"role": "user", "content": content}],
    )
    return next((b.text for b in r.content if b.type == "text"), "")


def blind_number(client, text: str) -> int | None:
    """A second model's answer to the instruction from its words alone."""
    found = re.search(r"\d+", _answer(client, NUMBER_SOLVER, text))
    return int(found.group()) if found else None


# what a child would try instead, none of them an answer the car takes: a situation one of these solves is turned down
REASON_DISTRACTORS = ("바람 끄기", "창문 열기", "창문 닫기", "바람 방향 바꾸기", "와이퍼 켜기", "뒷유리 열선 켜기",
                      "독서등 하나만 끄기", "운전석 열선 단계만 낮추기", "의자 바람(통풍) 켜기")


def reason_choices(rnd: random.Random | None = None) -> list[str]:
    """Every situation function and what a child would try instead, in a shuffled order, so the second model cannot
    lean on where the right one is."""
    order = [*quiz.REASON_ACTIONS.values(), *REASON_DISTRACTORS]
    (rnd or random).shuffle(order)
    return order


BLIND_TRIES, BLIND_NEED = 5, 4


def blind_reason(client, e: bank.Entry, rnd: random.Random | None = None) -> bool:
    """A second model reads only the situation and picks, from every situation function in a new shuffled order each
    time, the one that solves it; it must pick the entry's own at least BLIND_NEED times in BLIND_TRIES (one try
    passes sentences that are right only by luck)."""
    rnd = rnd or random.Random()
    want = quiz.REASON_ACTIONS[(e.dial, e.answer)]
    right = 0
    for i in range(BLIND_TRIES):
        order = reason_choices(rnd)
        k = picked(_answer(client, REASON_SOLVER, solver_prompt(e.text, order)), len(order))
        right += k is not None and order[k] == want
        if right + (BLIND_TRIES - 1 - i) < BLIND_NEED:
            return False
    return right >= BLIND_NEED


def entry_from(dial: str, age: str, d: dict, id_: str) -> bank.Entry:
    kind = d["kind"]
    if kind == "REASON":
        return bank.Entry(id=id_, kind=kind, dial=dial, age=age, text=d["text"].strip(), hint=d["hint"].strip(),
                          answer=d["answer"])
    return bank.Entry(
        id=id_, kind=kind, dial=dial, age=age, text=d["text"].strip(), hint=d["hint"].strip(), answer=d["answer"],
        expr=d["expr"].strip() if kind == "MATH" else "", word=d["word"].strip().upper() if kind == "ENGLISH" else "",
        terms=tuple(d["terms"]) if kind == "PATTERN" else (),
    )


def targets(dial: str) -> dict:
    """(kind, value) -> how many per age."""
    lo, hi = quiz.DIALS[dial]
    out = {(k, v): n for k, n in PER_VALUE.get(dial, {}).items() for v in range(lo, hi + 1)}
    out |= {("REASON", v): n for (d, v), n in REASONS.items() if d == dial}
    return out


def cell(e: bank.Entry) -> tuple:
    return e.kind, e.answer


def fill(client, model, dial: str, age: str, entries: list[bank.Entry]) -> list[bank.Entry]:
    """New entries for one device and age, up to its targets."""
    code = f"{dial.lower()}-{age[0].lower()}"
    mine = [e for e in entries if e.dial == dial and e.age == age]
    added: list[bank.Entry] = []
    seen = {norm(e.text) for e in entries}
    serial = max([int(e.id.rsplit("-", 1)[1]) for e in mine] + [0])
    want = {c: n for c, n in targets(dial).items()
            if not (c[0] == "REASON" and age == "LITTLE" and dial in quiz.REASON_NOT_LITTLE)}
    for rnd in range(1, ROUNDS + 1):
        have = mine + added
        need = {c: n - sum(1 for e in have if cell(e) == c) for c, n in want.items()}
        need = {c: n for c, n in need.items() if n > 0}
        if not need:
            break
        try:
            written = ask(client, model, dial, age, need, [e.text for e in have])
        except Exception as ex:  # a failed call is tried again next round
            log.warning("%s round %d: writer failed: %r", code, rnd, ex)
            continue
        for d in written:
            serial += 1
            try:
                e = entry_from(dial, age, d, f"{code}-{serial:03d}")
            except (KeyError, TypeError, AttributeError) as ex:
                log.info("%s: broken item %r", code, ex)
                continue
            why = bank.problems(e)
            if norm(e.text) in seen:
                why.append("이미 있는 문제")
            if need.get(cell(e), 0) <= 0:
                why.append("이 칸은 이미 충분")
            if not why:
                if e.kind == "REASON":
                    if not blind_reason(client, e):
                        why.append("풀이 모델이 다른 기능을 고름")
                elif (got := blind_number(client, e.text)) != e.answer:
                    why.append(f"풀이 모델의 답 {got}")
            if why:
                log.info("%s rejected %s: %s", code, e.text, why)
                continue
            added.append(e)
            seen.add(norm(e.text))
            need[cell(e)] -= 1
        log.info("%s round %d: %d added so far", code, rnd, len(added))
    return added


def dedupe(entries: list[bank.Entry]) -> list[bank.Entry]:
    """The first of each question, by its words or, for an older age, by the same sum, list or word as a younger
    age's question on the same device (an older child should get a harder question, not the same one)."""
    kept, words, sums = [], set(), set()
    for e in sorted(entries, key=lambda e: AGES.index(e.age)):
        same = (e.dial, e.kind, e.expr, e.word, e.terms)
        number = e.kind != "REASON"
        if norm(e.text) in words or (number and same in sums):
            log.info("copy left out: %s %s", e.id, e.text)
            continue
        kept.append(e)
        words.add(norm(e.text))
        if number:
            sums.add(same)
    return kept


def markdown(entries: list[bank.Entry]) -> str:
    def esc(s: str) -> str:
        return s.replace("|", "/").replace("\n", " ")

    numbers = [e for e in entries if e.kind != "REASON"]
    number_dials = [d for d in bank.DIAL_ORDER if any(e.dial == d for e in numbers)]
    number_kinds = [k for k in quiz.KINDS if k != "REASON"]
    out = [f"# 차근차근 탐험단 문제은행 ({len(entries)}개)", "",
           "문제마다 답을 넣을 차의 장치를 다루는 문제입니다. Claude로 만들고, 서버가 다시 풀고 다른 모델이 문장만 보고 풀어 "
           "검산했습니다. 판마다 아이 나이, 장치, 정답에 맞는 문제를 여기서 무작위로 뽑습니다.", "",
           "| 장치 | " + " | ".join(KIND_LABEL[k] for k in number_kinds) + " | 합계 |",
           "|---|" + "---|" * len(number_kinds) + "---|"]
    for dial in number_dials:
        counts = [sum(1 for e in numbers if e.dial == dial and e.kind == k) for k in number_kinds]
        out.append(f"| {DIAL_LABEL[dial]} | " + " | ".join(map(str, counts)) + f" | {sum(counts)} |")
    n = 0
    for dial in number_dials:
        out += ["", f"## {DIAL_LABEL[dial]}"]
        for kind in number_kinds:
            for age in AGES:
                rows = [e for e in numbers if e.dial == dial and e.kind == kind and e.age == age]
                if not rows:
                    continue
                detail = {"MATH": "식", "ENGLISH": "단어", "PATTERN": "수"}[kind]
                out += ["", f"### {KIND_LABEL[kind]}, {AGE_LABEL[age]}", "", f"| 번호 | 답 | 문제 | {detail} | 힌트 |", "|---|---|---|---|---|"]
                for e in rows:
                    n += 1
                    d = {"MATH": e.expr, "ENGLISH": e.word, "PATTERN": ", ".join(map(str, e.terms))}[kind]
                    out.append(f"| {n} | {e.answer} | {esc(e.text)} | {esc(d)} | {esc(e.hint)} |")
    # the situations: when each is asked, then every one by function and age
    out += ["", f"## {REASON_SECTION}", "",
            "아이가 고른 친구가 '나'로 자기 상황을 말하면, 아이는 어떤 차 기능이 필요한지 알아내 말하고 안전 지킴이가 그 버튼을 누릅니다. 차가 정답 상태가 되면 정답입니다.", "",
            "- 문제는 '나오는 조건'의 차 상태일 때만 나옵니다. 차가 이미 정답인 상태면 그 문제는 나오지 않습니다(예: 독서등이 모두 꺼져 있으면 독서등 모두 끄기 문제는 나오지 않음).",
            "- 놀이를 시작할 때 안전 지킴이에게 공조 AUTO를 꺼 달라고 안내합니다. AUTO가 바람, 에어컨, 내기 순환을 저절로 바꾸지 않게 하기 위해서입니다.",
            "- 정답 기능의 이름은 문제와 힌트에 쓰지 않았습니다. 다른 기능의 이름은 그 기능이 답이 아니라고 알려 주는 조건에만 나옵니다.",
            "- 4~6세에는 내기 순환과 온도 내리기를 내지 않습니다. 4~6세 문제에는 흔한 오답을 막는 짧은 말을 하나까지 넣습니다.",
            "- 친구 이름, 몸 특징, 장소에 묶인 물건, 까닭(감기, 줄넘기 등)은 문장에 넣지 않았습니다. 어느 친구가 어느 장소에서 말해도 맞고, 까닭은 문제 앞 이야기가 정합니다. 앱은 계절과 낮밤을 모르므로 날씨, 계절, 밝기는 말하지 않습니다.",
            "- 독서등은 아이가 직접 누릅니다(벨트를 맨 채로). 손이 닿지 않는 앞자리 불은 안전 지킴이가 누릅니다.",
            "- 다른 모델이 상황만 보고, 13가지 조작과 아이가 할 만한 다른 조작(바람 끄기, 창문 열기, 바람 방향 바꾸기 등) 가운데 정답을 5번 중 4번 이상 골라야 통과했습니다.", "",
            "| 정답 조작 | 나오는 조건 | " + " | ".join(AGE_LABEL[a] for a in AGES) + " | 합계 |", "|---|---|---|---|---|---|"]
    reasons = [e for e in entries if e.kind == "REASON"]
    for side, label in quiz.REASON_ACTIONS.items():
        cells = ["-" if a == "LITTLE" and side[0] in quiz.REASON_NOT_LITTLE else
                 str(sum(1 for e in reasons if (e.dial, e.answer) == side and e.age == a)) for a in AGES]
        total = sum(1 for e in reasons if (e.dial, e.answer) == side)
        out.append(f"| {label} | {quiz.REASON_WHEN[side]} | " + " | ".join(cells) + f" | {total} |")
    for side, label in quiz.REASON_ACTIONS.items():
        rows = [e for a in AGES for e in reasons if (e.dial, e.answer) == side and e.age == a]
        if not rows:
            continue
        out += ["", f"### {label}", "", f"나오는 조건: {quiz.REASON_WHEN[side]}", "", "| 번호 | 나이 | 상황 | 힌트 |", "|---|---|---|---|"]
        for e in rows:
            n += 1
            out.append(f"| {n} | {AGE_LABEL[e.age]} | {esc(e.text)} | {esc(e.hint)} |")
    return "\n".join(out) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--md", type=Path, help="also write every question as a list to read")
    parser.add_argument("--fresh", action="store_true", help="start from an empty bank instead of filling the gaps")
    parser.add_argument("--drop", type=Path, help="a file of entry ids (one per line) a reviewer turned down, made again")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    for noisy in ("httpx", "httpx2", "anthropic", "httpcore"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
    client = anthropic.Anthropic(timeout=180.0, max_retries=2)
    model = os.environ.get("STORY_MODEL", DEFAULT_MODEL)
    entries = [] if args.fresh else bank.read()
    if args.drop:
        ids = {line.strip() for line in args.drop.read_text(encoding="utf-8").splitlines() if line.strip()}
        entries = [e for e in entries if e.id not in ids]
        log.info("dropped %d reviewed out", len(ids))
    # questions that no longer pass today's checks are dropped and their cells filled again
    for e in [e for e in entries if bank.problems(e)]:
        log.info("dropped %s: %s", e.id, bank.problems(e))
        entries.remove(e)
    jobs = [(d, a) for d in bank.DIAL_ORDER for a in AGES]
    # jobs run side by side and cannot see each other's questions, so copies are taken out after each pass
    for _ in range(2):
        entries = dedupe(entries)
        with ThreadPoolExecutor(max_workers=6) as pool:
            for added in pool.map(lambda j: fill(client, model, j[0], j[1], entries), jobs):
                entries += added
    entries = dedupe(entries)
    bank.write(entries)
    bank.load()  # the bank as the server will read it
    shutil.copyfile(bank.BANK_FILE, CAR_COPY)
    log.info("bank: %d questions, copied to %s", len(entries), CAR_COPY)
    if args.md:
        args.md.write_text(markdown(bank.read()), encoding="utf-8", newline="\n")
        log.info("list: %s", args.md)


if __name__ == "__main__":
    main()
