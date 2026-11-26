from types import SimpleNamespace

import pytest
from google.genai import types

from app.models import DRAFT_SCHEMA
from app.writer import ClaudeWriter, GeminiWriter, WriterError, make_writer, picked, solver_prompt
from tests.fakes import GOOD, MATH, draft


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
        text=draft([MATH]).model_dump_json() if text is None else text,
        prompt_feedback=None,
        model_version="gemini-test-001",
        usage_metadata=None,
    )
    models = FakeModels(response)
    return GeminiWriter(model="gemini-test", client=SimpleNamespace(models=models)), models


def test_gemini_draft_is_read_with_the_same_schema_and_rules():
    writer, models = gemini()
    d, model = writer.write("규칙", "장면")
    assert d.scenes[0].key == MATH and d.scenes[0].bubble == GOOD[MATH][0]
    # the writer writes the story only; the question is put in by the server afterwards
    assert d.scenes[0].question is None and d.scenes[0].hint == ""
    assert model == "gemini-test-001"
    config = models.calls[0]["config"]
    assert config.system_instruction == "규칙"
    assert config.response_json_schema == DRAFT_SCHEMA
    assert config.response_mime_type == "application/json"
    assert models.calls[0]["contents"] == "장면"


def test_the_writer_is_not_asked_for_questions():
    scene = DRAFT_SCHEMA["properties"]["scenes"]["items"]
    # the story around the bank's question and the line said once it is solved
    assert set(scene["properties"]) == {"key", "bubble", "speech", "result", "hint"}
    assert scene["required"] == ["key", "bubble", "speech", "result", "hint"]
    # no generic praise, gift line or made-up friend any more
    assert not {"success", "treasureGift", "friend"} & set(DRAFT_SCHEMA["properties"])


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


@pytest.mark.parametrize("text,n,index", [
    ("2", 3, 1), ("정답은 1번이야.", 2, 0), ("4", 3, None), ("0", 3, None), ("모르겠어", 2, None), (None, 2, None),
])
def test_the_blind_checks_answer_is_read_as_a_choice_number(text, n, index):
    assert picked(text, n) == index


def test_the_blind_check_sees_only_the_question_and_numbered_choices():
    prompt = solver_prompt("앞 유리가 뿌예서 앞이 안 보여!", ["에어컨 켜기", "앞 유리 성에 제거 켜기"])
    assert prompt.splitlines() == ["문제: 앞 유리가 뿌예서 앞이 안 보여!", "보기:", "1. 에어컨 켜기", "2. 앞 유리 성에 제거 켜기",
                                   "정답 번호만 숫자로 답해."]


def test_claude_and_gemini_writers_both_report_token_counts_to_story_spec(monkeypatch):
    from app.models import EpisodeRequest
    from tests.fakes import BANK
    from tools import story_spec

    usage = SimpleNamespace(input_tokens=1200, cache_creation_input_tokens=100, cache_read_input_tokens=50, output_tokens=400)
    message = SimpleNamespace(
        stop_reason="end_turn",
        content=[SimpleNamespace(type="text", text=draft([MATH, "BELT"]).model_dump_json())],
        model="claude-opus-5-5-20260701",
        usage=usage,
    )
    fake_client = SimpleNamespace(beta=SimpleNamespace(messages=SimpleNamespace(create=lambda **_: message)))
    claude = ClaudeWriter(model="claude-opus-5-5", client=fake_client)

    monkeypatch.setattr(story_spec, "_writer", claude)
    monkeypatch.setattr(story_spec, "_bank", BANK)
    req = EpisodeRequest(installId="00000000-0000-0000-0000-000000000000", ages=["CAPTAIN"], minutes=5,
                         context={"timeOfDay": "NIGHT", "season": "SUMMER"}, puzzles=[[MATH], ["BELT"]],
                         friend="BANTE")
    written = story_spec.write(req)
    assert claude.last_tokens == (1350, 400)
    assert (written.tokens_in, written.tokens_out) == (1350, 400)
    assert written.model == "claude-opus-5-5-20260701"

    gem, _ = gemini()
    gem.last_usage = SimpleNamespace(prompt_token_count=900, candidates_token_count=300, thoughts_token_count=80)
    assert gem.last_tokens == (900, 380)


def test_story_spec_configured_price_follows_provider(monkeypatch):
    from tools import story_spec

    monkeypatch.setattr(story_spec, "load_dotenv", lambda: False)
    for name in ("STORY_PROVIDER", "GEMINI_API_KEY", "ANTHROPIC_API_KEY", "STORY_MODEL"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("GEMINI_API_KEY", "g")
    assert story_spec.configured_price() == (story_spec.PRICE_IN, story_spec.PRICE_OUT)

    monkeypatch.setenv("STORY_PROVIDER", "claude")
    monkeypatch.setenv("STORY_MODEL", "claude-sonnet-5-5")
    assert story_spec.configured_price() == (3.00, 15.00)

