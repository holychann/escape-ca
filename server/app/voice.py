"""Reads Lumi's lines aloud with Gemini's speech model, in a warm picture-book voice instead of the car's flat TTS.

The car's own voice (Pleos TextToSpeech) has no rate, pitch or SSML control and reads every line like the news, so
the server records each checked line once and the app plays the recording. A line without a recording (no key, a
failed call, the app's own lines) is still read by the car's voice.
"""
import io
import logging
import os
import wave
from typing import Protocol

log = logging.getLogger("voice")

TTS_MODEL = "gemini-3.8-flash-tts"
TTS_VOICE = "Leda"
# Gemini's speech model returns raw PCM: 24 kHz, mono, 16-bit
RATE = 24_000

STYLE = (
    "A warm, playful Korean storyteller reading a picture book aloud to a young child in the back seat. "
    "Never flat like a news reader: use a wide, lively pitch range and speak with a smile. "
    "Slow down a little just before a clue, lift the end of every question, say exclamations with excitement, "
    "and make sound words (like 딸깍, 모락모락, 투덜투덜) fun and bouncy. "
    "Pronounce every word clearly, at a pace a child can easily follow."
)
# the same words read plainly, to hear what the style adds (tools/tts_sample.py --plain)
PLAIN = "Read the text in a plain, neutral Korean voice."


class VoiceError(Exception):
    pass


class Voice(Protocol):
    def speak(self, text: str) -> bytes:
        """WAV bytes of [text] read aloud."""


def to_wav(audio: bytes, rate: int = RATE) -> bytes:
    """[audio] as a WAV file: kept as it is when it already is one, raw 16-bit mono PCM gets a RIFF header."""
    if audio[:4] == b"RIFF" and audio[8:12] == b"WAVE":
        return audio
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(audio)
    return out.getvalue()


class GeminiVoice:
    """Shared by the threads that read one episode's lines at the same time, so the client is made once up front
    (as in GeminiPainter)."""

    def __init__(self, model: str | None = None, voice: str | None = None, style: str | None = None, client=None):
        from google import genai
        from google.genai import types

        self.model = model or os.environ.get("TTS_MODEL", TTS_MODEL)
        self.voice = voice or os.environ.get("TTS_VOICE", TTS_VOICE)
        self.style = style or os.environ.get("TTS_STYLE") or STYLE
        self.client = client or genai.Client(http_options=types.HttpOptions(
            timeout=120_000,
            retry_options=types.HttpRetryOptions(attempts=2, initial_delay=4, max_delay=20, http_status_codes=[429, 500, 503]),
        ))

    def speak(self, text: str) -> bytes:
        response = self.client.models.generate_content(
            model=self.model,
            contents=[{"role": "user", "parts": [{"text": text, "speech_metadata": {"style": self.style}}]}],
            config={"response_modalities": ["AUDIO"], "speech_config": {"voice_config": {"voice": self.voice}}},
        )
        candidate = response.candidates[0] if response.candidates else None
        parts = (candidate.content.parts if candidate and candidate.content else None) or []
        for part in parts:
            if part.inline_data is not None and part.inline_data.data:
                return to_wav(part.inline_data.data)
        raise VoiceError(f"no audio in the reply (prompt_feedback={getattr(response, 'prompt_feedback', None)})")


def make_voice() -> Voice | None:
    """Gemini reads aloud when a Gemini key is set; TTS_MODEL=off leaves every line to the car's own voice."""
    if os.environ.get("TTS_MODEL", "").lower() == "off" or not os.environ.get("GEMINI_API_KEY"):
        return None
    return GeminiVoice()
