"""Quiz puzzles: the checker works every answer out again, holds the question to the youngest child's age and keeps
the answer out of every line."""
import pytest

from app import quiz
from app.checker import check, check_quiz_scene, to_episode, to_question
from app.models import DraftScene, EpisodeRequest, Question
from app.prompt import user_message
from tests.fakes import AC, COOL, ENGLISH, FOG, GOOD, MATH, PATTERN, REASON, filled, question

AGES = ("LITTLE", "CAPTAIN", "DETECTIVE")


def request(slots, ages=("CAPTAIN",)):
    return EpisodeRequest(
        installId="0123456789abcdef0123",
        ages=list(ages),
        minutes=10,
        context={"timeOfDay": "EVENING", "season": "WINTER"},
        puzzles=slots,
    )


def problems(key, q, age="CAPTAIN"):
    return quiz.check_question(quiz.parse(key), q, age)


def scene(key, **change) -> DraftScene:
    bubble, speech, hint, q = GOOD[key]
    return DraftScene(key=key, bubble=bubble, speech=speech, hint=hint, question=q).model_copy(update=change)


def every_key():
    """Every key the car can send: each device with the kinds it asks and every answer a key of that kind takes."""
    for dial in quiz.DIALS:
        for kind in quiz.KINDS_FOR[dial]:
            lo, hi = quiz.values(kind, dial)
            yield from (f"Q:{kind}:{dial}:{v}" for v in range(lo, hi + 1))


# ---------------------------------------------------------------- keys


def test_a_quiz_key_carries_its_answer():
    q = quiz.parse("Q:MATH:FAN:5")
    assert (q.kind, q.dial, q.value, q.key) == ("MATH", "FAN", 5, "Q:MATH:FAN:5")
    assert quiz.parse("Q:REASON:AC:0").value == 0
    assert quiz.parse("Q:REASON:TEMP_DOWN:1") == quiz.QuizKey("REASON", "TEMP_DOWN", 1)
    assert quiz.parse("BELT") is None


@pytest.mark.parametrize("key", [
    "Q:MATH:FAN:9", "Q:MATH:FAN:0", "Q:MATH:FAN:-1", "Q:MATH:FAN:x", "Q:MATH:FAN", "Q:MATH:FAN:5:1",
    "Q:FOO:FAN:1", "Q:MATH:DOOR:1", "Q:MATH:AC:1", "Q:REASON:AC:2", "Q:ENGLISH:TEMP_UP:4",
    # a situation on the temperature only says which way, and a one-way function has one value
    "Q:REASON:TEMP_UP:2", "Q:REASON:TEMP_DOWN:3", "Q:REASON:DEFROST:0", "Q:REASON:SEAT_HEAT_OFF:1",
    "Q:REASON:LIGHTS_OFF:1",
    # a kind the device does not ask
    "Q:PATTERN:TEMP_UP:2", "Q:PATTERN:TEMP_DOWN:3", "Q:PATTERN:SEAT_HEAT:1", "Q:ENGLISH:AC:1", "Q:PATTERN:AC:0",
    "Q:REASON:FAN:3", "Q:REASON:LIGHTS:1", "Q:REASON:SEAT_HEAT:1", "Q:MATH:DEFROST:1", "Q:ENGLISH:RECIRC:1",
])
def test_a_key_the_app_could_not_send_is_refused(key):
    with pytest.raises(ValueError):
        quiz.parse(key)


def test_each_device_asks_only_its_own_kinds():
    assert quiz.KINDS_FOR["FAN"] == quiz.KINDS_FOR["LIGHTS"] == ("MATH", "ENGLISH", "PATTERN")
    assert quiz.KINDS_FOR["SEAT_HEAT"] == ("MATH", "ENGLISH")
    assert quiz.KINDS_FOR["TEMP_UP"] == quiz.KINDS_FOR["TEMP_DOWN"] == ("MATH", "ENGLISH", "REASON")
    # every function a situation can ask for is asked by a situation only (the temperature asks numbers too)
    for dial, value in quiz.REASON_ACTIONS:
        assert "REASON" in quiz.KINDS_FOR[dial] and quiz.parse(f"Q:REASON:{dial}:{value}").value == value
        if dial not in ("TEMP_UP", "TEMP_DOWN"):
            assert quiz.KINDS_FOR[dial] == ("REASON",)
    for dial, (lo, hi) in quiz.DIALS.items():
        for kind in quiz.KINDS:
            if kind in quiz.KINDS_FOR[dial]:
                top = quiz.values(kind, dial)[1]
                assert quiz.parse(f"Q:{kind}:{dial}:{top}") == quiz.QuizKey(kind, dial, top)
            else:
                with pytest.raises(ValueError, match="does not ask"):
                    quiz.parse(f"Q:{kind}:{dial}:{hi}")


