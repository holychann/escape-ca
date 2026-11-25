import random

import pytest

from app import bank
from app.checker import art_problems, bad_words, check, friend_problems, to_episode
from app.rules import rule_for
from app.models import Art, EpisodeRequest
from app.prompt import SYSTEM, user_message
from app.service import pick_questions
from tests.fakes import (AC, BANK, COLD, COOL, ENGLISH, FOG, FRIEND, GOOD, MATH, PATTERN, REASON, SUM, UNCOVERED, filled,
                         question)


def request(slots, ages=("CAPTAIN",), theme=None):
    return EpisodeRequest(
        installId="0123456789abcdef0123",
        ages=list(ages),
        minutes=10,
        theme=theme,
        context={"timeOfDay": "EVENING", "season": "WINTER", "outsideTempC": 3},
        puzzles=slots,
    )


@pytest.mark.parametrize("age", ["LITTLE", "CAPTAIN"])
def test_every_hand_written_scene_passes(age):
    req = request([[k] for k in GOOD], ages=(age,))
    v = check(filled(req.keys), req)
    assert v.clean, (v.whole, v.items)


def test_wrong_answer_is_dropped_but_the_rest_stays():
    req = request([[MATH], [PATTERN]])
    d = filled(req.keys)
    # 3+3 is 6, but the app asked for 5
    d.scenes[0].question = question("MATH", "사과가 세 개 있었는데 세 개를 더 땄어. 사과는 모두 몇 개일까?", expr="3+3")
    v = check(d, req)
    assert v.usable
    assert set(v.items) == {MATH}
    assert "답이 다름: 6 (정답 5)" in v.items[MATH]


def test_answer_given_away_in_the_speech():
    req = request([[MATH]])
    d = filled(req.keys)
    d.scenes[0].speech = "새싹 로봇이 사과 다섯 개만 있으면 된대!"
    assert "이야기에 정답 수" in check(d, req).items[MATH]


def test_belt_mission_still_needs_its_word():
    req = request([["BELT"]])
    d = filled(req.keys)
    d.scenes[0].speech = "탐험대장, 벨트를 잘 맸는지 확인해 볼까?"
    assert "정답 말 빠짐: 딸깍" in check(d, req).items["BELT"]


def test_bubble_naming_the_button_is_dropped():
    req = request([[MATH]])
    d = filled(req.keys)
    d.scenes[0].bubble = "풍량을 맞춰 줘!"
    assert "말풍선에 버튼 말: 풍량" in check(d, req).items[MATH]


@pytest.mark.parametrize("line,word", [
    ("새싹 로봇이 더워해. 에어컨을 찾아볼까?", "에어컨"),
    ("새싹 로봇이 땀을 뻘뻘 흘려. 온도를 바꾸면 될까?", "온도"),
])
def test_a_situation_story_naming_a_button_is_dropped(line, word):
    req = request([[AC]])
    d = filled(req.keys)
    d.scenes[0].speech = line
    assert f"이야기에 버튼 말: {word}" in check(d, req).items[AC]


def test_gift_line_must_name_the_guardian():
    req = request([[MATH]])
    d = filled(req.keys, treasureGift="탐험 지킴이님, 글로브박스의 보물을 전해 주세요!")
    assert "선물 문장에 안전 지킴이 빠짐" in check(d, req).whole


def test_scene_never_written_is_dropped():
    req = request([[MATH], ["BELT"]])
    v = check(filled([MATH]), req)
    assert v.items == {"BELT": ["장면 없음"]}


def test_quiz_scene_without_a_question_is_dropped():
    req = request([[MATH]])
    d = filled(req.keys)
    d.scenes[0].question = None
    assert check(d, req).items == {MATH: ["문제 없음"]}


