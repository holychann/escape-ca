"""The story shelf: storage on a folder or an S3 bucket (a fake client here), and the in-memory index."""
import pytest
from botocore.exceptions import ClientError

from app.library import (SHELF_INSTALL, Cell, FolderStorage, Library, S3Storage, Shelved, ShelfMeta, make_library)
from app.models import Episode, EpisodeRequest, SceneText
from tests.fakes import BANK, FRIEND, MATH, REASON, filled

KEYS = [MATH, REASON, "BELT"]
STORY_ID = "a" * 32
INSTALL = "0123456789abcdef0123"


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    for name in ("STORY_LIBRARY_BUCKET", "STORY_LIBRARY_PREFIX", "STORY_LIBRARY_DIR", "STORY_LIBRARY_MODELS",
                 "STORY_LIBRARY_CAP", "STORY_LIBRARY_MIN", "STORY_LIBRARY_FRESH", "STORY_LIBRARY_CANDIDATES",
                 "STORY_LIBRARY_PER_IP", "STORY_LIBRARY_BLOCKS_PER_IP"):
        monkeypatch.delenv(name, raising=False)


class FakeS3:
    def __init__(self):
        self.objects: dict[str, bytes] = {}

    @property
    def keys(self):
        return sorted(self.objects)

    def put_object(self, Bucket, Key, Body, ContentType):
        self.objects[Key] = Body

    def get_object(self, Bucket, Key):
        if Key not in self.objects:
            raise ClientError({"Error": {"Code": "NoSuchKey"}}, "GetObject")
        body = self.objects[Key]

        class Reader:
            def read(self):
                return body
        return {"Body": Reader()}

    def get_paginator(self, name):
        assert name == "list_objects_v2"
        objects = self.objects

        class Paginator:
            def paginate(self, Bucket, Prefix):
                yield {"Contents": [{"Key": k} for k in sorted(objects) if k.startswith(Prefix)]}
        return Paginator()


def request() -> EpisodeRequest:
    return EpisodeRequest(installId=INSTALL, ages=["CAPTAIN"], minutes=10, theme="STAR_TRAIN",
                          context={"timeOfDay": "EVENING", "season": "WINTER"},
                          puzzles=[[MATH], [REASON], ["BELT"]], friend=FRIEND)


def shelved() -> Shelved:
    d = filled(KEYS)
    scenes = {s.key: SceneText(bubble=s.bubble, speech=s.speech, hint=s.hint, result=s.result,
                               question=s.question.model_dump() if s.question else None) for s in d.scenes}
    episode = Episode(storyId=STORY_ID, model="claude-test", theme=d.theme, title=d.title, intro=d.intro,
                      treasureBadge=d.treasureBadge, scenes=scenes)
    meta = ShelfMeta(ages=["CAPTAIN"], minutes=10, friend=FRIEND, lesson=None, theme=d.theme, writer="claude-test",
                     made="2026-11-26", bank=BANK.digest())
    return Shelved(meta=meta, puzzles=request().puzzles, episode=episode)


CELL = Cell("BANTE", "CAPTAIN", 10)


def test_folder_storage_round_trip(tmp_path):
    s = FolderStorage(tmp_path)
    s.put("library/a/b.json", b"{}", "application/json")
    assert s.get("library/a/b.json") == b"{}" and s.get("library/a/none.json") is None
    assert s.list("library/") == ["library/a/b.json"]


def test_s3_storage_keeps_everything_under_its_prefix():
    c = FakeS3()
    s = S3Storage("bucket", "pre", client=c)
    s.put("library/x.json", b"1", "application/json")
    assert c.keys == ["pre/library/x.json"] and s.get("library/x.json") == b"1"
    assert s.get("library/none.json") is None and s.list("library/") == ["library/x.json"]


def test_s3_storage_raises_other_errors():
    class Broken(FakeS3):
        def get_object(self, Bucket, Key):
            raise ClientError({"Error": {"Code": "AccessDenied"}}, "GetObject")
    with pytest.raises(ClientError):
        S3Storage("bucket", client=Broken()).get("library/x.json")


def test_the_bank_digest_follows_its_questions():
    assert BANK.digest() == BANK.digest() and len(BANK.digest()) == 16
    assert BANK.digest() != type(BANK)(BANK.entries[:-1]).digest()