def stand_in(q: quiz.QuizKey):
    """Any question of the key's kind: the writer's message only quotes it."""
    return question(q.kind, f"[시험] {q.key} 문제")


@pytest.mark.parametrize("age", AGES)
def test_every_key_the_car_can_send_is_explained_to_the_writer(age):
    for key in every_key():
        q = quiz.parse(key)
        req = request([[key]], ages=(age,))
        msg = user_message(req, {key: (stand_in(q), "")})
        assert f"[key={key}]" in msg and "주인공 친구:" in msg and f"«{stand_in(q).text}»" in msg
        said = f"정답 조작: {quiz.REASON_ACTIONS[(q.dial, q.value)]} (" if q.kind == "REASON" else f"정답: {q.value} ("
        assert said in msg, key
        # without a bank question the car asks its own, and the writer is told so
        assert "이 장면의 문제는 앱이 낸다" in user_message(req)


def test_the_writer_is_told_the_question_and_the_answer_to_keep_quiet():
    lines = quiz.scene_lines(quiz.parse(MATH), GOOD[MATH][3])
    assert lines[2].endswith(f"«{GOOD[MATH][3].text}»")
    assert "  정답: 5 (답만큼 바람 세기(풍량)를 맞춘다). 이 수는 어디에도 쓰지 않는다" in lines
    cool = quiz.scene_lines(quiz.parse("Q:MATH:TEMP_DOWN:2"), GOOD[ENGLISH][3])
    assert cool[-1] == "  반대로 들리는 말은 쓰지 않는다: 따뜻하게, 데워, 따끈하게"


def test_the_writer_is_told_a_situation_and_the_action_to_keep_quiet():
    lines = quiz.scene_lines(quiz.parse(AC), GOOD[AC][3])
    assert lines[2].endswith(f"«{COOL.text}»")
    assert ("  정답 조작: 에어컨 켜기 (아이가 상황을 듣고 스스로 찾는다). "
            "버튼 이름과 할 일은 bubble, speech, art 어디에도 쓰지 않는다") in lines
    # the story leads into the question's own trouble, not one made up for the device
    assert "  상황: 아래 문제 문장에 나온 상황 그대로다. 다른 상황이나 다른 까닭을 새로 만들지 않는다" in lines
    assert quiz.trouble(quiz.parse(AC)) not in "\n".join(lines)
    assert not any(line.startswith("  정답: ") for line in lines)


@pytest.mark.parametrize("key,q,friend,default", [
    # the question names another friend than the device's own: the story is about the question's friend
    (AC, COOL, "새싹 로봇", "투덜이 기계"),
    (REASON, FOG, "펭귄 선장", "투덜이 기계"),
])
def test_a_situation_scene_is_about_the_friend_its_question_names(key, q, friend, default):
    k = quiz.parse(key)
    assert quiz.friend(k) == default and quiz.question_friend(k, q.text) == friend
    lines = quiz.scene_lines(k, GOOD[key][3])
    assert lines[0] == f"  주인공 친구: {friend}"
    assert not any(default in line for line in lines)


def test_the_first_friend_a_situation_names_leads_and_none_falls_back_to_the_devices():
    k = quiz.parse("Q:REASON:DEFROST:1")
    assert quiz.question_friend(k, "수달 요리사와 펭귄 선장이 노래를 불러서 앞 유리가 뿌예지려고 해.") == "수달 요리사"
    assert quiz.question_friend(k, "앞 유리 안쪽이 뿌예지려고 해.") == "투덜이 기계"
    # a number question's scene always has the device's friend
    assert quiz.scene_lines(quiz.parse(MATH), GOOD[MATH][3])[0] == "  주인공 친구: 새싹 로봇"


