"""A wished story on the shelf: found again only by exactly the same choices and wish, never held back, and a new
one for the same choices carries a lesson the shelf does not have yet."""
import random

from fastapi.testclient import TestClient

from app.library import Cell, FolderStorage, Library, wish_key
from app.main import create_app
from app.models import LESSON_LABEL, EpisodeRequest, LibraryRequest
from tests.fakes import BANK, FRIEND, MATH, REASON, FakeWriter, draft
from tests.test_library_api import body, draft_and_request, story
from tests.test_library_shelve import BODY, KEYS, checked

WISH = "공룡 섬에서 펼쳐지는 보물찾기 이야기"


def wished(sid, wish=WISH, lesson=None, **kw):
    s = story(sid, **kw)
    return s.model_copy(update={"meta": s.meta.model_copy(update={"wish": wish, "lesson": lesson})})


def stocked(tmp_path, *stories, **kw):
    lib = Library(FolderStorage(tmp_path / "shelf"), run=lambda job: job(), **kw)
    for s in stories:
        d, r = draft_and_request(s)
        lib.add(s, d, r.model_copy(update={"wish": s.meta.wish, "lesson": s.meta.lesson}))
    return lib


def ids(stories):
    return sorted(s.episode.storyId for s in stories)


def test_a_wished_story_is_offered_only_for_the_same_wish(tmp_path):
    lib = stocked(tmp_path, story("a" * 32), wished("b" * 32), wished("c" * 32, wish="바닷속에서 펼쳐지는 구조대 이야기"))
    ask = lambda **f: ids(lib.candidates(LibraryRequest(**body(**f)), random.Random(0)))  # noqa: E731
    assert ask(wish=WISH) == ["b" * 32]
    assert ask(wish="  공룡 섬에서   펼쳐지는 보물찾기 이야기 ") == ["b" * 32]  # spaces as /v1/episode tidies them
    assert ask() == ["a" * 32]  # no wish: only the stories written without one
    assert ask(wish="공룡 섬에서 펼쳐지는 탐정 놀이 이야기") == []


def test_a_wished_story_still_needs_the_same_ages_minutes_friend_and_playable_keys(tmp_path):
    lib = stocked(tmp_path, wished("b" * 32))
    ask = lambda **f: ids(lib.candidates(LibraryRequest(**body(**{"wish": WISH, **f})), random.Random(0)))  # noqa: E731
    assert ask() == ["b" * 32]
    assert ask(ages=["LITTLE"]) == [] and ask(minutes=15) == [] and ask(friend="RIA") == []
    assert ask(playable=[MATH]) == []


def test_a_wished_cell_never_holds_back_for_a_new_story(tmp_path):
    lib = stocked(tmp_path, wished("b" * 32), min_fresh=30, fresh=1.0)
    assert ids(lib.candidates(LibraryRequest(**body(wish=WISH, allowFresh=True)), random.Random(0))) == ["b" * 32]
    lib.add(story("a" * 32), *draft_and_request(story("a" * 32)))
    assert lib.candidates(LibraryRequest(**body(allowFresh=True)), random.Random(0)) == []


def test_a_wished_cell_offers_up_to_thirty(tmp_path):
    lib = stocked(tmp_path, *[wished(f"{i:032x}") for i in range(35)], offer=10)
    assert len(lib.candidates(LibraryRequest(**body(wish=WISH)), random.Random(0))) == 30
    assert lib.size(Cell(FRIEND, "CAPTAIN", 10, wish_key(WISH))) == 35


def test_the_api_answers_with_the_wished_story(tmp_path):
    lib = stocked(tmp_path, wished("b" * 32))
    c = TestClient(create_app(data_dir=tmp_path, painter=None, bank=BANK, library=lib))
    r = c.post("/v1/library", json=body(wish=WISH))
    assert r.status_code == 200 and [s["episode"]["storyId"] for s in r.json()["stories"]] == ["b" * 32]
    assert c.post("/v1/library", json=body(wish="가" * 61)).status_code == 422


def request(lesson, wish=WISH):
    return EpisodeRequest(installId="0123456789abcdef0123", ages=["CAPTAIN"], minutes=10, friend=FRIEND,
                          context={"timeOfDay": "AFTERNOON", "season": "SPRING"}, puzzles=[[MATH], [REASON], ["BELT"]],
                          wish=wish, lesson=lesson)


