"""A story written for the shelf is put there after the response, and only when it may be."""
import json
import shutil

from fastapi.testclient import TestClient

from app.library import Cell, FolderStorage, Library, wish_key
from app.main import create_app
from app.models import Episode
from app.writer import WriterError
from tests.fakes import BANK, MATH, REASON, FakeWriter, draft, leaky

# the checker drops a draft whose theme differs from the request's, and the fake drafts are STAR_TRAIN
BODY = {
    "installId": "0123456789abcdef0123",
    "ages": ["CAPTAIN"],
    "minutes": 10,
    "theme": "STAR_TRAIN",
    "context": {"timeOfDay": "AFTERNOON", "season": "AUTUMN"},
    "puzzles": [[MATH], [REASON], ["BELT"]],
    "friend": "BANTE",
}
KEYS = [MATH, REASON, "BELT"]
CELL = Cell("BANTE", "CAPTAIN", 10)


def shelf(tmp_path, **kw):
    return Library(FolderStorage(tmp_path / "shelf"), run=lambda job: job(), **kw)


def client(tmp_path, writer, library):
    return TestClient(create_app(writer=writer, data_dir=tmp_path, painter=None, bank=BANK, library=library))


def test_a_story_without_a_wish_is_shelved(tmp_path):
    lib = shelf(tmp_path)
    ep = client(tmp_path, FakeWriter(draft(KEYS)), lib).post("/v1/episode", json=BODY).json()
    s = lib.get(ep["storyId"])
    assert s.episode == Episode(**ep) and s.puzzles == BODY["puzzles"] and s.meta.writer == "fake-model"
    assert ep["images"] == {}


def test_the_writer_is_told_the_season_but_not_the_time_when_the_story_is_for_the_shelf(tmp_path):
    w = FakeWriter(draft(KEYS))
    client(tmp_path, w, shelf(tmp_path)).post("/v1/episode", json=BODY)
    assert "- 지금 계절: 가을 (" in w.messages[0] and "가을 낮" not in w.messages[0]


def test_without_a_library_the_writer_is_told_the_season_but_not_the_time(tmp_path):
    w = FakeWriter(draft(KEYS))
    client(tmp_path, w, None).post("/v1/episode", json=BODY)
    assert "- 지금 계절: 가을" in w.messages[0] and "가을 낮" not in w.messages[0]


def test_a_wished_story_is_shelved_in_its_own_cell(tmp_path):
    lib = shelf(tmp_path)
    wish = "공룡 섬에서 펼쳐지는 보물찾기 이야기"
    r = client(tmp_path, FakeWriter(draft(KEYS)), lib).post("/v1/episode", json={**BODY, "wish": wish})
    assert r.status_code == 200 and lib.size(CELL) == 0
    assert lib.size(Cell("BANTE", "CAPTAIN", 10, wish_key(wish))) == 1
    assert lib.get(r.json()["storyId"]).meta.wish == wish


def test_a_story_by_a_model_not_allowed_is_not_shelved(tmp_path):
    lib = shelf(tmp_path, models=("claude",))
    assert client(tmp_path, FakeWriter(draft(KEYS)), lib).post("/v1/episode", json=BODY).status_code == 200
    assert lib.size(CELL) == 0


def test_a_story_with_a_dropped_scene_is_not_shelved(tmp_path):
    lib = shelf(tmp_path)
    w = FakeWriter(leaky(), leaky(), WriterError("stop_reason=refusal"))
    ep = client(tmp_path, w, lib).post("/v1/episode", json=BODY).json()
    assert ep["dropped"] == [MATH] and lib.size(CELL) == 0


def test_a_story_with_time_words_is_not_shelved(tmp_path):
    lib = shelf(tmp_path)
    d = draft(KEYS, intro="안녕, 탐험대장! 나는 반테야. 별이 빛나는 밤길을 같이 가 보자!")
    assert client(tmp_path, FakeWriter(d), lib).post("/v1/episode", json=BODY).status_code == 200
    assert lib.size(CELL) == 0