# ---------------------------------------------------------------- math


@pytest.mark.parametrize("age", AGES)
def test_a_small_sum_passes_at_every_age(age):
    assert problems(MATH, GOOD[MATH][3], age) == []


def test_times_and_brackets_are_for_the_oldest():
    times = question("MATH", "사과가 두 개씩 세 줄 있어. 모두 몇 개일까?", expr="2*3")
    assert problems("Q:MATH:FAN:6", times, "DETECTIVE") == []
    assert problems("Q:MATH:FAN:6", times, "CAPTAIN") == ["이 나이에 쓰지 않는 셈: *"]
    assert problems("Q:MATH:FAN:6", times, "LITTLE") == ["이 나이에 쓰지 않는 셈: *"]
    brackets = question("MATH", "4에 3을 곱하고 7을 빼면 몇일까?", expr="(4*3)-7")
    assert problems(MATH, brackets, "DETECTIVE") == []


@pytest.mark.parametrize("age", AGES)
def test_no_age_divides(age):
    shared = question("MATH", "사탕 4개를 2명이 똑같이 나누면 몇 개씩일까?", expr="4/2")
    assert problems("Q:MATH:FAN:2", shared, age) == ["이 나이에 쓰지 않는 셈: /"]
    assert problems("Q:MATH:FAN:2", question("MATH", "4 나누기 2는?", expr="4÷2"), age) == ["이 나이에 쓰지 않는 셈: /"]


def test_the_levels_are_easy_enough_to_hear_in_a_car():
    little, captain, detective = (quiz.LEVELS[a] for a in AGES)
    assert (little.ops, little.max_ops, little.max_operand, little.max_factor) == (frozenset("+-"), 1, 5, 0)
    assert (captain.ops, captain.max_ops, captain.max_operand, captain.max_factor) == (frozenset("+-"), 1, 10, 0)
    assert (detective.ops, detective.max_ops, detective.max_operand, detective.max_factor) == (frozenset("+-*"), 2, 12, 5)
    # number words up to TEN for everyone, counting letters for the oldest only
    assert [(lv.english_max, lv.english_letters) for lv in (little, captain, detective)] == [(10, False), (10, False), (10, True)]
    # runs: by one for the youngest, by one or two, then by one to three; never doubling
    assert [(lv.step_max, lv.step_down, lv.ratio, lv.max_term) for lv in (little, captain, detective)] == [
        (1, True, False, 10), (2, True, False, 10), (3, True, False, 20)]


@pytest.mark.parametrize("key,age,expr,text", [
    # the youngest add or take away once, with numbers up to 5
    ("Q:MATH:FAN:5", "LITTLE", "1+4", "바람 세기를 1 더하기 4단으로 맞춰 줘!"),
    ("Q:MATH:FAN:1", "LITTLE", "5-4", "바람 세기를 5 빼기 4단으로 맞춰 줘!"),
    # the middle age once, with numbers up to 10
    ("Q:MATH:FAN:8", "CAPTAIN", "10-2", "바람 세기를 10 빼기 2단으로 맞춰 줘!"),
    ("Q:MATH:FAN:7", "CAPTAIN", "3+4", "바람 세기를 3 더하기 4단으로 맞춰 줘!"),
    # the oldest twice, with numbers up to 12 and a times sign up to 5
    ("Q:MATH:FAN:8", "DETECTIVE", "12-2-2", "바람 세기를 12 빼기 2 빼기 2단으로 맞춰 줘!"),
    ("Q:MATH:FAN:7", "DETECTIVE", "5*2-3", "바람 세기를 5 곱하기 2 빼기 3단으로 맞춰 줘!"),
])
def test_a_sum_at_the_top_of_each_age_passes(key, age, expr, text):
    assert problems(key, question("MATH", text, expr=expr), age) == []


