"""A shelved story keeps its lines read aloud next to it, so a car playing it weeks later gets them without a new
recording, even after the voice cache has dropped them."""
import shutil

from fastapi.testclient import TestClient

from app.library import FolderStorage, Library
from app.main import create_app
from app.service import tidy_line
from app.voice import cache_key
from tests.fakes import BANK, FakeWriter
from tests.test_library_api import draft_and_request, stocked, story
from tests.test_voice import INSTALL, FakeVoice

ASK = " 어떻게 하면 되는지 안전 지킴이에게 말해 줘!"


def client(tmp_path, lib, voice):
    return TestClient(create_app(writer=FakeWriter(), data_dir=tmp_path / "data", painter=None, bank=BANK,
                                 voice=voice, library=lib))


def say(c, text):
    return c.post("/v1/speak", json={"installId": INSTALL, "text": text})


def voice_files(tmp_path):
    return sorted(p.name for p in (tmp_path / "shelf").rglob("voice/*.wav"))


def test_a_line_of_a_shelved_story_is_kept_with_it(tmp_path):
    s = story()
    lib = stocked(tmp_path, s)
    v = FakeVoice()
    r = say(client(tmp_path, lib, v), s.episode.intro)
    assert r.status_code == 200
    key = cache_key(v, tidy_line(s.episode.intro))
    assert voice_files(tmp_path) == [f"{key}.wav"] and lib.voice(key) == r.content


def test_a_line_with_the_apps_own_words_around_a_story_line_is_kept_too(tmp_path):
    s = story()
    lib = stocked(tmp_path, s)
    scene = next(sc for sc in s.episode.scenes.values() if sc.speech)
    assert say(client(tmp_path, lib, FakeVoice()), scene.speech + ASK).status_code == 200
    assert len(voice_files(tmp_path)) == 1


def test_a_line_of_no_shelved_story_is_not_kept(tmp_path):
    lib = stocked(tmp_path, story())
    assert say(client(tmp_path, lib, FakeVoice()), "잠깐만! 다음 장면을 그리고 있어.").status_code == 200
    assert voice_files(tmp_path) == []


def test_a_kept_line_is_served_from_the_shelf_after_a_restart_without_the_cache(tmp_path):
    s = story()
    say(client(tmp_path, stocked(tmp_path, s), FakeVoice()), s.episode.intro)
    shutil.rmtree(tmp_path / "data" / "audio_cache")
    again = Library(FolderStorage(tmp_path / "shelf"), run=lambda job: job())
    again.load()
    v = FakeVoice()
    r = say(client(tmp_path, again, v), s.episode.intro)
    assert r.status_code == 200 and r.content[:4] == b"RIFF" and v.calls == []


def test_lines_read_before_the_story_is_shelved_are_kept_when_it_is(tmp_path):
    s = story()
    lib = Library(FolderStorage(tmp_path / "shelf"), run=lambda job: job())
    made = {}
    lib.recording = made.get
    lib.expect(s.episode)
    key = cache_key(None, tidy_line(s.episode.intro))
    made[key] = b"RIFF-intro"
    lib.keep_voice(s.episode.intro, key, made[key])
    assert voice_files(tmp_path) == []
    d, r = draft_and_request(s)
    lib.add(s, d, r)
    assert voice_files(tmp_path) == [f"{key}.wav"] and lib.voice(key) == b"RIFF-intro"


def test_a_story_keeps_one_recording_per_line(tmp_path):
    s = story()
    lib = stocked(tmp_path, s)
    c = client(tmp_path, lib, FakeVoice())
    say(c, s.episode.intro)
    say(c, "  " + s.episode.intro + " ")
    assert len(voice_files(tmp_path)) == 1
