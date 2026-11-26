"""Lumi's lines read aloud on POST /v1/speak: made once and kept, free key first, then the paid key once, and no
sound (a caption in the app) when neither works. No test calls a real model."""
import io
import logging
import wave
from datetime import date

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.voice import GeminiVoice, KeyedVoice, Quota, QuotaError, make_voice, to_wav
from tests.fakes import BANK, FakeWriter

INSTALL = "0123456789abcdef0123"
LINE = "수달 요리사가 큰일이래! 3 더하기 2만큼 바람을 세게 해 줘! 어떻게 하면 되는지 안전 지킴이에게 말해 줘!"


def pcm(text: str) -> bytes:
    return b"\x00\x01" * (10 + len(text))


class FakeVoice:
    def __init__(self, fail: Exception | None = None, identity: str = "fake|voice|style"):
        self.calls: list[str] = []
        self.fail = fail
        self.identity = identity

    def speak(self, text: str) -> bytes:
        self.calls.append(text)
        if self.fail is not None:
            raise self.fail
        return to_wav(pcm(text))


def client(tmp_path, voice):
    return TestClient(create_app(writer=FakeWriter(), data_dir=tmp_path, painter=None, bank=BANK, voice=voice))


def say(c, text=LINE, install=INSTALL):
    return c.post("/v1/speak", json={"installId": install, "text": text})


# ---------------------------------------------------------------- the endpoint and its cache


def test_a_line_is_read_aloud_and_kept(tmp_path):
    v = FakeVoice()
    c = client(tmp_path, v)
    r = say(c)
    assert r.status_code == 200 and r.headers["content-type"] == "audio/wav" and r.content[:4] == b"RIFF"
    # the same line again, even spaced differently, is not made again
    assert say(c, "  " + LINE.replace(" ", "  ") + " ").content == r.content
    assert v.calls == [LINE]
    assert c.get("/health").json()["voice"] is True


def test_the_cache_outlives_the_server_and_a_missing_key(tmp_path, monkeypatch):
    for name in ("TTS_MODEL", "TTS_VOICE", "TTS_STYLE"):
        monkeypatch.delenv(name, raising=False)
    made = GeminiVoice(client=_Obj(models=_Models(pcm(LINE))))
    assert say(client(tmp_path, made)).status_code == 200
    # a restart with no key at all: the line made before still plays; a new one has no sound
    c = client(tmp_path, None)
    assert say(c).status_code == 200
    assert say(c, "처음 듣는 말").status_code == 503
    assert c.get("/health").json()["voice"] is False


def test_another_voice_or_style_is_another_recording(tmp_path):
    first, second = FakeVoice(identity="m|A|calm"), FakeVoice(identity="m|B|calm")
    say(client(tmp_path, first))
    say(client(tmp_path, second))
    assert len(first.calls) == len(second.calls) == 1


def test_no_sound_is_503_and_bad_requests_are_refused(tmp_path):
    c = client(tmp_path, FakeVoice(fail=RuntimeError("refused")))
    assert say(c).status_code == 503
    # nothing is kept for a line that failed, so it is tried again next time
    assert not list(tmp_path.glob("audio_cache/*.wav"))
    assert say(c, "").status_code == 422
    assert say(c, "가" * 601).status_code == 422
    assert say(c, install="not an id!").status_code == 422


def test_lines_a_day_are_limited(tmp_path, monkeypatch):
    monkeypatch.setenv("SPEAK_DAILY_PER_INSTALL", "2")
    c = client(tmp_path, FakeVoice())
    assert [say(c, f"줄 {i}").status_code for i in range(3)] == [200, 200, 429]


# ---------------------------------------------------------------- free key first, paid key once


def test_a_used_up_free_key_goes_to_the_paid_key_once_and_says_so(tmp_path, caplog):
    free, paid = FakeVoice(fail=QuotaError("429 RESOURCE_EXHAUSTED")), FakeVoice()
    c = client(tmp_path, KeyedVoice(free, paid))
    with caplog.at_level(logging.WARNING, logger="voice"):
        assert say(c).status_code == 200
    assert free.calls == [LINE] and paid.calls == [LINE]
    assert "paid key" in caplog.text
    # kept: the paid key is not asked again for the same line
    say(c)
    assert len(paid.calls) == 1


def test_any_free_key_error_goes_to_the_paid_key(tmp_path):
    paid = FakeVoice()
    assert say(client(tmp_path, KeyedVoice(FakeVoice(fail=RuntimeError("500")), paid))).status_code == 200
    assert paid.calls == [LINE]


