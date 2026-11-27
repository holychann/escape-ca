import random

import pytest

from app import bank
from app.checker import art_problems, bad_words, check, to_episode
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
        friend=FRIEND,
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
    d.scenes[0].speech = "사과 다섯 개만 있으면 된대!"
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


@pytest.mark.parametrize("line,expected", [
    ("나 너무 더워. 에어컨을 찾아볼까?", "이야기에 버튼 말: 에어컨"),
    ("땀이 뻘뻘 나. 온도를 바꾸면 될까?", "이야기에 버튼 말: 온도"),
    ("땀이 뻘뻘 나. 버튼을 눌러 볼까?", "이야기에 버튼 말: 버튼"),
    ("땀이 뻘뻘 나. 시원한 바람을 켜 줘!", "이야기에 조작 말: 켜 줘"),
    ("땀이 뻘뻘 나. 불 켜기 해 볼까?", "이야기에 조작 말: 켜기"),
])
def test_a_situation_story_naming_a_button_or_action_is_dropped(line, expected):
    req = request([[AC]])
    d = filled(req.keys)
    d.scenes[0].speech = line
    assert expected in check(d, req).items[AC]


def test_polite_speech_in_episode_or_scene_is_rejected():
    from app.models import DRAFT_SCHEMA
    from app.rules import LIMITS

    assert f"{LIMITS.title}자 이내" in DRAFT_SCHEMA["properties"]["title"]["description"]
    req = request([[MATH], ["BELT"]])
    assert "존댓말" in check(filled(req.keys, intro="안녕, 탐험대장! 나는 반테예요. 같이 가 볼까요?"), req).whole
    assert "존댓말" in check(filled(req.keys, treasureBadge="탐험 배지를 드립니다!"), req).whole
    d = filled(req.keys)
    d.scenes[0].speech = "바람개비가 멈췄어요. 같이 돌려 봐요!"
    assert "존댓말" in check(d, req).items[MATH]
    d2 = filled(req.keys)
    d2.scenes[0].result = "바람개비가 잘 돌아가요!"
    ep = to_episode(d2, check(d2, req), req, "0" * 32, "m")
    assert ep.scenes[MATH].result == ""
    # nouns ending in 요 ("담요", "동요") pass cleanly
    d3 = filled(req.keys)
    d3.scenes[0].bubble = "따뜻한 담요, 동요!"
    assert check(d3, req).clean


def test_the_chosen_friend_introduces_itself():
    req = request([[MATH]])
    assert "첫인사에 고른 친구 소개 빠짐" in check(filled(req.keys, intro="안녕, 탐험대장! 별빛 기차가 멈췄대."), req).whole


@pytest.mark.parametrize("name", ["루미", "새싹 로봇", "코냥", "캐스핀"])
def test_only_the_chosen_friend_is_in_the_story(name):
    req = request([[MATH], ["BELT"]])
    assert f"다른 인물: {name}" in check(filled(req.keys, treasureBadge=f"{name}도 함께 배지를 줄게!"), req).whole
    d = filled(req.keys)
    d.scenes[0].speech = f"{name}가 바람개비를 들고 왔어. 바람이 몇 단인지 알아내 볼까?"
    assert f"다른 인물: {name}" in check(d, req).items[MATH]


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
    (AC, AC, "speech", "나 너무 추워서 오들오들 떨려.", "추워"),
    (AC, AC, "bubble", "에취, 꺼 줘!", "꺼 줘"),
    ("Q:REASON:AC:0", AC, "bubble", "더워서 땀이 나!", "더워"),
    ("Q:REASON:FAN_DOWN:1", REASON, "speech", "바람을 더 세게 해 줘.", "더 세게"),
    ("Q:ENGLISH:TEMP_DOWN:2", ENGLISH, "speech", "작업실을 따뜻하게 데워 주자!", "따뜻하게"),
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
        "key": off, "bubble": "오들오들 떨려!", "speech": "물에 첨벙 빠졌더니 오들오들 떨려. 이 문제를 풀어 볼까?",
        "question": bank.question(COLD), "hint": "더워서 땀 날 때 켜는 버튼이 지금도 켜져 있는지 살펴봐.",
    })
    assert off not in check(d, request([[off]])).items


