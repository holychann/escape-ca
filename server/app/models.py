"""What the car app sends, what the writer returns, and what goes back to the car."""
from typing import Literal

import re

from pydantic import BaseModel, ConfigDict, Field, field_validator

from . import quiz
from .rules import LIMITS, rule_for

Age =Literal["LITTLE", "CAPTAIN", "DETECTIVE"]
Theme = Literal["STAR_TRAIN", "SEA_SUB", "FOREST_SHIP"]

AGE_LABEL = {"LITTLE": "꼬마 탐험대(4~6세)", "CAPTAIN": "탐험대장(7~9세)", "DETECTIVE": "수석 탐정(10~12세)"}
THEME_LABEL = {"STAR_TRAIN": "별빛 기차", "SEA_SUB": "바닷속 잠수함", "FOREST_SHIP": "숲속 탐사선"}
# the lesson an episode carries, picked at random by the app (car StoryRepo.LESSONS): its word and how a 4~6 year old
# hears it
LESSON_LABEL = {
    "COURAGE": ("용기", "먼저 해 보는 마음"),
    "CARE": ("신중함", "천천히 잘 살펴보는 마음"),
    "CREATIVITY": ("창의", "새롭게 생각해 보는 마음"),
    "GRIT": ("끈기", "포기하지 않는 마음"),
    "TEAMWORK": ("협동", "함께 힘을 모으는 마음"),
    "SHARING": ("나눔", "나눠 주는 마음"),
    "HONESTY": ("정직", "솔직하게 말하는 마음"),
    "THANKS": ("감사", "고마워하는 마음"),
    "PROMISE": ("약속", "약속을 지키는 마음"),
    "FRIENDSHIP": ("우정", "친구를 아끼는 마음"),
}
Lesson = Literal["COURAGE", "CARE", "CREATIVITY", "GRIT", "TEAMWORK", "SHARING", "HONESTY", "THANKS", "PROMISE",
                 "FRIENDSHIP"]
# the friend book (car engine/Friends.kt): the friend the child picks tells the whole story as "나".
# name, animal, what it stands for, catchphrase (none yet for the three rare friends)
FRIEND_LABEL = {
    "BANTE": ("반테", "강아지", "용기", "먼저 해볼래!"),
    "KONYANG": ("코냥", "고양이", "관찰력", "자세히 보면 보여"),
    "RIA": ("리아", "토끼", "상상력", "이렇게 생각해 보면 어때?"),
    "TUBUGI": ("투부기", "거북이", "끈기", "천천히 해도 괜찮아"),
    "PAPPI": ("파삐", "뱁새", "협동", "같이 하면 할 수 있어"),
    "POTTO": ("포또", "판다", "배려", "나눠 먹자"),
    "IO": ("이오", "여우", "재치", ""),
    "SSOLRANG": ("쏠랑이", "호랑이", "따뜻한 리더십", ""),
    "CASPIN": ("캐스핀", "상어", "편견 없는 우정", ""),
}
FriendCode = Literal["BANTE", "KONYANG", "RIA", "TUBUGI", "PAPPI", "POTTO", "IO", "SSOLRANG", "CASPIN"]
MAX_VARIANTS = 16
WISH_MAX = 60


class Context(BaseModel):
    model_config = ConfigDict(extra="forbid")

    timeOfDay: Literal["MORNING", "AFTERNOON", "EVENING", "NIGHT"]
    season: Literal["SPRING", "SUMMER", "AUTUMN", "WINTER"]
    # only on cars that report it; null otherwise
    outsideTempC: int | None = Field(default=None, ge=-30, le=50)


class EpisodeRequest(BaseModel):
    """Enums, numbers and answer keys, plus at most one short sentence the family typed (``wish``).

    The wish is the only free text. It is checked before any model sees it (main.py), never written to the
    records (store.py), and the writer is told to turn names into '탐험대장' and anything scary into something cute.
    """

    model_config = ConfigDict(extra="forbid")

    # a random id the app makes once per install, used only for the daily limit
    installId: str = Field(pattern=r"^[0-9a-f-]{16,64}$")
    ages: list[Age] = Field(min_length=1, max_length=3)
    minutes: int = Field(ge=3, le=30)
    theme: Theme | None = None
    context: Context
    # one list per puzzle slot: the planned answer first, then the answers the app may switch to at puzzle start
    puzzles: list[list[str]] = Field(min_length=1, max_length=6)
    # the story the family asked for, e.g. "공룡 섬에서 보물찾기"; None lets the writer choose
    wish: str | None = Field(default=None, max_length=WISH_MAX)
    # the lesson the story carries; None leaves it out
    lesson: Lesson | None = None
    # the friend the child picked: it tells the story and asks every question as "나"
    friend: FriendCode
    # draw this episode's pictures too (only when the server has a painter)
    pictures: bool = False

    @field_validator("wish")
    @classmethod
    def tidy_wish(cls, wish: str | None) -> str | None:
        if wish is None:
            return None
        wish = re.sub(r"\s+", " ", wish).strip()
        return wish or None

    @field_validator("puzzles")
    @classmethod
    def known_keys(cls, slots: list[list[str]]) -> list[list[str]]:
        keys = [k for slot in slots for k in slot]
        if any(not slot for slot in slots):
            raise ValueError("empty puzzle slot")
        if len(keys) > MAX_VARIANTS:
            raise ValueError(f"at most {MAX_VARIANTS} answer variants")
        if len(set(keys)) != len(keys):
            raise ValueError("duplicate answer key")
        for k in keys:
            # a quiz key carries its own answer (app/quiz.py); the belt mission has a table in story_rules.toml
            if quiz.parse(k) is None:
                rule_for(k)
        return slots

    @property
    def keys(self) -> list[str]:
        return [k for slot in self.puzzles for k in slot]

    @property
    def youngest(self) -> Age:
        return min(self.ages, key=["LITTLE", "CAPTAIN", "DETECTIVE"].index)


