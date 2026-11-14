"""The small part of the textgate checking library this server uses, built in.

textgate lives in a private repository, so a teammate without access could not install the server at all.
checker.py and rules.py import textgate when it is installed and fall back to this module otherwise; both give the
same verdicts for the server's checks (forbidden patterns, required word groups, length limits, strict rule files).
"""
import re
from dataclasses import dataclass, field
from typing import Annotated

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, StringConstraints

Word = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]
# one of these words must appear; an empty group could never be satisfied, so it is refused when the rules load
Group = Annotated[list[Word], Field(min_length=1)]


def _compiles(pattern: str) -> str:
    try:
        re.compile(pattern)
    except re.error as e:
        raise ValueError(f"broken pattern {pattern!r}: {e}") from None
    return pattern


Pattern = Annotated[str, StringConstraints(min_length=1), AfterValidator(_compiles)]


class Strict(BaseModel):
    """A rules-file table: an unknown field is a typo and stops loading instead of being ignored."""

    model_config = ConfigDict(extra="forbid")


class Forbid(Strict):
    label: Word
    patterns: Annotated[list[Pattern], Field(min_length=1)]
    # report the matched text ("무서운 말: 귀신") or only the label (for matches not worth quoting)
    quote_match: bool = True


def norm(text: str) -> str:
    """Spacing and punctuation never decide a match: '하나, 둘, 셋' is '하나둘셋', '안전 지킴이' is '안전지킴이'."""
    return re.sub(r"[\s\W_]+", "", text)


def find_forbidden(text: str, forbid: list[Forbid]) -> list[str]:
    found = []
    for f in forbid:
        for p in f.patterns:
            m = re.search(p, text)
            if m:
                found.append(f"{f.label}: {m.group(0)}" if f.quote_match else f.label)
    return found


def missing(text: str, groups: list[list[str]]) -> list[list[str]]:
    """The groups of which no word appears in the text."""
    t = norm(text)
    return [g for g in groups if not any(norm(w) in t for w in g)]


def present(text: str, words: list[str]) -> list[str]:
    t = norm(text)
    return [w for w in words if norm(w) and norm(w) in t]


def too_long(name: str, text: str, limit: int) -> list[str]:
    return [f"{name} 길이 {len(text)}자 > {limit}자"] if len(text) > limit else []


@dataclass
class Verdict:
    """Problems for the whole draft (``whole``) and per checked key (``items``)."""

    checked: list = field(default_factory=list)
    whole: list = field(default_factory=list)
    items: dict = field(default_factory=dict)

    @property
    def usable(self) -> bool:
        return not self.whole

    @property
    def clean(self) -> bool:
        return not self.whole and not self.items
