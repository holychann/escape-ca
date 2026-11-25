"""Hand-written drafts that pass every rule, a small question bank for them, and a writer that hands them out."""
from app import bank
from app.bank import Bank, Entry
from app.models import Draft, DraftQuestion, DraftScene, NewFriend
from app.service import put_questions

# one key of every kind; together with BELT they fill the six puzzle slots of a request
MATH = "Q:MATH:FAN:5"
ENGLISH = "Q:ENGLISH:TEMP_UP:2"
PATTERN = "Q:PATTERN:LIGHTS:4"
# two situations: the front window defroster, and the air conditioner switched on
REASON = "Q:REASON:DEFROST:1"
AC = "Q:REASON:AC:1"
# a quiz key the bank below has no question for
UNCOVERED = "Q:MATH:FAN:7"


def question(kind: str, text: str, expr: str = "", word: str = "", terms=()) -> DraftQuestion:
    return DraftQuestion(kind=kind, text=text, expr=expr, word=word, terms=list(terms))


# every number question is an instruction to the device its key sets, with the amount as a little puzzle
# (bank.problems); made for the youngest, so they also serve the older ages (Bank.candidates falls back to a younger
# age)
SUM = Entry(id="fan-l-001", kind="MATH", dial="FAN", age="LITTLE", answer=5, expr="3+2",
            text="바람 세기를 3 더하기 2단으로 맞춰 줘!", hint="손가락을 하나씩 펴면서 세어 봐.")
WORD = Entry(id="temp_up-l-001", kind="ENGLISH", dial="TEMP_UP", age="LITTLE", answer=2, word="TWO",
             text="TWO만큼 온도를 올려 줘!", hint="영어로 수를 하나부터 차례로 세어 봐.")
RUN = Entry(id="lights-l-001", kind="PATTERN", dial="LIGHTS", age="LITTLE", answer=4, terms=(1, 2, 3),
            text="켜진 독서등이 모두 1개, 2개, 3개 다음 수가 되게 해 줘!", hint="수가 얼마씩 커지는지 봐.")
# situations from the shipped bank (app/question_bank.json): each names the friend it is about, which need not be the
# device's own friend (FOG is about 펭귄 선장, the defroster's friend is 투덜이 기계)
FOG = Entry(id="defrost-l-r01", kind="REASON", dial="DEFROST", age="LITTLE", answer=1,
            text="펭귄 선장의 호호 입김에 앞 유리 안쪽이 뿌예지려고 해, 와이퍼는 바깥만 닦아서 안 돼. 차 버튼으로 펭귄 선장을 도와줘!",
            hint="꼬불꼬불 화살표 버튼이 두 개면 둥그스름한 앞 유리 모양 쪽이야.")
# an air conditioner situation is written for turning it on (1) or off (0) and only serves that answer
COOL = Entry(id="ac-on-l-r03", kind="REASON", dial="AC", age="LITTLE", answer=1,
             text="새싹 로봇이 줄넘기를 해서 땀이 뻘뻘, 바람은 나오는데 안 시원하대. 창문은 닫아 둔 채로 차 버튼으로 새싹 로봇을 도와줘!",
             hint="더울 때 켜서 나오는 바람을 차갑게 만드는 버튼을 찾아봐.")
COLD = Entry(id="ac-off-l-r02", kind="REASON", dial="AC", age="LITTLE", answer=0,
             text="감기 걸린 수달 요리사가 더울 때 켜는 시원한 바람이 켜져 있어서 덜덜 떨어. 온도 숫자는 그대로 두고 차 버튼으로 도와줘!",
             hint="더울 때 누르는 버튼이 지금도 켜져 있는지 살펴봐.")
# the same answer as SUM with a times sign, for the oldest only
TIMES = Entry(id="fan-d-001", kind="MATH", dial="FAN", age="DETECTIVE", answer=5, expr="4*3-7",
              text="바람 세기를 4 곱하기 3 빼기 7단으로 맞춰 줘!", hint="먼저 곱하고 나서 빼 봐.")