def test_a_situation_picture_shows_the_trouble_but_no_button():
    req = request([[MATH], [REASON], ["BELT"]])
    d = filled(req.keys)
    d.art = Art(cover="별빛 기차 앞의 반테", puzzles=["반테가 멈춘 바람개비를 본다", "반테가 뿌연 앞 유리 앞에서 입김을 분다", "탐험대장 자리"])
    # a situation has no number to give away, so its trouble may be drawn
    assert art_problems(d, req) == {}
    d.art = d.art.model_copy(update={"puzzles": ["반테가 멈춘 바람개비를 본다", "반테가 성에 낀 앞 유리를 본다", "탐험대장 자리"]})
    assert art_problems(d, req) == {"p2": ["버튼 말: 성에"]}
    d.art = d.art.model_copy(update={"cover": "별빛 기차 앞의 루미와 반테", "puzzles": ["코냥이 바람개비를 본다", "반테가 뿌연 앞 유리를 본다", "펭귄 선장"]})
    assert art_problems(d, req) == {"cover": ["다른 인물: 루미"], "p1": ["다른 인물: 코냥"], "p3": ["다른 인물: 펭귄 선장"]}


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
    d.scenes[0].speech = GOOD[MATH][1] + " 바람이 몇 단인지 하나하나 천천히 세어 보자. 다 세면 바람개비가 빙글빙글 힘차게 돌고, 우리 발명품이 드디어 움직이기 시작할 거야!"
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
    # the chosen friend tells the story; nobody else is named for a scene
    assert "고른 친구('나'): 반테. 강아지 친구이고, 담고 있는 마음은 '용기'이다." in msg and "주인공 친구" not in msg
    assert "정답: 5 (답만큼 바람 세기(풍량)를 맞춘다). 이 수는 bubble, speech, art에 쓰지 않는다" in msg
    assert "speech에 꼭 넣을 말: \"딸깍\"" in msg and "쓰지 말 말: \"풀어\"" in msg
    assert "- 지금 계절: 겨울, 바깥 기온 3도" in msg and "저녁" not in msg.split("하루 중 때")[0]
    assert "0123456789abcdef0123" not in msg


def test_prompt_shows_the_picked_question_and_hides_the_rest():
    req = request([[MATH], [REASON], [UNCOVERED]])
    msg = user_message(req, picks(req))
    # the writer writes the story that leads to the bank's question, word for word
    assert f"이 장면의 문제 (정해져 있음, 친구가 speech 바로 뒤에 읽는다): «{SUM.text}»" in msg
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
    assert "반대로 들리는 말은 bubble과 speech에 쓰지 않는다: 추워, 꺼 줘, 끄자" in on and "정답 조작: 에어컨 켜기" in on
    assert "반대로 들리는 말은 bubble과 speech에 쓰지 않는다: 더워, 켜 줘, 켜자" in off and "정답 조작: 에어컨 끄기" in off
    # each scene asks the situation written for its own on or off
    assert f"«{COOL.text}»" in on and f"«{COLD.text}»" in off and f"«{COLD.text}»" not in on


def test_prompt_follows_the_youngest_age():
    little = user_message(request([[MATH]], ages=("LITTLE", "DETECTIVE")))
    assert "speech 110자 이내" in little and "result 70자 이내" in little
    oldest = user_message(request([[MATH]], ages=("DETECTIVE",)))
    assert "speech 210자 이내" in oldest and "result 90자 이내" in oldest


def test_prompt_keeps_puzzle_order_and_only_the_chosen_friend():
    msg = user_message(request([[REASON], [MATH], ["BELT"]]))
    assert msg.index("1번째 퍼즐") < msg.index(f"key={REASON}") < msg.index("2번째 퍼즐") < msg.index(f"key={MATH}")
    assert msg.index(f"key={MATH}") < msg.index("3번째 퍼즐") < msg.index("key=BELT")
    assert "아래 장면 중 하나만 나온다" not in msg
    # the old crew and the gift line are gone from what the writer is told
    for word in ("펭귄 선장", "사막여우", "토끼 기관사", "수달 요리사", "새싹 로봇", "투덜이 기계", "treasureGift", "success"):
        assert word not in SYSTEM and word not in msg