@pytest.mark.parametrize("key,age,expr,text,expected", [
    (MATH, "CAPTAIN", "3+3", "사과가 세 개 있었는데 세 개를 더 땄어. 모두 몇 개일까?", "답이 다름: 6 (정답 5)"),
    (MATH, "LITTLE", "7-2", "사과 7개 중에 2개를 먹었어. 몇 개 남았을까?", "수가 너무 큼: 7"),
    (MATH, "LITTLE", "1+1+3", "사과 1개, 1개, 3개를 모으면 몇 개일까?", "셈이 너무 많음: 2번"),
    (MATH, "CAPTAIN", "2-4+7", "2에서 4를 빼고 7을 더하면 몇일까?", "중간에 0보다 작아짐"),
    ("Q:MATH:FAN:2", "LITTLE", "6-4", "바람 세기를 6 빼기 4단으로 맞춰 줘!", "수가 너무 큼: 6"),
    # the middle age: one step, numbers up to 10
    ("Q:MATH:FAN:5", "CAPTAIN", "2+4-1", "바람 세기를 2 더하기 4 빼기 1단으로 맞춰 줘!", "셈이 너무 많음: 2번"),
    ("Q:MATH:FAN:5", "CAPTAIN", "11-6", "바람 세기를 11 빼기 6단으로 맞춰 줘!", "수가 너무 큼: 11"),
    ("Q:MATH:FAN:6", "CAPTAIN", "2*3", "바람 세기를 2 곱하기 3단으로 맞춰 줘!", "이 나이에 쓰지 않는 셈: *"),
    # the oldest: two steps, numbers up to 12, a times sign with numbers up to 5, and no sharing out
    ("Q:MATH:FAN:5", "DETECTIVE", "1+2+3-1", "바람 세기를 1 더하기 2 더하기 3 빼기 1단으로 맞춰 줘!", "셈이 너무 많음: 3번"),
    ("Q:MATH:FAN:8", "DETECTIVE", "13-5", "바람 세기를 13 빼기 5단으로 맞춰 줘!", "수가 너무 큼: 13"),
    ("Q:MATH:FAN:3", "DETECTIVE", "6/2", "바람 세기를 6 나누기 2단으로 맞춰 줘!", "이 나이에 쓰지 않는 셈: /"),
    ("Q:MATH:FAN:8", "DETECTIVE", "2*6-4", "바람 세기를 2 곱하기 6 빼기 4단으로 맞춰 줘!", "곱하거나 나누는 수가 너무 큼"),
    ("Q:MATH:FAN:8", "DETECTIVE", "6*2-4", "바람 세기를 6 곱하기 2 빼기 4단으로 맞춰 줘!", "곱하거나 나누는 수가 너무 큼"),
    (MATH, "CAPTAIN", "3+\x002", "사과 3개와 2개를 더하면 몇 개일까?", "식을 읽을 수 없음: 3+\x002"),
    (MATH, "CAPTAIN", "10-5", "열에서 다섯을 빼면 몇일까?", "식에 정답 수가 들어 있음"),
    (MATH, "CAPTAIN", "3+2", "사과가 세 개 있었는데 조금 더 땄어. 이제 사과는 몇 개일까?", "식의 수가 문제에 없음: 2"),
    (MATH, "CAPTAIN", "5", "사과는 몇 개일까?", "셈이 없음"),
    (MATH, "CAPTAIN", "2**2+1", "2의 제곱에 1을 더하면?", "식에 쓸 수 없는 것: 2**2+1"),
    (MATH, "CAPTAIN", "__import__('os')", "사과는 몇 개일까?", "식에 쓸 수 없는 것: __import__('os')"),
])
def test_a_sum_that_does_not_work_out_or_fit_the_age_is_refused(key, age, expr, text, expected):
    assert expected in problems(key, question("MATH", text, expr=expr), age)


# ---------------------------------------------------------------- english


def test_number_words_for_everyone_letter_counts_for_the_oldest():
    for age in AGES:
        assert problems(ENGLISH, GOOD[ENGLISH][3], age) == []
        ten = question("ENGLISH", "바람 세기를 TEN단으로 맞춰 줘!", word="TEN")
        assert problems("Q:ENGLISH:FAN:8", ten, age) == ["답이 다름: 10 (정답 8)"]
    cat = question("ENGLISH", "CAT의 글자 수만큼 켜진 독서등이 되게 해 줘!", word="CAT")
    assert problems("Q:ENGLISH:LIGHTS:3", cat, "DETECTIVE") == []
    assert problems("Q:ENGLISH:LIGHTS:3", cat, "CAPTAIN") == ["이 나이는 숫자 영단어만"]
    assert problems("Q:ENGLISH:LIGHTS:3", cat, "LITTLE") == ["이 나이는 숫자 영단어만"]


