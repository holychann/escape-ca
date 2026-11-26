"""The story rules in story_rules.toml: what the writer is told and what the checker holds every draft to.

A key names one puzzle with its answer already chosen by the app's rule engine, e.g. ``ROOMS:LEFT`` is the
two-rooms puzzle with the penguin on the left. The story writer only dresses the answer up; the scene rules are how
the checker makes sure the words still point at the same answer.

The file is checked once when the server starts. An unknown field, a broken pattern or an empty word group stops
the server, so a typo in the file can never quietly make a check weaker.
"""
import tomllib
from pathlib import Path
from typing import Annotated

from pydantic import Field, PositiveInt
from textgate import Forbid, Group, Word
from textgate.rules import Strict as _Strict

RULES_FILE = Path(__file__).with_name("story_rules.toml")


class AgeLimits(_Strict):
    LITTLE: PositiveInt
    CAPTAIN: PositiveInt
    DETECTIVE: PositiveInt


class Limits(_Strict):
    bubble: PositiveInt
    hint: PositiveInt
    title: PositiveInt
    intro: PositiveInt
    treasure: PositiveInt
    speech: AgeLimits
    result: AgeLimits


class Prompt(_Strict):
    everywhere_never: list[Word]
    bubble_never: list[Word]


class Rule(_Strict):
    situation: Word
    # every group must appear in the speech
    must: Annotated[list[Group], Field(min_length=1)]
    # none of these may appear in the bubble, speech or hint
    never: list[Word] = []
    # the hint must point the same way as the speech
    hint_must: list[Group] = []
    # the friend has the child point the found button out to the 안전 지킴이 (the belt mission needs no car button)
    needs_guardian: bool = True
    # things the picture of this puzzle must not show, because they would tell one answer from another
    # (the dust puzzle's picture shows neither dust nor steam); the writer is told, and the checker holds art to it
    art_never: list[Word] = []


class StoryRules(_Strict):
    limits: Limits
    forbid: Annotated[list[Forbid], Field(min_length=1)]
    prompt: Prompt
    scene: Annotated[dict[str, Rule], Field(min_length=1)]


def load(path: Path = RULES_FILE) -> StoryRules:
    with open(path, "rb") as f:
        return StoryRules.model_validate(tomllib.load(f))


RULES = load()
LIMITS = RULES.limits
FORBID = RULES.forbid
EVERYWHERE_NEVER = RULES.prompt.everywhere_never
BUBBLE_NEVER = RULES.prompt.bubble_never


def rule_for(key: str) -> Rule:
    """Raises ValueError for a key the app could not have sent."""
    try:
        return RULES.scene[key]
    except KeyError:
        raise ValueError(f"unknown puzzle key: {key}") from None
