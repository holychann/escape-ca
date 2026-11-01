from types import SimpleNamespace

import pytest
from google.genai import types

from app.models import DRAFT_SCHEMA
from app.writer import ClaudeWriter, GeminiWriter, WriterError, make_writer
from tests.fakes import draft


class FakeModels:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def generate_content(self, **kwargs):
        self.calls.append(kwargs)
        return self.response


def gemini(finish=types.FinishReason.STOP, text=None, candidates=True):
    response = SimpleNamespace(
        candidates=[SimpleNamespace(finish_reason=finish)] if candidates else [],
        text=draft(["FOG"]).model_dump_json() if text is None else text,
        prompt_feedback=None,
        model_version="gemini-test-001",
        usage_metadata=None,
    )
    models = FakeModels(response)
    return GeminiWriter(model="gemini-test", client=SimpleNamespace(models=models)), models


def test_gemini_draft_is_read_with_the_same_schema_and_rules():
    writer, models = gemini()
    d, model = writer.write("규칙", "장면")
    assert d.scenes[0].key == "FOG"
    assert model == "gemini-test-001"
    config = models.calls[0]["config"]
    assert config.system_instruction == "규칙"
    assert config.response_json_schema == DRAFT_SCHEMA
    assert config.response_mime_type == "application/json"
    assert models.calls[0]["contents"] == "장면"


@pytest.mark.parametrize("kwargs", [
    {"finish": types.FinishReason.SAFETY},
    {"finish": types.FinishReason.MAX_TOKENS},
    {"candidates": False},
    {"text": ""},
])
def test_gemini_stopped_early_is_a_writer_error(kwargs):
    writer, _ = gemini(**kwargs)
    with pytest.raises(WriterError):
        writer.write("규칙", "장면")


def test_provider_follows_the_keys(monkeypatch):
    for name in ("STORY_PROVIDER", "GEMINI_API_KEY", "STORY_MODEL"):
        monkeypatch.delenv(name, raising=False)
    assert isinstance(make_writer(), ClaudeWriter)
    monkeypatch.setenv("GEMINI_API_KEY", "x")
    assert isinstance(make_writer(), GeminiWriter)
    monkeypatch.setenv("STORY_PROVIDER", "claude")
    assert isinstance(make_writer(), ClaudeWriter)
    monkeypatch.setenv("STORY_PROVIDER", "openai")
    with pytest.raises(ValueError):
        make_writer()