def test_a_story_with_season_words_is_shelved_with_its_season(tmp_path):
    lib = shelf(tmp_path)
    d = draft(KEYS, intro="안녕, 탐험대장! 나는 반테야. 단풍이 든 길을 같이 가 보자!")
    ep = client(tmp_path, FakeWriter(d), lib).post("/v1/episode", json=BODY).json()
    assert lib.size(CELL) == 1 and lib.get(ep["storyId"]).meta.season == "AUTUMN"


def test_a_full_cell_takes_no_more(tmp_path):
    lib = shelf(tmp_path, cap=1)
    c = client(tmp_path, FakeWriter(draft(KEYS), draft(KEYS)), lib)
    assert c.post("/v1/episode", json=BODY).status_code == 200
    assert c.post("/v1/episode", json=BODY).status_code == 200
    assert lib.size(CELL) == 1


def test_the_shelved_draft_has_the_repaired_scene(tmp_path):
    lib = shelf(tmp_path)
    fixed = draft([MATH])
    fixed.scenes[0].speech = "발명품 바람개비가 멈췄어. 바람 세기를 알아내면 다시 돌릴 수 있어!"
    ep = client(tmp_path, FakeWriter(leaky(), leaky(), fixed), lib).post("/v1/episode", json=BODY).json()
    assert ep["dropped"] == []
    body = json.loads((tmp_path / "shelf" / CELL.path() / ep["storyId"] / "draft.json").read_text(encoding="utf-8"))
    scene = next(s for s in body["draft"]["scenes"] if s["key"] == MATH)
    assert scene["speech"] == fixed.scenes[0].speech and scene["question"]["text"] == ep["scenes"][MATH]["question"]["text"]
    assert "wish" not in body["request"] and "installId" not in body["request"]


def test_a_storage_error_does_not_reach_the_family(tmp_path):
    class Broken(FolderStorage):
        def put(self, path, data, content_type):
            raise OSError("disk full")

    lib = Library(Broken(tmp_path / "shelf"), run=lambda job: job())
    r = client(tmp_path, FakeWriter(draft(KEYS)), lib).post("/v1/episode", json=BODY)
    assert r.status_code == 200 and r.json()["scenes"]
    assert lib.size(CELL) == 0


def test_a_painted_story_is_shelved_with_its_pictures(tmp_path):
    from tests.test_wish_and_pictures import ART, FakePainter
    lib = shelf(tmp_path)
    d = draft(KEYS, art=ART)
    c = TestClient(create_app(writer=FakeWriter(d), data_dir=tmp_path, painter=FakePainter(), bank=BANK, library=lib))
    ep = c.post("/v1/episode", json={**BODY, "pictures": True}).json()
    assert ep["images"] and any(s["picture"] for s in ep["scenes"].values())
    s = lib.get(ep["storyId"])
    assert s.episode == Episode(**ep)
    folder = tmp_path / "shelf" / CELL.path() / ep["storyId"] / "img"
    assert {p.stem for p in folder.iterdir()} == set(ep["images"])
    # once the day-long copy is gone, the shelf serves its own
    shutil.rmtree(tmp_path / "images" / ep["storyId"])
    for name, path in ep["images"].items():
        r = c.get(path)
        assert r.status_code == 200 and r.content == (folder / f"{name}.webp").read_bytes()
    assert c.get(f"/v1/images/{ep['storyId']}/p6.webp").status_code == 404


def test_a_picture_gone_before_shelving_is_left_out_of_the_shelved_story(tmp_path):
    from tests.test_wish_and_pictures import ART, FakePainter
    jobs = []
    lib = Library(FolderStorage(tmp_path / "shelf"), run=jobs.append)
    c = TestClient(create_app(writer=FakeWriter(draft(KEYS, art=ART)), data_dir=tmp_path, painter=FakePainter(),
                              bank=BANK, library=lib))
    ep = c.post("/v1/episode", json={**BODY, "pictures": True}).json()
    (tmp_path / "images" / ep["storyId"] / "p1.webp").unlink()
    jobs.pop()()
    s = lib.get(ep["storyId"])
    assert "p1" not in s.episode.images and "p1" not in s.episode.pictures.values()
    assert all(sc.picture != "p1" for sc in s.episode.scenes.values())
    assert set(s.episode.images) == set(ep["images"]) - {"p1"}