@pytest.mark.parametrize("key,base,field,line,word", [
    (AC, AC, "speech", "투덜이 기계가 너무 추워서 오들오들 떨어.", "추워"),
    (AC, AC, "bubble", "에취, 꺼 줘!", "꺼 줘"),
    ("Q:REASON:AC:0", AC, "bubble", "더워서 땀이 나!", "더워"),
    ("Q:REASON:FAN_DOWN:1", REASON, "speech", "펭귄 선장이 바람을 더 세게 해 달래.", "더 세게"),
    ("Q:ENGLISH:TEMP_DOWN:2", ENGLISH, "speech", "펭귄 선장의 방을 따뜻하게 데워 주자!", "따뜻하게"),
    (ENGLISH, ENGLISH, "speech", "방을 시원하게 하려면 어떻게 할까?", "시원하게"),
])
def test_a_line_that_says_the_other_way_is_dropped(key, base, field, line, word):
    d = filled([base])
    d.scenes[0] = d.scenes[0].model_copy(update={"key": key, field: line})
    assert f"반대로 들리는 말: {word}" in check(d, request([[key]])).items[key]


def test_the_banks_hint_is_not_held_to_one_way():
    # a situation's hint may say when the button is used ("더울 때"), even when the child turns it off
    off = "Q:REASON:AC:0"
    d = filled([AC])
    d.scenes[0] = d.scenes[0].model_copy(update={
        "key": off, "bubble": "오들오들 떨려!", "speech": "수달 요리사가 오들오들 떨고 있어. 이 문제를 풀어 볼까?",
        "question": bank.question(COLD), "hint": "더워서 땀 날 때 켜는 버튼이 지금도 켜져 있는지 살펴봐.",
    })
    assert off not in check(d, request([[off]])).items


def test_a_situation_picture_shows_the_trouble_but_no_button():
    req = request([[MATH], [REASON], ["BELT"]])
    d = filled(req.keys)
    d.art = Art(cover="별빛 기차 앞의 루미", puzzles=["새싹 로봇", "펭귄 선장이 뿌연 앞 유리 앞에서 입김을 분다", "탐험대장 자리"],
                ending="모두 함께 웃는다")
    # a situation has no number to give away, so its trouble may be drawn
    assert art_problems(d, req) == {}
    d.art = d.art.model_copy(update={"puzzles": ["새싹 로봇", "펭귄 선장이 성에 낀 앞 유리를 본다", "탐험대장 자리"]})
    assert art_problems(d, req) == {"p2": ["버튼 말: 성에"]}
    friend = FRIEND.model_copy(update={"look": "에어컨 모양 모자를 쓴 꼬마"})
    assert friend_problems(friend, req) == ["버튼 말: 에어컨"]


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
    req = request([[MATH]])
    v = check(filled(req.keys, intro="무서운 괴물이 기차를 막았어!"), req)
    assert not v.usable


def test_little_ones_get_shorter_speech():
    d = filled([MATH])
    d.scenes[0].speech = GOOD[MATH][1] + " 바람이 몇 단인지 하나하나 천천히 세어 보자. 다 세면 새싹이 기운을 차리고 활짝 웃으며 춤을 출 거야!"
    assert 110 < len(d.scenes[0].speech) <= 170
    little = request([[MATH]], ages=("LITTLE", "DETECTIVE"))
    assert any("speech 길이" in p for p in check(d, little).items[MATH])
    assert check(d, request([[MATH]], ages=("DETECTIVE",))).clean


def test_theme_must_match_the_parents_choice():
    req = request([[MATH]], theme="SEA_SUB")
    assert "고른 테마와 다름" in check(filled(req.keys), req).whole


@pytest.mark.parametrize("slots", [
    [["Q:MATH:FAN:9"]],
    [["Q:MATH:AC:1"]],
    [["Q:PATTERN:TEMP_DOWN:2"]],
    [["Q:ENGLISH:AC:0"]],
    [["Q:REASON:TEMP_DOWN:2"]],
    [["Q:REASON:FAN:1"]],
    [["Q:FOO:FAN:1"]],
    [["Q:MATH:FAN"]],
    [["ROOMS:LEFT"]],
    [[MATH], [MATH]],
    [[AC, AC]],
])
def test_unknown_or_duplicate_keys_are_refused(slots):
    with pytest.raises(ValueError):
        request(slots)


def test_only_the_belt_mission_has_a_word_table():
    assert rule_for("BELT").must == [["딸깍"]]
    with pytest.raises(ValueError):
        rule_for("FOG")