@pytest.mark.parametrize("key,age,word,text,expected", [
    ("Q:ENGLISH:LIGHTS:4", "DETECTIVE", "CAT", "CAT은 몇 글자일까?", "답이 다름: 3 (정답 4)"),
    ("Q:ENGLISH:FAN:3", "LITTLE", "TWELVE", "TWELVE는 우리말로 몇일까?", "이 나이에 어려운 숫자 단어: TWELVE"),
    # number words stop at TEN at every age
    ("Q:ENGLISH:FAN:3", "DETECTIVE", "ELEVEN", "바람 세기를 ELEVEN단으로 맞춰 줘!", "이 나이에 어려운 숫자 단어: ELEVEN"),
    ("Q:ENGLISH:LIGHTS:3", "DETECTIVE", "CAT", "이 동물 이름은 몇 글자일까?", "영어 단어가 문제에 없음"),
    ("Q:ENGLISH:LIGHTS:4", "CAPTAIN", "FIRE", "FIRE는 몇 글자일까?", "쓰면 안 되는 단어: FIRE"),
    ("Q:ENGLISH:LIGHTS:3", "CAPTAIN", "C A T", "C A T은 몇 글자일까?", "영어 단어는 알파벳 2~8글자: C A T"),
])
def test_an_english_question_that_does_not_fit_is_refused(key, age, word, text, expected):
    assert expected in problems(key, question("ENGLISH", text, word=word), age)


# ---------------------------------------------------------------- pattern


@pytest.mark.parametrize("age", AGES)
def test_no_age_doubles(age):
    doubling = question("PATTERN", "1, 2, 4 다음은 몇일까?", terms=[1, 2, 4])
    assert problems("Q:PATTERN:FAN:8", doubling, age) == ["규칙이 하나로 정해지지 않음"]


def test_the_youngest_count_down_by_one_only():
    down = question("PATTERN", "9, 8, 7 다음은 몇일까?", terms=[9, 8, 7])
    assert problems("Q:PATTERN:FAN:6", down, "CAPTAIN") == []
    assert problems("Q:PATTERN:FAN:6", down, "LITTLE") == []
    by_two = question("PATTERN", "10, 8, 6 다음은 몇일까?", terms=[10, 8, 6])
    assert problems("Q:PATTERN:LIGHTS:4", by_two, "LITTLE") == ["이 나이에 맞지 않는 규칙: -2씩"]


@pytest.mark.parametrize("key,age,terms,text,expected", [
    (PATTERN, "CAPTAIN", [1, 2, 3, 5], "1, 2, 3, 5 다음은?", "규칙이 하나로 정해지지 않음"),
    ("Q:PATTERN:FAN:8", "LITTLE", [2, 4, 6], "2, 4, 6 다음은?", "이 나이에 맞지 않는 규칙: +2씩"),
    (PATTERN, "CAPTAIN", [2, 3], "2, 3 다음은?", "수는 3~5개"),
    (PATTERN, "CAPTAIN", [2, 3, 4], "2, 3, 4 다음은?", "답이 다름: 5 (정답 4)"),
    (PATTERN, "CAPTAIN", [1, 2, 3], "별이 차례로 반짝였어. 그다음은 몇일까?", "수열의 수가 문제에 없음: 1"),
    (PATTERN, "LITTLE", [9, 10, 11, 12], "9, 10, 11, 12 다음은?", "수가 너무 큼"),
    # by one or two up to 10 for the middle age, by one to three up to 20 for the oldest
    ("Q:PATTERN:FAN:1", "CAPTAIN", [10, 7, 4], "10, 7, 4 다음은?", "이 나이에 맞지 않는 규칙: -3씩"),
    ("Q:PATTERN:FAN:8", "CAPTAIN", [14, 12, 10], "14, 12, 10 다음은?", "수가 너무 큼"),
    ("Q:PATTERN:FAN:1", "DETECTIVE", [13, 9, 5], "13, 9, 5 다음은?", "이 나이에 맞지 않는 규칙: -4씩"),
    ("Q:PATTERN:FAN:8", "DETECTIVE", [20, 21, 22], "20, 21, 22 다음은?", "수가 너무 큼"),
])
def test_a_pattern_that_does_not_fit_is_refused(key, age, terms, text, expected):
    assert expected in problems(key, question("PATTERN", text, terms=terms), age)


