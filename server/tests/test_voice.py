"""Lumi's lines read aloud: every line is recorded and served, a failed line is left out, nothing without a voice."""
import io
import wave

from fastapi.testclient import TestClient

from app.main import create_app
from app.voice import GeminiVoice, to_wav
from tests.fakes import FakeWriter, draft

BODY = {
    "installId": "0123456789abcdef0123",
    "ages": ["CAPTAIN"],
    "minutes": 10,
    "context": {"timeOfDay": "AFTERNOON", "season": "AUTUMN"},
    "puzzles": [["ROOMS:LEFT", "ROOMS:RIGHT"], ["FOG"], ["BELT"]],
}
KEYS = ["ROOMS:LEFT", "ROOMS:RIGHT", "FOG", "BELT"]
LINES = {"intro", "success0", "success1", "success2", "treasureGift", "treasureBadge"} | {
    f"{k}.{part}" for k in KEYS for part in ("speech", "hint")
}


def pcm(text: str) -> bytes:
    return b"\x00\x01" * (10 + len(text))


class FakeVoice:
    def __init__(self, fail: set[str] = frozenset()):
        self.calls: list[str] = []
        self.fail = fail

    def speak(self, text: str) -> bytes:
        self.calls.append(text)
        if any(f in text for f in self.fail):
            raise RuntimeError("refused")
        return to_wav(pcm(text))


def client(tmp_path, voice):
    return TestClient(create_app(writer=FakeWriter(draft(KEYS)), data_dir=tmp_path, painter=None, voice=voice))


def test_every_line_is_read_aloud_and_served(tmp_path):
    v = FakeVoice()
    c = client(tmp_path, v)
    ep = c.post("/v1/episode", json=BODY).json()
    assert set(ep["voices"]) == LINES
    assert len(v.calls) == len(LINES)
    sid = ep["storyId"]
    # ':' in the answer keys does not go into file names
    assert ep["voices"]["ROOMS:LEFT.speech"] == f"/v1/audio/{sid}/ROOMS_LEFT.speech.wav"
    for path in ep["voices"].values():
        r = c.get(path)
        assert r.status_code == 200 and r.headers["content-type"] == "audio/wav"
        assert r.content[:4] == b"RIFF"
    assert c.get("/health").json()["voice"] is True


def test_a_line_that_fails_is_left_out(tmp_path):
    ep = client(tmp_path, FakeVoice(fail={"발자국이 오른쪽"})).post("/v1/episode", json=BODY).json()
    assert "ROOMS:RIGHT.speech" not in ep["voices"]
    assert set(ep["voices"]) == LINES - {"ROOMS:RIGHT.speech"}


def test_without_a_voice_there_are_no_recordings(tmp_path):
    c = client(tmp_path, None)
    assert c.post("/v1/episode", json=BODY).json()["voices"] == {}
    assert c.get("/health").json()["voice"] is False


def test_bad_names_are_not_found(tmp_path):
    c = client(tmp_path, FakeVoice())
    sid = c.post("/v1/episode", json=BODY).json()["storyId"]
    assert c.get(f"/v1/audio/{sid}/intro.wav").status_code == 200
    for bad in [f"/v1/audio/{sid}/..intro.wav", f"/v1/audio/{sid}/a..b.wav", f"/v1/audio/{sid}/in%20tro.wav",
                f"/v1/audio/{sid}/{'a' * 65}.wav", "/v1/audio/not-a-story/intro.wav", f"/v1/audio/{sid}/missing.wav"]:
        assert c.get(bad).status_code == 404, bad


def test_pcm_gets_a_header_and_wav_is_kept():
    raw = pcm("가나다")
    wav = to_wav(raw)
    with wave.open(io.BytesIO(wav)) as w:
        assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, 24_000)
        assert w.readframes(w.getnframes()) == raw
    assert to_wav(wav) == wav


class _Obj:
    def __init__(self, **kw):
        self.__dict__.update(kw)


class _Models:
    def __init__(self, data: bytes):
        self.data = data
        self.sent = None

    def generate_content(self, **kw):
        self.sent = kw
        part = _Obj(inline_data=_Obj(data=self.data))
        return _Obj(candidates=[_Obj(content=_Obj(parts=[part]))], prompt_feedback=None)


def test_gemini_voice_asks_for_audio_in_the_storyteller_style():
    models = _Models(pcm("안녕"))
    v = GeminiVoice(model="m", voice="Leda", style="S", client=_Obj(models=models))
    assert v.speak("안녕")[:4] == b"RIFF"
    assert models.sent["contents"][0]["parts"][0] == {"text": "안녕", "speech_metadata": {"style": "S"}}
    assert models.sent["config"]["response_modalities"] == ["AUDIO"]
    assert models.sent["config"]["speech_config"]["voice_config"]["voice"] == "Leda"
