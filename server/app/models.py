"""What the car app sends, what the writer returns, and what goes back to the car."""
from typing import Literal

import re

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .rules import rule_for

Age = Literal["LITTLE", "CAPTAIN", "DETECTIVE"]
Theme = Literal["STAR_TRAIN", "SEA_SUB", "FOREST_SHIP"]

AGE_LABEL = {"LITTLE": "꼬마 탐험대(4~6세)", "CAPTAIN": "탐험대장(7~9세)", "DETECTIVE": "수석 탐정(10~12세)"}
THEME_LABEL = {"STAR_TRAIN": "별빛 기차", "SEA_SUB": "바닷속 잠수함", "FOREST_SHIP": "숲속 탐사선"}
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
            rule_for(k)
        return slots

    @property
    def keys(self) -> list[str]:
        return [k for slot in self.puzzles for k in slot]

    @property
    def youngest(self) -> Age:
        return min(self.ages, key=["LITTLE", "CAPTAIN", "DETECTIVE"].index)


class SceneText(BaseModel):
    bubble: str
    speech: str
    hint: str
    # name of this scene's picture in Episode.images ("p1".."p6"); None plays the app's own picture
    picture: str | None = None


class DraftScene(SceneText):
    key: str


class Art(BaseModel):
    """What the painter is asked to draw. Never the answer: the answer is only in Lumi's words."""

    cover: str
    puzzles: list[str]
    ending: str


class Draft(BaseModel):
    """Exactly the shape the model is told to fill (see DRAFT_SCHEMA)."""

    theme: Theme
    title: str
    intro: str
    success: list[str]
    treasureGift: str
    treasureBadge: str
    scenes: list[DraftScene]
    # the day's world in one line ("공룡이 사는 화산섬"); empty in drafts written before wishes existed
    world: str = ""
    art: Art | None = None


def _text(description: str) -> dict:
    return {"type": "string", "description": description}


DRAFT_SCHEMA = {
    "type": "object",
    "properties": {
        "theme": {"type": "string", "enum": list(THEME_LABEL)},
        "world": _text("오늘의 세계를 한 줄로. 예: 공룡이 사는 화산섬"),
        "title": _text("오늘의 탐험 카드 제목. 15자 이내"),
        "intro": _text("루미의 첫인사와 오늘의 사건. 2~3문장"),
        "success": {"type": "array", "items": _text("퍼즐을 풀었을 때 루미의 칭찬 한 문장"), "description": "서로 다른 칭찬 3개"},
        "treasureGift": _text("모든 퍼즐을 마치고 안전 지킴이가 글로브박스에서 선물을 꺼내 줄 때의 말"),
        "treasureBadge": _text("선물 없이 탐험 배지를 받을 때의 말"),
        "scenes": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "key": _text("주어진 장면 key를 그대로"),
                    "bubble": _text("화면 말풍선 한 줄"),
                    "speech": _text("루미가 소리 내어 읽는 말"),
                    "hint": _text("막혔을 때 들려줄 힌트 한 문장"),
                },
                "required": ["key", "bubble", "speech", "hint"],
                "additionalProperties": False,
            },
        },
        "art": {
            "type": "object",
            "description": "그림 작가에게 줄 장면 설명. 정답 단서는 쓰지 않는다",
            "properties": {
                "cover": _text("오늘의 모험 표지 그림 설명 1~2문장"),
                "puzzles": {"type": "array", "items": _text("퍼즐 순서대로 한 장씩, 곤란한 친구와 장소 설명 1~2문장")},
                "ending": _text("목표를 이룬 마지막 장면 그림 설명 1~2문장"),
            },
            "required": ["cover", "puzzles", "ending"],
            "additionalProperties": False,
        },
    },
    "required": ["theme", "world", "title", "intro", "success", "treasureGift", "treasureBadge", "scenes", "art"],
    "additionalProperties": False,
}


class Episode(BaseModel):
    """What the car stores and plays. A key missing from ``scenes`` is played with the app's own lines."""

    storyId: str
    model: str
    theme: Theme
    title: str
    intro: str
    success: list[str]
    treasureGift: str
    treasureBadge: str
    scenes: dict[str, SceneText]
    dropped: list[str] = []
    world: str = ""
    # picture name -> path on this server ("cover", "p1".."p6", "ending"); empty when nothing was drawn
    images: dict[str, str] = {}
    # answer key -> its puzzle's picture name, also for a key whose words were dropped (it plays the app's own lines)
    pictures: dict[str, str] = {}


class Report(BaseModel):
    model_config = ConfigDict(extra="forbid")

    storyId: str = Field(pattern=r"^[0-9a-f]{32}$")
    reason: Literal["SCARY", "WRONG_ANSWER", "HARD_WORDS", "OTHER"]
