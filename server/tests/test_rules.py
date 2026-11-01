import copy
import itertools
import tomllib

import pytest
from pydantic import ValidationError

from app.rules import RULES, RULES_FILE, StoryRules

# the puzzles whose answer the app may switch at puzzle start (car StoryKeys.variants)
SWITCHABLE = ("WIND", "DUST", "ROOMS", "SEAT")


def _pairs():
    keys = sorted(RULES.scene)
    for kind in SWITCHABLE:
        same = [k for k in keys if k.split(":")[0] == kind]
        yield from itertools.permutations(same, 2)


@pytest.mark.parametrize("key,other", list(_pairs()))
def test_switchable_answers_forbid_each_others_words(key, other):
    a, b = RULES.scene[key], RULES.scene[other]
    for group in a.must:
        if group in b.must:
            continue
        assert any(w in b.never for w in group), f"{other} must forbid one of {group} ({key}'s answer)"


def _raw():
    with open(RULES_FILE, "rb") as f:
        return tomllib.load(f)


def _typo(d):
    d["scene"]["FOG"]["hint_mst"] = [["유리"]]


def _bad_pattern(d):
    d["forbid"][0]["patterns"].append("피(가")


def _empty_group(d):
    d["scene"]["ROOMS:LEFT"]["must"].append([])


def _missing_age(d):
    del d["limits"]["speech"]["LITTLE"]


@pytest.mark.parametrize("break_it", [_typo, _bad_pattern, _empty_group, _missing_age])
def test_a_mistake_in_the_rules_file_stops_loading(break_it):
    d = copy.deepcopy(_raw())
    StoryRules.model_validate(d)
    break_it(d)
    with pytest.raises(ValidationError):
        StoryRules.model_validate(d)
