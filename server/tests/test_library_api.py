"""POST /v1/library: the candidates a car's rule engine picks from."""
import random
from datetime import date

from fastapi.testclient import TestClient

from app.library import Cell, FolderStorage, Library, Shelved, ShelfMeta
from app.main import create_app
from app.models import Episode, EpisodeRequest, LibraryRequest, SceneText
from app.store import IpLimit
from tests.fakes import AC, BANK, FRIEND, MATH, REASON, filled

ALT = "Q:REASON:AC:0"
STORY_ID = "a" * 32


def story(sid=STORY_ID, puzzles=None, ages=("CAPTAIN",), minutes=10, friend=FRIEND, writer="claude-test"):
    d = filled([MATH, REASON, "BELT"])
    scenes = {s.key: SceneText(bubble=s.bubble, speech=s.speech, hint=s.hint, result=s.result,
                               question=s.question.model_dump() if s.question else None) for s in d.scenes}
    ep = Episode(storyId=sid, model=writer, theme=d.theme, title=d.title, intro=d.intro,
                 treasureBadge=d.treasureBadge, scenes=scenes)
    meta = ShelfMeta(ages=list(ages), minutes=minutes, friend=friend, lesson=None, theme=d.theme, writer=writer,
                     made="2026-11-26", bank=BANK.digest())
    return Shelved(meta=meta, puzzles=puzzles or [[AC, ALT], [MATH], ["BELT"]], episode=ep)


def draft_and_request(s):
    d = filled([MATH, REASON, "BELT"])
    r = EpisodeRequest(installId="0123456789abcdef0123", ages=s.meta.ages, minutes=s.meta.minutes, theme="STAR_TRAIN",
                       context={"timeOfDay": "AFTERNOON", "season": "SPRING"}, puzzles=s.puzzles, friend=s.meta.friend)
    return d, r


def stocked(tmp_path, *stories, **kw):
    lib = Library(FolderStorage(tmp_path / "shelf"), run=lambda job: job(), **kw)
    for s in stories:
        d, r = draft_and_request(s)
        lib.add(s, d, r)
    return lib


def client(tmp_path, lib, **kw):
    return TestClient(create_app(data_dir=tmp_path, painter=None, bank=BANK, library=lib, **kw))


def body(**fields):
    return {"ages": ["CAPTAIN"], "minutes": 10, "friend": FRIEND, "playable": [ALT, MATH], "allowFresh": False,
            **fields}


def offered(tmp_path, lib, **fields):
    r = client(tmp_path, lib).post("/v1/library", json=body(**fields))
    assert r.status_code == 200
    return [s["episode"]["storyId"] for s in r.json()["stories"]]


def test_only_the_same_cell_is_offered(tmp_path):
    lib = stocked(tmp_path, story(), story("b" * 32, friend="RIA"), story("c" * 32, ages=("LITTLE", "CAPTAIN")),
                  story("d" * 32, minutes=15), story("e" * 32, ages=("DETECTIVE",)))
    assert offered(tmp_path, lib) == [STORY_ID]
    assert offered(tmp_path, lib, ages=["CAPTAIN", "LITTLE"]) == ["c" * 32]  # same ages in any order
    assert offered(tmp_path, lib, ages=["LITTLE"]) == []  # same cell, not the same ages


def test_every_slot_needs_a_playable_key(tmp_path):
    lib = stocked(tmp_path, story())
    assert offered(tmp_path, lib, playable=[ALT, MATH]) == [STORY_ID]  # the alternate counts, BELT is always playable
    assert offered(tmp_path, lib, playable=[MATH]) == []


def test_the_answer_carries_the_slots_and_the_episode(tmp_path):
    s = story()
    r = client(tmp_path, stocked(tmp_path, s)).post("/v1/library", json=body())
    assert r.json() == {"stories": [{"puzzles": s.puzzles, "episode": s.episode.model_dump(mode="json")}]}


