"""The cover first: the episode is sent once its cover is in, the other pictures follow, each is drawn once more when
it fails, and a story goes on the shelf only with all of them."""
import threading

import pytest
from fastapi.testclient import TestClient

import app.main
import app.pictures
from app.library import Cell, FolderStorage, Library
from app.main import create_app
from tests.fakes import BANK, FakeWriter, draft
from tests.test_wish_and_pictures import ART, BODY, KEYS, FakePainter

CELL = Cell("BANTE", "CAPTAIN", 10)
# with a shelf, a story without a wish gets a random theme; the fake drafts are STAR_TRAIN
SHELF_BODY = {**BODY, "theme": "STAR_TRAIN", "pictures": True}


class GatedPainter(FakePainter):
    """Draws the cover at once and holds every other picture until ``gate`` opens; ``fail_once`` and ``fail`` name
    descriptions that fail the first time, or every time."""

    def __init__(self, fail_once: set[str] = frozenset(), fail: set[str] = frozenset()):
        super().__init__(fail=fail)
        self.gate = threading.Event()
        self.fail_once = set(fail_once)

    def paint(self, description: str) -> bytes:
        if description != ART.cover:
            assert self.gate.wait(10)
        hit = next((f for f in self.fail_once if f in description), None)
        if hit:
            self.fail_once.discard(hit)
            self.calls.append(description)
            raise RuntimeError("refused once")
        return super().paint(description)


@pytest.fixture
def later(monkeypatch):
    """Draws each episode's pictures on a thread of their own, as the server does; ``later.wait()`` waits for them."""
    threads: list[threading.Thread] = []

    def run(job):
        t = threading.Thread(target=job)
        threads.append(t)
        t.start()

    monkeypatch.setattr(app.main, "_run_pictures", run)
    run.wait = lambda: [t.join(10) for t in threads]
    return run


def client(tmp_path, painter, library=None):
    w = FakeWriter(draft(KEYS, art=ART))
    return TestClient(create_app(writer=w, data_dir=tmp_path, painter=painter, bank=BANK, library=library))


def test_the_episode_comes_with_its_cover_and_the_rest_follow(tmp_path, later):
    painter = GatedPainter()
    c = client(tmp_path, painter)
    ep = c.post("/v1/episode", json={**BODY, "pictures": True}).json()
    # every picture is named at once, so the car knows what to ask for
    assert set(ep["images"]) == {"cover", "p1", "p2", "p3"} and ep["scenes"]["BELT"]["picture"] == "p3"
    assert c.get(ep["images"]["cover"]).status_code == 200
    waiting = c.get(ep["images"]["p1"])
    assert waiting.status_code == 202 and waiting.headers["retry-after"] == "3"
    painter.gate.set()
    later.wait()
    assert all(c.get(path).status_code == 200 for path in ep["images"].values())


def test_a_picture_that_fails_once_is_drawn_again(tmp_path, later):
    painter = GatedPainter(fail_once={"모래 언덕"})
    c = client(tmp_path, painter)
    ep = c.post("/v1/episode", json={**BODY, "pictures": True}).json()
    painter.gate.set()
    later.wait()
    assert c.get(ep["images"]["p2"]).status_code == 200
    assert sum("모래 언덕" in call for call in painter.calls) == 2


def test_a_picture_that_fails_twice_is_not_there_and_keeps_the_story_off_the_shelf(tmp_path, later):
    painter = GatedPainter(fail={"모래 언덕"})
    lib = Library(FolderStorage(tmp_path / "shelf"), run=lambda job: job())
    c = client(tmp_path, painter, lib)
    ep = c.post("/v1/episode", json=SHELF_BODY).json()
    painter.gate.set()
    later.wait()
    assert sum("모래 언덕" in call for call in painter.calls) == 2
    assert c.get(ep["images"]["p2"]).status_code == 404  # the car shows its own picture for that scene
    assert c.get(ep["images"]["p1"]).status_code == 200
    assert lib.size(CELL) == 0


def test_a_story_is_shelved_only_once_every_picture_is_in(tmp_path, later):
    painter = GatedPainter()
    lib = Library(FolderStorage(tmp_path / "shelf"), run=lambda job: job())
    c = client(tmp_path, painter, lib)
    ep = c.post("/v1/episode", json=SHELF_BODY).json()
    assert lib.size(CELL) == 0  # pictures still coming
    painter.gate.set()
    later.wait()
    shelved = lib.get(ep["storyId"])
    assert shelved is not None and set(shelved.episode.images) == {"cover", "p1", "p2", "p3"}
    folder = tmp_path / "shelf" / CELL.path() / ep["storyId"] / "img"
    assert {p.stem for p in folder.iterdir()} == {"cover", "p1", "p2", "p3"}


def test_a_cover_that_fails_twice_leaves_it_out_and_the_rest_go_on(tmp_path, later):
    painter = GatedPainter(fail={ART.cover})
    c = client(tmp_path, painter)
    ep = c.post("/v1/episode", json={**BODY, "pictures": True}).json()
    assert "cover" not in ep["images"] and "p1" in ep["images"]
    painter.gate.set()
    later.wait()
    assert c.get(ep["images"]["p1"]).status_code == 200


def test_a_late_cover_sends_the_episode_without_it(tmp_path, monkeypatch, later):
    monkeypatch.setattr(app.pictures, "COVER_WAIT_SECONDS", 0.2)

    class SlowCover(GatedPainter):
        def paint(self, description):
            if description == ART.cover:
                assert self.gate.wait(10)
            return super().paint(description)

    painter = SlowCover()
    c = client(tmp_path, painter)
    ep = c.post("/v1/episode", json={**BODY, "pictures": True}).json()
    assert "cover" not in ep["images"] and "p1" in ep["images"]
    painter.gate.set()
    later.wait()