ENTRIES = [SUM, WORD, RUN, TIMES, FOG, COOL, COLD]
BANK = Bank(ENTRIES)


def without_situations() -> Bank:
    return Bank([e for e in ENTRIES if e.kind != "REASON"])


# key -> (bubble, speech, hint, question): the lines the writer writes, and for a quiz the hint and question the bank
# gives it; every quiz passes for the LITTLE and CAPTAIN ages
GOOD = {
    MATH: (
        "새싹이 목말라!",
        "새싹 로봇의 새싹이 목말라서 축 늘어졌어. 바람이 몇 단인지 알아내면 새싹에게 바람을 보내 줄 수 있어!",
        SUM.hint,
        bank.question(SUM),
    ),
    ENGLISH: (
        "펭귄 방이 추워!",
        "부르르, 펭귄 선장의 방이 너무 추워졌어. 영어 낱말을 맞히면 방을 조금 따뜻하게 할 수 있어!",
        WORD.hint,
        bank.question(WORD),
    ),
    PATTERN: (
        "기차 칸이 깜깜해!",
        "토끼 기관사가 고치는 기차 칸이 깜깜해. 불이 켜지는 규칙을 찾으면 작은 불을 밝힐 수 있어!",
        RUN.hint,
        bank.question(RUN),
    ),
    REASON: (
        "앞이 뿌예!",
        "펭귄 선장이 기차 창가에 서서 호호 입김을 불고 있어. 이 문제를 풀어 볼까?",
        FOG.hint,
        bank.question(FOG),
    ),
    AC: (
        "헥헥, 더워!",
        "새싹 로봇이 줄넘기를 하고 와서 땀을 뻘뻘 흘려. 이 문제를 풀어 볼까?",
        COOL.hint,
        bank.question(COOL),
    ),
    "BELT": ("벨트를 딸깍!", "탐험대장, 벨트를 딸깍! 이미 맸다면 버클을 짚고 딸깍 확인!", "끝까지 넣으면 딸깍 소리가 나.", None),
}


FRIEND = NewFriend(name="별똥 차장", look="노란 별 모양 몸에 작은 차장 모자를 쓴 꼬마", trait="기차 노래를 좋아하는 수줍은 친구야.", hello="반가워! 나도 이제 도감 친구야!")


def draft(keys, **override) -> Draft:
    """A draft as the writer returns it: the story only, with a quiz scene's hint left empty for the bank's."""
    scenes = [DraftScene(key=k, bubble=GOOD[k][0], speech=GOOD[k][1], hint="" if GOOD[k][3] else GOOD[k][2])
              for k in keys]
    fields = dict(
        theme="STAR_TRAIN",
        title="별빛 기차를 구해라",
        intro="안녕, 탐험대장! 나는 루미야. 별빛 기차가 멈췄대. 같이 도와주자!",
        success=["정답! 별 하나!", "와, 해냈어!", "역시 탐험대장!"],
        treasureGift="보물 상자가 열렸어! 안전 지킴이가 글로브박스에서 선물을 꺼내 줄 거야.",
        treasureBadge="모두 도왔어! 탐험 배지를 받았어!",
        scenes=scenes,
        friend=FRIEND,
    )
    fields.update(override)
    return Draft(**fields)


def filled(keys, **override) -> Draft:
    """The writer's draft once every quiz scene has its question and hint, as the server checks it."""
    d = draft(keys, **override)
    put_questions(d, {k: (GOOD[k][3].model_copy(deep=True), GOOD[k][2]) for k in keys if GOOD[k][3] is not None})
    return d


class FakeWriter:
    """Hands out the given drafts (or raises the given errors) in order, and keeps every message it was sent."""

    def __init__(self, *drafts):
        self.drafts = list(drafts)
        self.calls = 0
        self.messages: list[str] = []

    def write(self, system, user):
        self.calls += 1
        self.messages.append(user)
        d = self.drafts.pop(0)
        if isinstance(d, Exception):
            raise d
        return d, "fake-model"
