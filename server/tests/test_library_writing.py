"""Writing a story for the shelf: the season told but never the time of day or the weather, a random theme, and no
time-of-day words in the result."""
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


def test_a_timeless_message_tells_the_season_but_not_the_time_or_weather():
    req = timeless_request(EpisodeRequest(**{**BODY, "context": {**BODY["context"], "outsideTempC": 3}}),
                           random.Random(0))
    msg = user_message(req, timeless=True)
    assert "- 지금 계절: 가을 (" in msg and "기온" not in msg and req.theme in THEME_LABEL
    assert f"theme에 {req.theme}" in msg


def test_no_message_tells_the_time_of_day_and_every_one_forbids_its_words():
    for timeless in (False, True):
        msg = user_message(EpisodeRequest(**BODY), timeless=timeless)
        assert "- 지금 계절: 가을" in msg and "가을 낮" not in msg
        assert "하루 중 때를 나타내는 말(아침, 저녁, 밤, 새벽, 노을)은 쓰지 않는다" in msg


def test_timeless_request_keeps_a_theme_already_chosen():
    req = EpisodeRequest(**BODY, theme="SEA_SUB")
    assert timeless_request(req).theme == "SEA_SUB"


def test_timeless_request_picks_among_the_three_themes():
    seen = {timeless_request(EpisodeRequest(**BODY), random.Random(i)).theme for i in range(40)}
    assert seen == set(THEME_LABEL)


def test_time_words_are_found_anywhere_in_the_episode_and_season_words_are_not():
    req = EpisodeRequest(**BODY, theme="STAR_TRAIN")
    clean = filled(KEYS)
    v = check(clean, req)
    assert timeless_problems(to_episode(clean, v, req, "0" * 32, "m")) == []
    d = filled(KEYS, intro="안녕, 탐험대장! 나는 반테야. 낙엽이 바스락거리는 숲이야")
    assert timeless_problems(to_episode(d, v, req, "0" * 32, "m")) == []
    d = filled(KEYS, intro="안녕, 탐험대장! 나는 반테야. 노을이 지는 숲이야")
    assert timeless_problems(to_episode(d, v, req, "0" * 32, "m")) == ["시간대 말: 노을"]


def test_a_scene_hint_or_result_with_a_time_word_is_found():
    req = EpisodeRequest(**BODY, theme="STAR_TRAIN")
    d = filled(KEYS)
    v = check(d, req)
    ep = to_episode(d, v, req, "0" * 32, "m")
    ep.scenes["BELT"].result = "새벽 별이 반짝여!"
    assert timeless_problems(ep) == ["시간대 말: 새벽"]


def test_make_episode_passes_timeless_to_the_writer_and_the_repair():
    req = EpisodeRequest(**BODY, theme="STAR_TRAIN")
    w = FakeWriter(leaky(), leaky(), draft(KEYS))
    make_episode(req, w, BANK, timeless=True)
    assert all("기온" not in m and "가을 낮" not in m for m in w.messages) and w.calls == 3
