"""The key-less dev server and the sampler stay in step with the quiz rules and the question bank."""
import random

import pytest
from fastapi.testclient import TestClient

from app import bank, quiz
from app.bank import Bank
from app.checker import art_problems, check
from app.main import create_app
from app.models import EpisodeRequest
from app.prompt import SYSTEM, user_message
from app.service import pick_questions, put_questions
from tests.fakes import BANK, COOL, MATH, REASON, SUM, draft
from tests.test_quiz import every_key
from tools import story_spec
from tools.dev_server import TEMPLATE_ENTRIES, CardPainter, TemplateWriter, dev_bank
from tools.story_spec import DEVICES, random_request


AGES = ("LITTLE", "CAPTAIN", "DETECTIVE")


def never_told(q: quiz.QuizKey, age: str) -> bool:
    """A situation the youngest are never told (quiz.REASON_NOT_LITTLE): no bank has one for them."""
    return q.kind == "REASON" and age == "LITTLE" and q.dial in quiz.REASON_NOT_LITTLE


def test_the_built_in_dev_bank_passes_the_banks_own_checks():
    assert {e.id: bank.problems(e) for e in TEMPLATE_ENTRIES if bank.problems(e)} == {}
    assert len({e.id for e in TEMPLATE_ENTRIES}) == len(TEMPLATE_ENTRIES)


def test_the_built_in_dev_bank_has_a_question_of_the_right_device_for_every_key_at_every_age():
    dev = Bank(TEMPLATE_ENTRIES)
    # made for the youngest each is told to; the older ages get the same ones
    for e in TEMPLATE_ENTRIES:
        youngest = "CAPTAIN" if never_told(quiz.QuizKey(e.kind, e.dial, e.answer), "LITTLE") else "LITTLE"
        assert e.age == youngest, e.id
    for key in every_key():
        q = quiz.parse(key)
        for age in AGES:
            found = dev.candidates(q, age)
            if never_told(q, age):
                assert found == [], (key, age)
                continue
            assert found and all((e.kind, e.dial, e.answer) == (q.kind, q.dial, q.value) for e in found), (key, age)
    # one situation for every function and way a situation can ask
    assert sorted((e.dial, e.answer) for e in TEMPLATE_ENTRIES if e.kind == "REASON") == sorted(quiz.REASON_ACTIONS)


def test_the_dev_server_takes_the_real_bank_once_it_is_made(tmp_path):
    path = tmp_path / "question_bank.json"
    assert dev_bank(path).entries == TEMPLATE_ENTRIES
    bank.write([SUM, COOL], path)
    assert dev_bank(path).entries == [SUM, COOL]


@pytest.mark.parametrize("age", AGES)
def test_dev_server_stories_pass_the_real_checker(age):
    dev, writer = Bank(TEMPLATE_ENTRIES), TemplateWriter()
    for key in every_key():
        req = EpisodeRequest(installId="0123456789abcdef0123", ages=["DETECTIVE", age], minutes=10,
                             context={"timeOfDay": "NIGHT", "season": "SUMMER"}, puzzles=[[key], ["BELT"]],
                             friend="KONYANG")
        picks = pick_questions(req, dev, random.Random(0))
        if never_told(quiz.parse(key), age):
            assert key not in picks, key
            continue
        assert key in picks, key
        d, model = writer.write(SYSTEM, user_message(req, picks))
        put_questions(d, picks)
        v = check(d, req)
        assert model == "dev-template" and v.clean, (key, v.whole, v.items)
        assert art_problems(d, req) == {}


def test_the_dev_server_answers_the_car(tmp_path, monkeypatch):
    monkeypatch.setenv("DEV_PAINT_SECONDS", "0")
    app = create_app(writer=TemplateWriter(), data_dir=tmp_path, painter=CardPainter(),
                     bank=dev_bank(tmp_path / "no_bank.json"))
    body = {"installId": "0123456789abcdef0123", "ages": ["LITTLE"], "minutes": 10, "pictures": True,
            "context": {"timeOfDay": "NIGHT", "season": "SUMMER"},
            "puzzles": [["Q:MATH:FAN:3"], ["Q:REASON:AC:0"], ["BELT"]], "friend": "IO"}
    r = TestClient(app).post("/v1/episode", json=body)
    assert r.status_code == 200
    ep = r.json()
    assert ep["model"] == "dev-template" and ep["dropped"] == [] and "이오" in ep["intro"]
    assert ep["scenes"]["Q:MATH:FAN:3"]["question"]["expr"] == "1+2"
    assert ep["scenes"]["Q:REASON:AC:0"]["question"]["kind"] == "REASON"
    assert set(ep["images"]) == {"cover", "p1", "p2", "p3"}
    assert ep["scenes"]["Q:MATH:FAN:3"]["result"]