def test_when_both_keys_fail_there_is_no_sound(tmp_path):
    paid = FakeVoice(fail=RuntimeError("down"))
    assert say(client(tmp_path, KeyedVoice(FakeVoice(fail=QuotaError("429")), paid))).status_code == 503
    assert paid.calls == [LINE]


def test_a_working_free_key_never_touches_the_paid_key(tmp_path, caplog):
    free, paid = FakeVoice(), FakeVoice()
    with caplog.at_level(logging.WARNING, logger="voice"):
        say(client(tmp_path, KeyedVoice(free, paid)))
    assert paid.calls == [] and "paid key" not in caplog.text


def test_the_paid_key_has_no_daily_limit():
    models = _Models(pcm("가"))
    paid = GeminiVoice(model="m", voice="v", style="s", client=_Obj(models=models), limited=False)
    assert paid.quota is None
    for _ in range(5):
        paid.speak("가")
    free = GeminiVoice(model="m", voice="v", style="s", client=_Obj(models=models), quota=Quota(per_day=1))
    free.speak("가")
    with pytest.raises(QuotaError):
        free.speak("가")


def test_make_voice_follows_the_keys(monkeypatch):
    import app.voice as voice

    class Made:
        def __init__(self, api_key=None, limited=True):
            self.api_key, self.limited = api_key, limited

    monkeypatch.setattr(voice, "GeminiVoice", Made)
    monkeypatch.delenv("TTS_MODEL", raising=False)
    assert make_voice() is None
    monkeypatch.setenv("GEMINI_API_KEY", "free")
    only_free = make_voice()
    assert isinstance(only_free, Made) and only_free.limited
    # an empty paid key changes nothing
    monkeypatch.setenv("GEMINI_API_KEY_PAID", " ")
    assert isinstance(make_voice(), Made)
    monkeypatch.setenv("GEMINI_API_KEY_PAID", "paid")
    both = make_voice()
    assert isinstance(both, KeyedVoice)
    assert (both.free.api_key, both.free.limited, both.paid.api_key, both.paid.limited) == ("free", True, "paid", False)
    monkeypatch.setenv("TTS_MODEL", "off")
    assert make_voice() is None


# ---------------------------------------------------------------- the model call itself


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


def test_the_style_is_never_part_of_the_words(monkeypatch):
    style = "Read in Korean like a warm, playful storyteller reading a picture book to a 5-year-old."
    monkeypatch.setenv("TTS_STYLE", style)
    monkeypatch.setenv("TTS_VOICE", "ko-kr-storyteller-4")
    models = _Models(pcm(LINE))
    v = GeminiVoice(model="m", client=_Obj(models=models))
    assert v.speak(LINE)[:4] == b"RIFF"
    part = models.sent["contents"][0]["parts"][0]
    assert part == {"text": LINE, "speech_metadata": {"style": style}}
    assert models.sent["config"]["response_modalities"] == ["AUDIO"]
    assert models.sent["config"]["speech_config"]["voice_config"]["voice"] == "ko-kr-storyteller-4"


def test_the_quota_guard():
    now = [0.0]
    day = [date(2026, 11, 26)]
    q = Quota(per_day=2, cooldown=60, today=lambda: day[0], clock=lambda: now[0])
    q.take()
    q.take()
    with pytest.raises(QuotaError):
        q.take()
    day[0] = date(2026, 11, 27)
    q.take()
    # a per-minute 429 waits a minute; a daily one waits for tomorrow
    q.refused(Exception("429 RESOURCE_EXHAUSTED. GenerateRequestsPerMinutePerProjectPerModel-FreeTier"))
    with pytest.raises(QuotaError):
        q.take()
    now[0] += 61
    q.take()
    q.refused(Exception("429 RESOURCE_EXHAUSTED. GenerateRequestsPerDayPerProjectPerModel-FreeTier"))
    now[0] += 3600
    with pytest.raises(QuotaError):
        q.take()


class _Refusing:
    def __init__(self):
        self.calls = 0

    def generate_content(self, **kw):
        self.calls += 1
        e = Exception("429 RESOURCE_EXHAUSTED. Quota exceeded: GenerateRequestsPerDayPerProjectPerModel-FreeTier")
        e.code = 429
        raise e


def test_a_429_on_the_free_key_closes_it_without_more_calls():
    models = _Refusing()
    v = GeminiVoice(model="m", voice="v", style="S", client=_Obj(models=models), quota=Quota(per_day=100))
    for _ in range(3):
        with pytest.raises(QuotaError):
            v.speak("안녕")
    assert models.calls == 1