def picks(req):
    return pick_questions(req, BANK, random.Random(0))


def test_prompt_carries_only_the_rules_not_free_text():
    req = request([[MATH], [REASON], ["BELT"]])
    msg = user_message(req, picks(req))
    assert "[key=Q:MATH:FAN:5]" in msg and "[key=Q:REASON:DEFROST:1]" in msg and "[key=BELT]" in msg
    # the situation's friend is the one its question names
    assert "주인공 친구: 새싹 로봇" in msg and "주인공 친구: 펭귄 선장" in msg
    assert "정답: 5 (답만큼 바람 세기(풍량)를 맞춘다). 이 수는 어디에도 쓰지 않는다" in msg
    assert "speech에 꼭 넣을 말: \"딸깍\"" in msg and "쓰지 말 말: \"풀어\"" in msg
    assert "겨울 저녁, 바깥 기온 3도" in msg
    assert "0123456789abcdef0123" not in msg


def test_prompt_shows_the_picked_question_and_hides_the_rest():
    req = request([[MATH], [REASON], [UNCOVERED]])
    msg = user_message(req, picks(req))
    # the writer writes the story that leads to the bank's question, word for word
    assert f"이 장면의 문제 (정해져 있음, 루미가 speech 바로 뒤에 읽는다): «{SUM.text}»" in msg
    assert f"«{FOG.text}»" in msg and "정답 조작: 앞 유리 성에 제거 켜기 (아이가 상황을 듣고 스스로 찾는다)" in msg
    # the bank's hints are not the writer's business
    assert SUM.hint not in msg and FOG.hint not in msg
    # the bank has nothing for this key: the car asks its own question
    uncovered = msg[msg.index(f"[key={UNCOVERED}]"):]
    assert "이 장면의 문제는 앱이 낸다" in uncovered and "«" not in uncovered


def test_prompt_tells_which_words_would_send_the_child_the_other_way():
    req = request([[AC], ["Q:REASON:AC:0"]])
    msg = user_message(req, picks(req))
    on, off = msg.split("[key=Q:REASON:AC:0]")
    assert "반대로 들리는 말은 쓰지 않는다: 추워, 꺼 줘, 끄자" in on and "정답 조작: 에어컨 켜기" in on
    assert "반대로 들리는 말은 쓰지 않는다: 더워, 켜 줘, 켜자" in off and "정답 조작: 에어컨 끄기" in off
    # each scene asks the situation written for its own on or off, about the friend it names
    assert f"«{COOL.text}»" in on and f"«{COLD.text}»" in off and f"«{COLD.text}»" not in on
    assert "주인공 친구: 새싹 로봇" in on and "주인공 친구: 수달 요리사" in off


def test_prompt_follows_the_youngest_age():
    assert "speech 110자 이내" in user_message(request([[MATH]], ages=("LITTLE", "DETECTIVE")))
    assert "speech 210자 이내" in user_message(request([[MATH]], ages=("DETECTIVE",)))


def test_prompt_keeps_puzzle_order_and_the_fixed_cast():
    msg = user_message(request([[REASON], [MATH], ["BELT"]]))
    assert msg.index("1번째 퍼즐") < msg.index(f"key={REASON}") < msg.index("2번째 퍼즐") < msg.index(f"key={MATH}")
    assert msg.index(f"key={MATH}") < msg.index("3번째 퍼즐") < msg.index("key=BELT")
    assert "아래 장면 중 하나만 나온다" not in msg
    for name in ("펭귄 선장", "사막여우", "토끼 기관사", "수달 요리사", "새싹 로봇", "투덜이 기계"):
        assert name in SYSTEM


def test_a_bad_praise_line_is_left_out_not_the_whole_draft():
    req = request([[MATH]])
    d = filled(req.keys)
    d.success = ["대단해!", "기차가 출발해!", "최고야!"]
    v = check(d, req)
    assert v.usable
    assert to_episode(d, v, req, "0" * 32, "m").success == ["대단해!", "최고야!"]
    d.success = ["대단해!", "기차가 출발해!"]
    assert "칭찬 문장 부족" in check(d, req).whole