class Question(BaseModel):
    """A checked quiz question as the car plays it. The friend reads ``text``; the app says what to do with the answer."""

    kind: str
    text: str
    expr: str = ""
    word: str = ""
    terms: list[int] = []


class SceneText(BaseModel):
    bubble: str
    speech: str
    hint: str
    # said the moment the answer is in: what changed in the car and in the story; empty when it failed the checks
    # (the car says its own praise then)
    result: str = ""
    # name of this scene's picture in Episode.images ("p1".."p6"); None plays the app's own picture
    picture: str | None = None
    # the quiz question of this scene; None for the belt mission
    question: Question | None = None


class DraftQuestion(BaseModel):
    """A question with the parts the checker works the answer out from (app/quiz.py). It comes from the question bank
    (app/bank.py); the writer does not write it."""

    kind: str
    text: str
    expr: str = ""
    word: str = ""
    terms: list[int] = []


class DraftScene(BaseModel):
    key: str
    bubble: str
    speech: str
    hint: str
    result: str
    # put in by the server from the question bank after the writer has written the story (service.put_questions)
    question: DraftQuestion | None = None


class Art(BaseModel):
    """What the painter is asked to draw. Never the answer: the answer is only in the friend's words."""

    cover: str
    puzzles: list[str]


class Draft(BaseModel):
    """Exactly the shape the model is told to fill (see DRAFT_SCHEMA)."""

    theme: Theme
    title: str
    intro: str
    # the last line, when the goal is reached and the child gets the badge
    treasureBadge: str
    scenes: list[DraftScene]
    # the writer's own one-line summary of the place, kind and season it was given; never shown to the child, only
    # told again when a failed scene is rewritten
    world: str = ""
    art: Art | None = None


def _text(description: str) -> dict:
    return {"type": "string", "description": description}


DRAFT_SCHEMA = {
    "type": "object",
    "properties": {
        "theme": {"type": "string", "enum": list(THEME_LABEL)},
        "world": _text("오늘의 세계를 한 줄로. 예: 공룡이 사는 화산섬"),
        "title": _text(f"오늘의 탐험 카드 제목. {LIMITS.title}자 이내"),
        "intro": _text("고른 친구의 첫인사와 오늘의 장소, 목표. 2~3문장"),
        "treasureBadge": _text("끝 인사: 목표를 이루고 탐험대장에게 탐험 배지를 줄 때의 말"),
        "scenes": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "key": _text("주어진 장면 key를 그대로"),
                    "bubble": _text("화면 말풍선 한 줄"),
                    "speech": _text("고른 친구가 문제 앞에 들려주는 이야기 1~2문장. 문제와 정답은 쓰지 않는다"),
                    "result": _text("정답을 넣은 순간의 말 1~2문장: 차에서 바뀐 것, 이야기 속 결과, 목표까지 남은 것"),
                    "hint": _text("벨트 미션의 힌트 한 문장. 퀴즈 장면은 빈 문자열(문제은행의 힌트를 쓴다)"),
                },
                "required": ["key", "bubble", "speech", "result", "hint"],
                "additionalProperties": False,
            },
        },
        "art": {
            "type": "object",
            "description": "그림 작가에게 줄 장면 설명. 정답 단서는 쓰지 않는다",
            "properties": {
                "cover": _text("오늘의 모험 표지 그림 설명 1~2문장"),
                "puzzles": {"type": "array", "items": _text("퍼즐 순서대로 한 장씩, 곤란한 친구와 장소 설명 1~2문장")},
            },
            "required": ["cover", "puzzles"],
            "additionalProperties": False,
        },
    },
    "required": ["theme", "world", "title", "intro", "treasureBadge", "scenes", "art"],
    "additionalProperties": False,
}


class Episode(BaseModel):
    """What the car stores and plays. A key missing from ``scenes`` is played with the app's own lines."""

    storyId: str
    model: str
    theme: Theme
    title: str
    intro: str
    treasureBadge: str
    scenes: dict[str, SceneText]
    dropped: list[str] = []
    world: str = ""
    # picture name -> path on this server ("cover", "p1".."p6"); empty when nothing was drawn
    images: dict[str, str] = {}
    # answer key -> its puzzle's picture name, also for a key whose words were dropped (it plays the app's own lines)
    pictures: dict[str, str] = {}


SPEAK_MAX = 600


class SpeakRequest(BaseModel):
    """One line exactly as the app will say it (story, question and the app's instruction joined)."""

    model_config = ConfigDict(extra="forbid")

    installId: str = Field(pattern=r"^[0-9a-f-]{16,64}$")
    text: str = Field(min_length=1, max_length=SPEAK_MAX)


class Report(BaseModel):
    model_config = ConfigDict(extra="forbid")

    storyId: str = Field(pattern=r"^[0-9a-f]{32}$")
    reason: Literal["SCARY", "WRONG_ANSWER", "HARD_WORDS", "OTHER"]
