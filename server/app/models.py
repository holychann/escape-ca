"""What the car app sends, what the writer returns, and what goes back to the car."""
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from .keys import rule_for

Age = Literal["LITTLE", "CAPTAIN", "DETECTIVE"]
Theme = Literal["STAR_TRAIN", "SEA_SUB", "FOREST_SHIP"]

AGE_LABEL = {"LITTLE": "꼬마 탐험대(4~6세)", "CAPTAIN": "탐험대장(7~9세)", "DETECTIVE": "수석 탐정(10~12세)"}
THEME_LABEL = {"STAR_TRAIN": "별빛 기차", "SEA_SUB": "바닷속 잠수함", "FOREST_SHIP": "숲속 탐사선"}
MAX_VARIANTS = 16


class Context(BaseModel):
    model_config = ConfigDict(extra="forbid")

    timeOfDay: Literal["MORNING", "AFTERNOON", "EVENING", "NIGHT"]
    season: Literal["SPRING", "SUMMER", "AUTUMN", "WINTER"]
    # only on cars that report it; null otherwise
    outsideTempC: int | None = Field(default=None, ge=-30, le=50)


class EpisodeRequest(BaseModel):
    """No name, voice, place or free text: only enums, numbers and answer keys."""

    model_config = ConfigDict(extra="forbid")

    # a random id the app makes once per install, used only for the daily limit
    installId: str = Field(pattern=r"^[0-9a-f-]{16,64}$")
    ages: list[Age] = Field(min_length=1, max_length=3)
    minutes: int = Field(ge=3, le=30)
    theme: Theme | None = None
    context: Context
    # one list per puzzle slot: the planned answer first, then the answers the app may switch to at puzzle start
    puzzles: list[list[str]] = Field(min_length=1, max_length=6)

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


class DraftScene(SceneText):
    key: str


class Draft(BaseModel):
    """Exactly the shape the model is told to fill (see DRAFT_SCHEMA)."""

    theme: Theme
    title: str
    intro: str
    success: list[str]
    treasureGift: str
    treasureBadge: str
    scenes: list[DraftScene]


def _text(description: str) -> dict:
    return {"type": "string", "description": description}


DRAFT_SCHEMA = {
    "type": "object",
    "properties": {
        "theme": {"type": "string", "enum": list(THEME_LABEL)},
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
    },
    "required": ["theme", "title", "intro", "success", "treasureGift", "treasureBadge", "scenes"],
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


class Report(BaseModel):
    model_config = ConfigDict(extra="forbid")

    storyId: str = Field(pattern=r"^[0-9a-f]{32}$")
    reason: Literal["SCARY", "WRONG_ANSWER", "HARD_WORDS", "OTHER"]
