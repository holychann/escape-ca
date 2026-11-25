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


# ---------------------------------------------------------------- cache, quota, style kept out of the words

from datetime import date

from app.service import read_aloud
from app.voice import Quota, QuotaError


class QuotaVoice(FakeVoice):
    """Says no with the quota after [ok] lines."""

    def __init__(self, ok: int):
        super().__init__()
        self.ok = ok

    def speak(self, text: str) -> bytes:
        if len(self.calls) >= self.ok:
            self.calls.append(text)
            raise QuotaError("429 RESOURCE_EXHAUSTED")
        return super().speak(text)


def test_a_line_said_again_is_not_made_again(tmp_path):
    v = FakeVoice()
    c = TestClient(create_app(writer=FakeWriter(draft(KEYS), draft(KEYS)), data_dir=tmp_path, painter=None, voice=v))
    first = c.post("/v1/episode", json=BODY).json()
    made = len(v.calls)
    second = c.post("/v1/episode", json=BODY).json()
    # the second story has the same words: every line comes from the cache, none is read again
    assert len(v.calls) == made
    assert set(second["voices"]) == LINES and first["storyId"] != second["storyId"]
    assert c.get(second["voices"]["success0"]).content == c.get(first["voices"]["success0"]).content


def test_the_same_words_twice_in_one_story_are_read_once(tmp_path):
    ep = client(tmp_path, FakeVoice()).post("/v1/episode", json=BODY).json()
    ep["scenes"]["FOG"]["hint"] = ep["scenes"]["ROOMS:LEFT"]["hint"]
    from app.models import Episode
    v = FakeVoice()
    out = read_aloud(Episode.model_validate({**ep, "voices": {}}), v, tmp_path / "a", cache=tmp_path / "c")
    assert len(v.calls) == len(LINES) - 1 and set(out.voices) == LINES


def test_a_used_up_quota_stops_the_calls_and_the_story_still_plays(tmp_path):
    v = QuotaVoice(ok=0)
    r = TestClient(create_app(writer=FakeWriter(draft(KEYS)), data_dir=tmp_path, painter=None, voice=v)).post("/v1/episode", json=BODY)
    # no recordings: the car reads every line with its own voice
    assert r.status_code == 200 and r.json()["voices"] == {} and r.json()["scenes"]
    # after the first 429 the remaining lines are not tried (only the calls already on their way)
    assert len(v.calls) <= 8 < len(LINES)


def test_the_quota_guard():
    now = [0.0]
    day = [date(2026, 11, 26)]
    q = Quota(per_day=2, cooldown=60, today=lambda: day[0], clock=lambda: now[0])
    q.take(); q.take()
    try:
        q.take()
        raise AssertionError("third call let out")
    except QuotaError:
        pass
    day[0] = date(2026, 11, 27)
    q.take()
    # a per-minute 429 waits a minute; a daily one waits for tomorrow
    q.refused(Exception("429 RESOURCE_EXHAUSTED. GenerateRequestsPerMinutePerProjectPerModel-FreeTier"))
    for blocked in (True, False):
        try:
            q.take()
            assert not blocked
        except QuotaError:
            assert blocked
        now[0] += 61
    q.refused(Exception("429 RESOURCE_EXHAUSTED. GenerateRequestsPerDayPerProjectPerModel-FreeTier"))
    now[0] += 3600
    try:
        q.take()
        raise AssertionError("daily limit ignored")
    except QuotaError:
        pass


class _Refusing:
    def __init__(self):
        self.calls = 0

    def generate_content(self, **kw):
        self.calls += 1
        e = Exception("429 RESOURCE_EXHAUSTED. Quota exceeded: GenerateRequestsPerDayPerProjectPerModel-FreeTier")
        e.code = 429
        raise e


def test_a_429_from_the_model_closes_the_quota_without_more_calls():
    models = _Refusing()
    v = GeminiVoice(model="m", voice="Sira", style="S", client=_Obj(models=models), quota=Quota(per_day=100))
    for _ in range(3):
        try:
            v.speak("안녕")
            raise AssertionError("no error")
        except QuotaError:
            pass
    assert models.calls == 1


def test_the_style_is_never_part_of_the_words(monkeypatch):
    style = "Read in Korean like a warm, playful storyteller reading a picture book to a 5-year-old."
    monkeypatch.setenv("TTS_STYLE", style)
    monkeypatch.setenv("TTS_VOICE", "Sira")
    line = "수달 요리사가 큰일이래! 밖에서 먼지 구름이 몰려와."
    v = GeminiVoice(model="m", client=_Obj(models=_Models(b"")))
    part = v.request(line)["contents"][0]["parts"][0]
    assert part["text"] == line and style not in part["text"]
    assert part["speech_metadata"] == {"style": style}
    assert v.request(line)["config"]["speech_config"]["voice_config"]["voice"] == "Sira"
    # a different style or voice is a different recording
    assert v.identity != GeminiVoice(model="m", style="other", client=_Obj(models=None)).identity
