import pytest

from app.checker import bad_words, check, to_episode
from app.rules import rule_for
from app.models import EpisodeRequest
from app.prompt import user_message
from tests.fakes import GOOD, draft


def request(slots, ages=("CAPTAIN",), theme=None):
    return EpisodeRequest(
        installId="0123456789abcdef0123",
        ages=list(ages),
        minutes=10,
        theme=theme,
        context={"timeOfDay": "EVENING", "season": "WINTER", "outsideTempC": 3},
        puzzles=slots,
    )


def test_every_hand_written_scene_passes():
    keys = list(GOOD)
    req = request([keys[i:i + 2] for i in range(0, len(keys), 2)])
    v = check(draft(req.keys), req)
    assert v.clean, (v.episode, v.scenes)


def test_wrong_side_is_dropped_but_the_rest_stays():
    req = request([["ROOMS:LEFT", "ROOMS:RIGHT"], ["FOG"]])
    d = draft(req.keys)
    # the left-room scene says the penguin went right
    d.scenes[0].speech = GOOD["ROOMS:RIGHT"][1]
    v = check(d, req)
    assert v.usable
    assert set(v.scenes) == {"ROOMS:LEFT"}
    assert any("다른 정답 말" in p for p in v.scenes["ROOMS:LEFT"])


def test_missing_answer_word_and_missing_guardian():
    req = request([["DUST:OUTSIDE"]])
    d = draft(req.keys)
    d.scenes[0].speech = "먼지 구름이 몰려와! 어떻게 하지?"
    problems = check(d, req).scenes["DUST:OUTSIDE"]
    assert "정답 말 빠짐: 밖에서" in problems
    assert "안전 지킴이에게 부탁하지 않음" in problems


def test_scene_never_written_is_dropped():
    req = request([["FOG"], ["BELT"]])
    v = check(draft(["FOG"]), req)
    assert v.scenes == {"BELT": ["장면 없음"]}


@pytest.mark.parametrize("text", [
    "괴물이 나타났어!", "피가 났대", "차 문을 열어 봐", "창문을 열자", "벨트를 풀어 봐", "이제 출발하자",
    "시동을 걸어", "네 이름이 뭐야?", "Let us go", "기어를 바꿔", "코피가 났어",
    # reassuring still names the fear; the story never needs it
    "무서워하지 마",
])
def test_bad_words(text):
    assert bad_words(text)


@pytest.mark.parametrize("text", [
    "기차 문을 열려면 함께 눌러야 해", "김이 모락모락 피어올라", "운전석 불을 켜 줘", "죽순이 쑥쑥", "칼국수 냄새",
    "커피가 따뜻해", "소피가 웃었어", "피자를 먹자",
])
def test_ordinary_words_pass(text):
    assert bad_words(text) == []


def test_bad_episode_line_rejects_the_whole_draft():
    req = request([["FOG"]])
    v = check(draft(req.keys, intro="무서운 괴물이 기차를 막았어!"), req)
    assert not v.usable


def test_little_ones_get_shorter_speech():
    req = request([["FOG"]], ages=("LITTLE", "DETECTIVE"))
    d = draft(req.keys)
    d.scenes[0].speech = "앞 유리가 뿌예! " + "안전 지킴이에게 유리를 닦는 바람을 부탁하자. " * 4
    assert any("speech 길이" in p for p in check(d, req).scenes["FOG"])


def test_theme_must_match_the_parents_choice():
    req = request([["FOG"]], theme="SEA_SUB")
    assert "고른 테마와 다름" in check(draft(req.keys), req).episode


def test_unknown_or_duplicate_keys_are_refused():
    with pytest.raises(ValueError):
        request([["ROOMS:UP"]])
    with pytest.raises(ValueError):
        request([["FOG"], ["FOG"]])
    with pytest.raises(ValueError):
        rule_for("SEAT:TRUNK")


def test_prompt_carries_only_the_rules_not_free_text():
    req = request([["ROOMS:LEFT", "ROOMS:RIGHT"], ["TOGETHER:CHILD_ONCE_PARENT_TWICE:REACH"]])
    msg = user_message(req)
    assert "key=ROOMS:LEFT" in msg and "key=ROOMS:RIGHT" in msg
    assert "쓰지 말 말: \"오른쪽\"" in msg
    assert "\"한번\", \"두번\"" in msg
    assert "겨울 저녁, 바깥 기온 3도" in msg
    assert "0123456789abcdef0123" not in msg


def test_a_bad_praise_line_is_left_out_not_the_whole_draft():
    d = draft(["FOG"])
    d.success = ["대단해!", "기차가 출발해!", "최고야!"]
    v = check(d, request([["FOG"]]))
    assert v.usable
    assert to_episode(d, v, request([["FOG"]]), "0" * 32, "m").success == ["대단해!", "최고야!"]
    d.success = ["대단해!", "기차가 출발해!"]
    assert "칭찬 문장 부족" in check(d, request([["FOG"]])).episode