def test_a_new_story_for_the_same_choices_gets_a_lesson_the_shelf_lacks(tmp_path):
    lib = stocked(tmp_path, wished("b" * 32, lesson="COURAGE"), wished("c" * 32, lesson="CARE"))
    assert lib.fresh_lesson(request("GRIT")).lesson == "GRIT"  # new there: kept
    for seed in range(20):
        assert lib.fresh_lesson(request("COURAGE"), random.Random(seed)).lesson not in ("COURAGE", "CARE")
    # another wish, or no wish, is another cell
    assert lib.fresh_lesson(request("COURAGE", wish="바닷속에서 펼쳐지는 구조대 이야기")).lesson == "COURAGE"
    assert lib.fresh_lesson(request("COURAGE", wish=None)).lesson == "COURAGE"


def test_when_every_lesson_is_taken_the_lesson_stays(tmp_path):
    lib = stocked(tmp_path, *[wished(f"{i:032x}", lesson=lesson) for i, lesson in enumerate(LESSON_LABEL)])
    assert lib.fresh_lesson(request("THANKS")).lesson == "THANKS"


def test_the_writer_is_told_the_lesson_the_shelf_lacks(tmp_path):
    lib = stocked(tmp_path, wished("b" * 32, lesson="COURAGE", friend="BANTE"))
    w = FakeWriter(draft(KEYS))
    c = TestClient(create_app(writer=w, data_dir=tmp_path, painter=None, bank=BANK, library=lib))
    r = c.post("/v1/episode", json={**BODY, "wish": WISH, "lesson": "COURAGE"})
    assert r.status_code == 200
    assert f"오늘의 교훈: {LESSON_LABEL['COURAGE'][0]}" not in w.messages[0] and "오늘의 교훈:" in w.messages[0]


def test_a_wished_story_is_told_the_season_and_keeps_its_own_theme(tmp_path):
    w = FakeWriter(draft(KEYS))
    lib = Library(FolderStorage(tmp_path / "shelf"), run=lambda job: job())
    c = TestClient(create_app(writer=w, data_dir=tmp_path, painter=None, bank=BANK, library=lib))
    c.post("/v1/episode", json={**{k: v for k, v in BODY.items() if k != "theme"}, "wish": WISH})
    assert "- 지금 계절: 가을 (" in w.messages[0] and "theme에는 셋 중 가장 가까운 것을" in w.messages[0]


def test_a_wished_story_survives_a_restart_and_is_found_again(tmp_path):
    lib = Library(FolderStorage(tmp_path / "shelf"), run=lambda job: job())
    lib.check = checked()
    c = TestClient(create_app(writer=FakeWriter(draft(KEYS)), data_dir=tmp_path, painter=None, bank=BANK, library=lib))
    ep = c.post("/v1/episode", json={**BODY, "wish": WISH}).json()
    again = Library(FolderStorage(tmp_path / "shelf"))
    again.load(checked())
    found = again.candidates(LibraryRequest(ages=["CAPTAIN"], minutes=10, friend="BANTE", playable=[MATH, REASON],
                                            wish=WISH), random.Random(0))
    assert ids(found) == [ep["storyId"]] and found[0].meta.wish == WISH


def test_a_story_is_offered_only_in_its_own_season(tmp_path):
    def seasoned(sid, season):
        s = wished(sid)
        return s.model_copy(update={"meta": s.meta.model_copy(update={"season": season})})

    lib = stocked(tmp_path, seasoned("a" * 32, "WINTER"), seasoned("b" * 32, None))
    ask = lambda **f: ids(lib.candidates(LibraryRequest(**body(**{"wish": WISH, **f})), random.Random(0)))  # noqa: E731
    assert ask(season="WINTER") == ["a" * 32, "b" * 32]
    assert ask(season="SUMMER") == ["b" * 32]  # one written without a season suits any
    assert ask() == ["a" * 32, "b" * 32]  # a car that does not say its season takes any
    winter = request("COURAGE").model_copy(update={"context": request("COURAGE").context.model_copy(update={"season": "WINTER"})})
    lib2 = stocked(tmp_path / "2", seasoned("c" * 32, "WINTER").model_copy(
        update={"meta": seasoned("c" * 32, "WINTER").meta.model_copy(update={"lesson": "COURAGE"})}))
    assert lib2.fresh_lesson(winter).lesson != "COURAGE"
    assert lib2.fresh_lesson(request("COURAGE")).lesson == "COURAGE"  # spring: that lesson is not taken there