def test_a_shelf_that_cannot_queue_does_not_reach_the_family(tmp_path):
    def broken(job):
        raise RuntimeError("executor shut down")

    lib = Library(FolderStorage(tmp_path / "shelf"), run=broken)
    r = client(tmp_path, FakeWriter(draft(KEYS)), lib).post("/v1/episode", json=BODY)
    assert r.status_code == 200 and r.json()["scenes"] and lib.size(CELL) == 0


def long_result():
    d = draft(KEYS)
    d.scenes[1].result = "유리가 맑아지고 있어! " * 30
    return d


def checked(bank=BANK):
    from app.library import recheck
    return lambda s, d, r: recheck(s, d, r, bank)


def test_a_story_the_restart_check_would_drop_is_not_shelved(tmp_path):
    lib = shelf(tmp_path)
    lib.check = checked()
    ep = client(tmp_path, FakeWriter(long_result()), lib).post("/v1/episode", json=BODY).json()
    assert ep["scenes"][REASON]["result"] == "" and lib.size(CELL) == 0
    assert not (tmp_path / "shelf" / CELL.path() / ep["storyId"]).exists()


def test_a_story_that_passes_the_check_is_shelved_and_survives_a_restart(tmp_path):
    lib = shelf(tmp_path)
    lib.check = checked()
    ep = client(tmp_path, FakeWriter(draft(KEYS)), lib).post("/v1/episode", json=BODY).json()
    assert lib.size(CELL) == 1
    again = Library(FolderStorage(tmp_path / "shelf"))
    again.load(checked())
    assert again.size(CELL) == 1 and again.get(ep["storyId"]) is not None


def test_the_shelf_made_by_make_library_checks_before_shelving(monkeypatch, tmp_path):
    from app.library import make_library
    monkeypatch.setenv("STORY_LIBRARY_DIR", str(tmp_path / "shelf"))
    monkeypatch.setenv("STORY_LIBRARY_MODELS", "")
    lib = make_library(checked())
    ep = client(tmp_path, FakeWriter(long_result()), lib).post("/v1/episode", json=BODY).json()
    lib.drain(5)
    assert lib.size(CELL) == 0 and ep["scenes"]
    ok = client(tmp_path, FakeWriter(draft(KEYS)), lib).post("/v1/episode", json=BODY).json()
    lib.drain(5)
    assert lib.size(CELL) == 1 and lib.get(ok["storyId"]) is not None

def test_a_story_whose_pictures_all_failed_is_not_shelved(tmp_path):
    from tests.test_wish_and_pictures import ART

    class Refusing:
        def paint(self, text):
            raise RuntimeError("refused")

    lib = shelf(tmp_path)
    c = TestClient(create_app(writer=FakeWriter(draft(KEYS, art=ART)), data_dir=tmp_path, painter=Refusing(), bank=BANK,
                              library=lib))
    ep = c.post("/v1/episode", json={**BODY, "pictures": True}).json()
    assert ep["images"] == {} and ep["scenes"] and lib.size(CELL) == 0


def test_a_story_without_pictures_asked_is_shelved_without_them(tmp_path):
    from tests.test_wish_and_pictures import ART, FakePainter
    lib = shelf(tmp_path)
    c = TestClient(create_app(writer=FakeWriter(draft(KEYS, art=ART)), data_dir=tmp_path, painter=FakePainter(),
                              bank=BANK, library=lib))
    ep = c.post("/v1/episode", json=BODY).json()
    assert ep["images"] == {} and lib.size(CELL) == 1