def test_no_friend_is_given_a_catchphrase_to_repeat():
    # said scene after scene, a catchphrase made the script read oddly: the writer is given none and told not to repeat one
    for friend in ("BANTE", "KONYANG", "CASPIN"):
        msg = user_message(request([[MATH]]).model_copy(update={"friend": friend}))
        assert "말버릇" not in msg and "먼저 해볼래" not in msg and "자세히 보면 보여" not in msg
    assert "말버릇이나 입버릇처럼 정해진 말을 여러 장면에 되풀이하지 않는다" in SYSTEM


def test_the_greeting_never_announces_the_lesson():
    assert "intro에서는 교훈을 말로 다짐하거나 예고하지 않는다" in SYSTEM and "intro에서 살짝 비춘다" not in SYSTEM


def test_a_named_thing_takes_its_title_after_the_name():
    assert "'참나무 할아버지'" in SYSTEM and "'할아버지 참나무'처럼 호칭을 앞에 두지 않는다" in SYSTEM


def test_the_result_line_may_name_the_answer_the_child_just_put_in():
    req = request([[AC], [MATH]])
    d = filled(req.keys)
    v = check(d, req)
    assert v.clean, (v.whole, v.items)
    ep = to_episode(d, v, req, "0" * 32, "m")
    assert ep.scenes[AC].result.startswith("에어컨 바람") and "바람" in ep.scenes[MATH].result


@pytest.mark.parametrize("line", ["기차 안이 따뜻해졌어! 이제 다음 칸으로 가자!", "마차에서 시원한 바람이 솔솔 불어!",
                                  "차가운 물방울이 멈췄어! 단서가 하나 남았어!"])
def test_a_result_line_may_tell_the_story_vehicle_or_the_feeling(line):
    req = request([[MATH], ["BELT"]])
    d = filled(req.keys)
    d.scenes[0].result = line
    v = check(d, req)
    ep = to_episode(d, v, req, "0" * 32, "m")
    assert ep.scenes[MATH].result == line


@pytest.mark.parametrize("line", ["기차가 출발해서 다음 곳으로 가자!", "루미도 박수를 쳐!", "와" * 100,
                                  "차 안이 따뜻해져서 손이 녹았어!", "우리 차에서 시원한 바람이 나와!"])
def test_a_bad_result_line_is_left_out_not_the_scene(line):
    req = request([[MATH], ["BELT"]])
    d = filled(req.keys)
    d.scenes[0].result = line
    v = check(d, req)
    assert v.clean, (v.whole, v.items)
    ep = to_episode(d, v, req, "0" * 32, "m")
    # the scene still plays; the car says its own praise instead of the line
    assert ep.scenes[MATH].result == "" and ep.scenes[MATH].speech == GOOD[MATH][1]
    assert ep.scenes["BELT"].result == GOOD["BELT"][4]


@pytest.mark.parametrize("text", [
    "귀.신이 나타났어!",
    "귀_신 이야기",
    "귀*신 놀이",
    "귀\u200b신이 숨었어",
    "창.문.을 열.어 봐",
    "창 문 열 어 봐",
    "벨 트 를 풀 어",
    "전 화 번 호 알려 줘",
    "비.밀.번.호 맞히기",
    "ＨＴＴＰＳ://example.com",
    "Ｌｅｔ us go",
])
def test_obfuscated_bad_words_are_still_caught(text):
    assert bad_words(text)


@pytest.mark.parametrize("text", [
    "합창 문을 열려면 다 함께 노래하자",
    "우주 소리를 들어 볼까",
    "복귀 신호가 반짝였어",
    "저 주에 보았던 별자리야",
])
def test_tightened_patterns_keep_ordinary_phrases_clean(text):
    assert bad_words(text) == []


def test_norm_and_leak_checks_handle_symbols_invisible_chars_and_fullwidth():
    from app.text_guard import missing, norm, present

    assert norm("Ｔ-Ｒｅｘ_탐/험*대\u200b!") == "trex탐험대"
    assert present("에/어/컨 바람", ["에어컨"]) == ["에어컨"]
    assert missing("안_전_지_킴_이에게 알려 줘", [["안전 지킴이"]]) == []

    req = request([[MATH]])
    d = filled(req.keys)
    d.scenes[0].speech = "사과 ５개만 모으면 된대!"
    assert "이야기에 정답 수" in check(d, req).items[MATH]

