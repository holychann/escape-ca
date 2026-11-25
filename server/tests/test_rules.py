import copy
import tomllib

import pytest
from pydantic import ValidationError

from app.rules import RULES_FILE, StoryRules


def _raw():
    with open(RULES_FILE, "rb") as f:
        return tomllib.load(f)


def _typo(d):
    d["scene"]["BELT"]["hint_mst"] = [["딸깍"]]


def _bad_pattern(d):
    d["forbid"][0]["patterns"].append("피(가")


def _empty_group(d):
    d["scene"]["BELT"]["must"].append([])


def _missing_age(d):
    del d["limits"]["speech"]["LITTLE"]


@pytest.mark.parametrize("break_it", [_typo, _bad_pattern, _empty_group, _missing_age])
def test_a_mistake_in_the_rules_file_stops_loading(break_it):
    d = copy.deepcopy(_raw())
    StoryRules.model_validate(d)
    break_it(d)
    with pytest.raises(ValidationError):
        StoryRules.model_validate(d)
