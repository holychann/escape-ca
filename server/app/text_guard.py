"""Normalized word and forbidden-pattern checks built on top of textgate.

Handles evasion tricks that raw substring or raw regex checks miss:
- Unicode compatibility forms (fullwidth ASCII/digits, compatibility Hangul via NFKC)
- Invisible/zero-width format characters inserted inside words
- Inline punctuation or symbols inserted inside forbidden words ("귀.신", "귀_신", "창.문.을 열")
- Case differences and extra symbols ("/", "_", "*") in required/forbidden word lists
"""
from collections.abc import Iterable, Sequence
import re
import unicodedata

from textgate import Forbid

_INVISIBLE = re.compile(
    r"[\u00ad\u034f\u061c\u115f\u1160\u17b4\u17b5\u180b-\u180f"
    r"\u200b-\u200f\u202a-\u202e\u2060-\u206f\u3164\ufeff\uffa0]"
)
_STRIP_ALL = re.compile(r"[\W_]+")
_INLINE_PUNCT = re.compile(r"[^\w\s]+|_+")
# 존댓말 endings ("~요", "~니다", "~죠"), excluding common -요 nouns ("담요", "동요", "요요"...)
_POLITE = re.compile(
    r"((?<![담동요주필중강])요|니다|죠)(?:[.!?,~…]|\s*$)"
    r"|(?:습|합|입)니다(?=\s)"
    r"|(?:세|해|어|아|예|이에|네|군|까|줘|워|봐)요(?=\s)"
)


def clean_text(text: str) -> str:
    """NFKC-normalized text with invisible format characters removed."""
    return _INVISIBLE.sub("", unicodedata.normalize("NFKC", text))


def polite(text: str) -> bool:
    """Whether ``text`` uses polite speech endings instead of 반말."""
    return bool(_POLITE.search(clean_text(text)))


def norm(text: str) -> str:
    """Compatibility-normalized, casefolded text with all whitespace, symbols and invisible chars removed."""
    cleaned = _INVISIBLE.sub("", unicodedata.normalize("NFKC", text)).casefold()
    return _STRIP_ALL.sub("", cleaned)


def missing(text: str, groups: Iterable[Sequence[str]]) -> list[list[str]]:
    """The groups none of whose words appear in ``text`` after normalization."""
    t = norm(text)
    return [list(g) for g in groups if not any(norm(w) in t for w in g)]


def present(text: str, words: Iterable[str]) -> list[str]:
    """The words from ``words`` that appear in ``text`` after normalization."""
    t = norm(text)
    return [w for w in words if norm(w) in t]


def find_forbidden(text: str, groups: Iterable[Forbid]) -> list[str]:
    """Matches forbidden regex groups against both NFKC-cleaned text and inline-punctuation-stripped text."""
    clean = _INVISIBLE.sub("", unicodedata.normalize("NFKC", text))
    depunct = _INLINE_PUNCT.sub("", clean)
    candidates = (clean,) if depunct == clean else (clean, depunct)
    found: list[str] = []
    for group in groups:
        for p in group.patterns:
            for candidate in candidates:
                m = p.search(candidate)
                if m:
                    found.append(f"{group.label}: {m.group(0)}" if group.quote_match else group.label)
                    break
    return found