def test_sampled_requests_look_like_the_cars_plan():
    r = random.Random(7)
    device_of = {dial: device for device, dials in DEVICES.items() for dial in dials}
    assert sorted(device_of) == sorted(quiz.DIALS)
    seen = set()
    for _ in range(300):
        req = random_request(r)
        quizzes = [quiz.parse(slot[0]) for slot in req.puzzles if slot != ["BELT"]]
        assert len(quizzes) == {5: 2, 10: 3, 15: 4}[req.minutes]
        assert (["BELT"] in req.puzzles) == (len(quizzes) >= 3) and (len(quizzes) < 3 or req.puzzles[2] == ["BELT"])
        # one quiz per car device, every key one the car can send to this age (quiz.parse refuses the rest)
        devices = [device_of[q.dial] for q in quizzes]
        assert len(set(devices)) == len(devices)
        assert not any(never_told(q, req.youngest) for q in quizzes)
        # about half are situations; the number questions differ in kind where the devices allow it
        situations = [q for q in quizzes if q.kind == "REASON"]
        numbers = [q for q in quizzes if q.kind != "REASON"]
        assert len(situations) >= len(quizzes) // 2
        assert len({q.kind for q in numbers}) == min(len(numbers), 3)
        seen |= {(q.kind, q.dial) for q in quizzes}
    # every kind on every dial comes up
    assert seen == {(kind, dial) for dial in quiz.DIALS for kind in quiz.KINDS_FOR[dial]}


class RecordingWriter:
    def __init__(self):
        self.messages = []

    def write(self, system, user):
        self.messages.append(user)
        return draft([MATH, "BELT"]), "fake-model"


def test_the_sampler_writes_around_bank_questions_and_keeps_them_with_the_draft(monkeypatch):
    writer = RecordingWriter()
    monkeypatch.setattr(story_spec, "_writer", writer)
    monkeypatch.setattr(story_spec, "_bank", BANK)
    req = EpisodeRequest(installId="00000000-0000-0000-0000-000000000000", ages=["CAPTAIN"], minutes=5,
                         context={"timeOfDay": "NIGHT", "season": "SUMMER"}, puzzles=[[MATH], ["BELT"]],
                         friend="BANTE")
    written = story_spec.write(req)
    assert f"«{SUM.text}»" in writer.messages[0]
    # saved with its questions, a recheck later holds the same questions to the rules of the day
    assert written.draft.scenes[0].question.text == SUM.text and written.draft.scenes[0].hint == SUM.hint
    assert story_spec.SPEC.judge(req, written.draft).clean


def test_the_dev_writer_uses_the_theme_the_prompt_names():
    user = "- 오늘의 장소: 바닷속 잠수함 (theme에 SEA_SUB, world에도 그대로)\n[key=BELT]\n('나'): 반테."
    assert TemplateWriter().write("", user)[0].theme == "SEA_SUB"
    assert TemplateWriter().write("", "[key=BELT]")[0].theme == "STAR_TRAIN"


def test_the_dev_server_shelves_into_a_local_folder(tmp_path, monkeypatch):
    from tools.dev_server import dev_app
    monkeypatch.setenv("STORY_LIBRARY_DIR", str(tmp_path / "shelf"))
    monkeypatch.delenv("STORY_LIBRARY_MODELS", raising=False)
    app = dev_app()
    body = {"installId": "0123456789abcdef0123", "ages": ["CAPTAIN"], "minutes": 10, "theme": "SEA_SUB",
            "context": {"timeOfDay": "EVENING", "season": "WINTER"}, "puzzles": [[REASON], [MATH], ["BELT"]],
            "friend": "BANTE"}
    c = TestClient(app)
    r = c.post("/v1/episode", json=body)
    assert r.status_code == 200 and r.json()["model"] == "dev-template" and r.json()["theme"] == "SEA_SUB"
    app.state.library.drain(10)
    ask = {"ages": ["CAPTAIN"], "minutes": 10, "friend": "BANTE", "playable": [REASON, MATH], "allowFresh": False}
    assert len(c.post("/v1/library", json=ask).json()["stories"]) == 1
    assert list((tmp_path / "shelf").rglob("story.json"))