# ---------------------------------------------------------------- situation (REASON)


def told(n: int):
    """A situation of exactly n characters."""
    return question("REASON", "가" * (n - 1) + "!")


@pytest.mark.parametrize("age,longest", [("LITTLE", 70), ("CAPTAIN", 90), ("DETECTIVE", 120)])
def test_a_situation_may_be_as_long_as_its_age_allows(age, longest):
    assert quiz.REASON_MAX[age] == longest
    assert problems(AC, told(longest), age) == []
    assert problems(AC, told(longest + 1), age) == [f"문제 문장 길이 {longest + 1} > {longest}"]


def test_the_oldest_hear_a_situation_longer_than_any_number_question():
    # two clues take more words than an instruction: 120 characters pass for a situation, 100 for a number question
    assert problems(AC, told(120), "DETECTIVE") == []
    assert problems("Q:REASON:TEMP_DOWN:1", told(120), "DETECTIVE") == []
    assert problems(AC, told(91), "CAPTAIN") == ["문제 문장 길이 91 > 90"]
    long_sum = question("MATH", "가" * 101, expr="3+2")
    assert problems(MATH, long_sum, "DETECTIVE") == ["문제 문장 길이 101 > 100"]


@pytest.mark.parametrize("age", AGES)
def test_a_situation_has_nothing_to_work_out(age):
    # the bank checked its words when it was made; here only its kind and length
    assert problems(AC, GOOD[AC][3], age) == []
    assert problems(REASON, GOOD[REASON][3], age) == []
    assert problems(AC, GOOD[MATH][3], age) == ["문제 종류가 다름: MATH"]
    assert problems(AC, question("REASON", " "), age) == ["문제 문장 없음"]


def test_a_situation_scene_passes_and_names_no_button():
    q = quiz.parse(AC)
    assert check_quiz_scene(q, scene(AC), "CAPTAIN") == []
    named = scene(AC, speech="새싹 로봇이 더워해. 에어컨을 찾아볼까?")
    assert "이야기에 버튼 말: 에어컨" in check_quiz_scene(q, named, "CAPTAIN")
    # a situation has no number, so a count in the story gives nothing away
    counted = scene(AC, speech="새싹 로봇이 줄넘기를 두 번 하고 와서 땀을 뻘뻘 흘려. 이 문제를 풀어 볼까?")
    assert check_quiz_scene(q, counted, "CAPTAIN") == []
    assert quiz.answer_words(q) == []


# ---------------------------------------------------------------- the answer stays hidden


@pytest.mark.parametrize("text,n,leak", [
    ("사과가 5개야", 5, True), ("사과가 다섯 개야", 5, True), ("세 칸을 가", 3, True), ("두 단으로", 2, True),
    ("열두 개", 12, True), ("15개", 5, False), ("다섯 친구가 웃어", 5, False), ("세어 봐", 3, False),
    # a number word inside a longer word counts nothing: the 두 of 모두 or 열두
    ("켜진 독서등이 모두 개가 되게 해 줘!", 2, False), ("독서등 두 개를 켜 줘", 2, True), ("열두 개", 2, False),
    ("세 글자야", 3, True), ("두 단계로", 2, True), ("네 층까지", 4, True), ("다섯 살이야", 5, True),
    ("정답은 다섯", 5, True), ("답이 셋이야", 3, True), ("셋이서 웃었어", 3, False),
])
def test_an_answer_is_given_away_by_digits_or_counted_words(text, n, leak):
    assert quiz.leaks(text, n) is leak


@pytest.mark.parametrize("field,line,name", [
    ("bubble", "사과 5개!", "말풍선"),
    ("speech", "사과 다섯 개만 있으면 새싹이 웃을 거야!", "이야기"),
    ("hint", "손가락 다섯 개를 펴 봐.", "힌트"),
])
def test_the_answer_in_any_line_drops_the_scene(field, line, name):
    q = quiz.parse(MATH)
    assert f"{name}에 정답 수" in check_quiz_scene(q, scene(MATH, **{field: line}), "CAPTAIN")


