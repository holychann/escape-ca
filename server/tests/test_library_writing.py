"""Writing a story for the shelf: no season or time of day told, a random theme, and no season words in the result."""
import random

from app.checker import timeless_problems, to_episode, check
from app.models import THEME_LABEL, EpisodeRequest
from app.prompt import user_message
from app.service import make_episode, timeless_request
from tests.fakes import BANK, MATH, REASON, FakeWriter, draft, filled, leaky

KEYS = [MATH, REASON, "BELT"]
BODY = {
    "installId": "0123456789abcdef0123",
    "ages": ["CAPTAIN"],
    "minutes": 10,
    "context": {"timeOfDay": "AFTERNOON", "season": "AUTUMN"},
    "puzzles": [[MATH], [REASON], ["BELT"]],
    "friend": "BANTE",
}


def test_a_timeless_message_does_not_tell_the_season_or_time():
    req = timeless_request(EpisodeRequest(**BODY), random.Random(0))
    msg = user_message(req, timeless=True)
    assert "- 지금:" not in msg and "가을" not in msg and req.theme in THEME_LABEL
    assert f"theme에 {req.theme}" in msg


def test_a_wished_or_ordinary_message_still_tells_the_season():
    assert "- 지금: 가을 낮" in user_message(EpisodeRequest(**BODY))


def test_timeless_request_keeps_a_theme_already_chosen():
    req = EpisodeRequest(**BODY, theme="SEA_SUB")
    assert timeless_request(req).theme == "SEA_SUB"


def test_timeless_request_picks_among_the_three_themes():
    seen = {timeless_request(EpisodeRequest(**BODY), random.Random(i)).theme for i in range(40)}
    assert seen == set(THEME_LABEL)


def test_season_and_time_words_are_found_anywhere_in_the_episode():
    req = EpisodeRequest(**BODY, theme="STAR_TRAIN")
    clean = filled(KEYS)
    v = check(clean, req)
    assert timeless_problems(to_episode(clean, v, req, "0" * 32, "m")) == []
    d = filled(KEYS, intro="안녕, 탐험대장! 나는 반테야. 낙엽이 바스락거리는 숲이야")
    assert timeless_problems(to_episode(d, v, req, "0" * 32, "m")) == ["계절·시간대 말: 낙엽"]


def test_a_scene_hint_or_result_with_a_season_word_is_found():
    req = EpisodeRequest(**BODY, theme="STAR_TRAIN")
    d = filled(KEYS)
    v = check(d, req)
    ep = to_episode(d, v, req, "0" * 32, "m")
    ep.scenes["BELT"].result = "벚꽃이 피었어!"
    assert timeless_problems(ep) == ["계절·시간대 말: 벚꽃"]


def test_make_episode_passes_timeless_to_the_writer_and_the_repair():
    req = EpisodeRequest(**BODY, theme="STAR_TRAIN")
    w = FakeWriter(leaky(), leaky(), draft(KEYS))
    make_episode(req, w, BANK, timeless=True)
    assert all("- 지금:" not in m for m in w.messages) and w.calls == 3