def test_a_shelved_story_is_laid_out_by_cell(tmp_path):
    Library(FolderStorage(tmp_path)).add(shelved(), filled(KEYS), request())
    base = tmp_path / "library/BANTE/CAPTAIN/10m" / STORY_ID
    assert {p.name for p in base.iterdir()} == {"story.json", "draft.json"}
    assert INSTALL not in (base / "draft.json").read_text()


def test_a_new_library_reads_back_what_was_shelved(tmp_path):
    Library(FolderStorage(tmp_path)).add(shelved(), filled(KEYS), request())
    again = Library(FolderStorage(tmp_path))
    again.load()
    assert again.get(STORY_ID) == shelved() and again.size(CELL) == 1


def test_the_stored_request_gets_the_shelf_install_back(tmp_path):
    Library(FolderStorage(tmp_path)).add(shelved(), filled(KEYS), request())
    seen = []
    again = Library(FolderStorage(tmp_path))
    again.load(lambda sh, draft, req: seen.append((draft, req)) or [])
    assert seen[0][0] == filled(KEYS) and seen[0][1] == request().model_copy(update={"installId": SHELF_INSTALL})


def test_a_half_written_story_is_skipped(tmp_path):
    Library(FolderStorage(tmp_path)).add(shelved(), filled(KEYS), request())
    base = tmp_path / "library/BANTE/CAPTAIN/10m"
    (base / STORY_ID / "story.json").rename(base / "other-story.json")
    (base / "draft-only").mkdir()
    (base / "draft-only" / "draft.json").write_text("{}")
    (base / "story-only").mkdir()
    (base / "story-only" / "story.json").write_text((base / "other-story.json").read_text())
    (base / "broken").mkdir()
    (base / "broken" / "story.json").write_text("not json")
    (base / "broken" / "draft.json").write_text("{}")
    again = Library(FolderStorage(tmp_path))
    again.load()
    assert again.size(CELL) == 0


def test_a_story_from_a_model_that_is_not_allowed_is_skipped(tmp_path):
    Library(FolderStorage(tmp_path)).add(shelved(), filled(KEYS), request())
    again = Library(FolderStorage(tmp_path), models=("gemini",))
    again.load()
    assert again.size(CELL) == 0


def test_a_story_the_check_finds_fault_with_is_skipped(tmp_path):
    Library(FolderStorage(tmp_path)).add(shelved(), filled(KEYS), request())
    again = Library(FolderStorage(tmp_path))
    again.load(lambda sh, draft, req: ["bad"])
    assert again.size(CELL) == 0 and again.get(STORY_ID) is None


def test_only_allowed_models_pass(tmp_path):
    lib = Library(FolderStorage(tmp_path), models=("claude",))
    assert lib.allows("claude-opus-5-5") and not lib.allows("gemini-3.6-flash")
    assert Library(FolderStorage(tmp_path)).allows("gemini-3.6-flash")


def test_without_settings_there_is_no_library(monkeypatch, tmp_path):
    assert make_library() is None
    monkeypatch.setenv("STORY_LIBRARY_DIR", str(tmp_path))
    lib = make_library()
    assert isinstance(lib, Library) and lib.allows("claude-opus-5-5") and not lib.allows("gemini-3.6-flash")


def test_the_models_setting_can_be_empty(monkeypatch, tmp_path):
    monkeypatch.setenv("STORY_LIBRARY_DIR", str(tmp_path))
    monkeypatch.setenv("STORY_LIBRARY_MODELS", "")
    assert make_library().allows("gemini-3.6-flash")


def test_a_bucket_makes_an_s3_library(monkeypatch):
    monkeypatch.setenv("STORY_LIBRARY_BUCKET", "b")
    monkeypatch.setenv("STORY_LIBRARY_PREFIX", "p")
    monkeypatch.setattr(S3Storage, "list", lambda self, prefix: [])
    monkeypatch.setattr("boto3.client", lambda *a, **k: FakeS3())
    assert isinstance(make_library(), Library)


def test_a_library_that_cannot_load_is_turned_off(monkeypatch, tmp_path):
    monkeypatch.setenv("STORY_LIBRARY_DIR", str(tmp_path))

    def fail(self, prefix):
        raise OSError("down")
    monkeypatch.setattr(FolderStorage, "list", fail)
    assert make_library() is None