def test_at_most_ten_are_offered_at_random(tmp_path):
    lib = stocked(tmp_path, *[story(f"{i:032x}") for i in range(12)])
    req = LibraryRequest(**body())
    a = lib.candidates(req, random.Random(1))
    b = lib.candidates(req, random.Random(2))
    assert len(a) == len(b) == 10 and len({s.episode.storyId for s in a}) == 10
    assert [s.episode.storyId for s in a] != [s.episode.storyId for s in b]


def test_a_small_cell_sometimes_asks_for_a_new_story(tmp_path):
    class Rolls(random.Random):
        def __init__(self, value):
            super().__init__(0)
            self.value = value

        def random(self):
            return self.value

    lib = stocked(tmp_path, story(), min_fresh=30, fresh=0.3)
    assert lib.candidates(LibraryRequest(**body(allowFresh=True)), Rolls(0.1)) == []
    assert len(lib.candidates(LibraryRequest(**body(allowFresh=True)), Rolls(0.5))) == 1
    assert len(lib.candidates(LibraryRequest(**body(allowFresh=False)), Rolls(0.1))) == 1


def test_a_big_cell_never_asks_for_a_new_story(tmp_path):
    lib = stocked(tmp_path, story(), min_fresh=1, fresh=1.0)
    assert len(lib.candidates(LibraryRequest(**body(allowFresh=True)), random.Random(0))) == 1


def test_a_model_no_longer_allowed_is_not_offered(tmp_path):
    lib = stocked(tmp_path, story(writer="claude-x"), models=("claude-x",))
    assert offered(tmp_path, lib) == [STORY_ID]
    lib.models = ("claude-y",)
    assert offered(tmp_path, lib) == []


def test_a_blocked_story_is_not_offered_or_counted(tmp_path):
    lib = stocked(tmp_path, story())
    lib.blocked.add(STORY_ID)
    assert offered(tmp_path, lib) == [] and lib.size(Cell(FRIEND, "CAPTAIN", 10)) == 0


def test_without_a_library_the_answer_is_empty(tmp_path):
    r = client(tmp_path, None).post("/v1/library", json=body())
    assert r.status_code == 200 and r.json() == {"stories": []}


def test_an_unknown_key_is_refused(tmp_path):
    r = client(tmp_path, stocked(tmp_path)).post("/v1/library", json=body(playable=["ROOMS:LEFT"]))
    assert r.status_code == 422
    assert client(tmp_path, None).post("/v1/library", json=body(playable=["Q:MATH:NOPE:1"])).status_code == 422
    assert client(tmp_path, None).post("/v1/library", json={**body(), "extra": 1}).status_code == 422


def test_one_address_has_a_daily_limit(tmp_path, monkeypatch):
    monkeypatch.setenv("STORY_LIBRARY_PER_IP", "1")
    c = client(tmp_path, stocked(tmp_path, story()))
    assert c.post("/v1/library", json=body()).status_code == 200
    assert c.post("/v1/library", json=body()).status_code == 429


def test_ip_limit_counts_per_address_and_per_day():
    lim = IpLimit(per_ip=2)
    day = date(2026, 11, 26)
    assert lim.take("1.1.1.1", day) and lim.take("1.1.1.1", day) and not lim.take("1.1.1.1", day)
    assert lim.take("2.2.2.2", day)
    assert lim.take("1.1.1.1", date(2026, 11, 27))


def test_asking_writes_no_record_and_uses_no_story_limit(tmp_path):
    c = client(tmp_path, stocked(tmp_path, story()))
    before = (tmp_path / "daily_limit.json").read_bytes() if (tmp_path / "daily_limit.json").exists() else None
    assert c.post("/v1/library", json=body()).status_code == 200
    assert not (tmp_path / "episodes.jsonl").exists()
    after = (tmp_path / "daily_limit.json").read_bytes() if (tmp_path / "daily_limit.json").exists() else None
    assert before == after