def test_the_answer_in_the_question_drops_the_scene():
    q = quiz.parse(MATH)
    told = question("MATH", "사과 세 개와 두 개를 더하면 5개일까?", expr="3+2")
    assert "문제에 정답 수" in check_quiz_scene(q, scene(MATH, question=told), "CAPTAIN")


def test_only_the_english_word_may_be_english():
    q = quiz.parse(ENGLISH)
    assert check_quiz_scene(q, scene(ENGLISH), "CAPTAIN") == []
    # the bank's hint may say the question's word and count in English, as bank.problems allows
    for hint in ("TWO를 떠올려 봐.", "ONE, TWO 하고 손가락을 펴며 세어 봐.", "ONE부터 차례로 세어 봐."):
        assert check_quiz_scene(q, scene(ENGLISH, hint=hint), "CAPTAIN") == [], hint
    assert "영어나 주소" in check_quiz_scene(q, scene(ENGLISH, hint="CAR를 떠올려 봐."), "CAPTAIN")
    assert "영어나 주소" in check_quiz_scene(q, scene(ENGLISH, speech="TWO를 떠올려 봐. 이 문제를 풀어 볼까?"), "CAPTAIN")
    other = question("ENGLISH", "TWO는 몇일까? DOG도 생각해 봐.", word="TWO")
    assert "영어나 주소" in check_quiz_scene(q, scene(ENGLISH, question=other), "CAPTAIN")
    # any other question's hint says no English at all
    assert "영어나 주소" in check_quiz_scene(quiz.parse(MATH), scene(MATH, hint="ONE부터 세어 봐."), "CAPTAIN")


@pytest.mark.parametrize("key,words", [
    ("Q:REASON:AC:1", ["추워", "꺼 줘", "끄자"]),
    ("Q:REASON:AC:0", ["더워", "켜 줘", "켜자"]),
    ("Q:REASON:RECIRC:1", ["답답", "맑은 공기"]),
    ("Q:REASON:RECIRC:0", ["먼지", "매연"]),
    ("Q:REASON:TEMP_DOWN:1", ["따뜻하게", "데워", "따끈하게"]),
    ("Q:REASON:FAN_DOWN:1", ["더 세게", "세게 해"]),
    ("Q:MATH:TEMP_DOWN:2", ["따뜻하게", "데워", "따끈하게"]),
    ("Q:MATH:TEMP_UP:2", ["시원하게", "식혀", "차갑게"]),
    (MATH, []),
    (REASON, []),
])
def test_words_that_would_send_the_child_the_other_way(key, words):
    assert quiz.other_way(quiz.parse(key)) == words


def test_a_quiz_question_of_another_kind_is_refused():
    assert problems(MATH, GOOD[ENGLISH][3]) == ["문제 종류가 다름: ENGLISH"]
    assert problems(MATH, None) == ["문제 없음"]


# ---------------------------------------------------------------- what the car gets


def test_a_situation_reaches_the_car_as_its_words_only():
    req = request([[MATH], [REASON], [AC]], ages=("DETECTIVE",))
    d = filled(req.keys)
    v = check(d, req)
    assert v.clean, v.items
    ep = to_episode(d, v, req, "0" * 32, "m")
    assert ep.scenes[AC].question == Question(kind="REASON", text=COOL.text)
    assert ep.scenes[REASON].question == Question(kind="REASON", text=FOG.text)
    assert ep.scenes[AC].hint == COOL.hint
    assert ep.scenes[MATH].question.expr == "3+2"


def test_only_the_parts_of_the_kind_reach_the_car():
    q = quiz.parse(ENGLISH)
    messy = question("ENGLISH", " TWO는 우리말로 몇일까? ", word=" two ", expr="1+1", terms=[1, 2])
    out = to_question(q, messy)
    assert (out.text, out.word, out.expr, out.terms) == ("TWO는 우리말로 몇일까?", "TWO", "", [])
    situation = question("REASON", f" {COOL.text} ", word="x", expr="1+1", terms=[1])
    assert to_question(quiz.parse(AC), situation) == Question(kind="REASON", text=COOL.text)
