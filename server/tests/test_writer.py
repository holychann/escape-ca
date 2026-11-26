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
    assert config.thinking_config.thinking_level == types.ThinkingLevel.MEDIUM
    assert models.calls[0]["contents"] == "장면"


def test_story_and_image_efforts_follow_defaults_and_env(monkeypatch):
    from app.painter import GeminiPainter

    for name in ("STORY_EFFORT", "IMAGE_EFFORT"):
        monkeypatch.delenv(name, raising=False)
    captured = {}

    def create(**kwargs):
        captured.update(kwargs)
        return SimpleNamespace(
            stop_reason="end_turn",
            content=[SimpleNamespace(type="text", text=draft([MATH]).model_dump_json())],
            model="claude-opus-5-5",
            usage=None,
        )

    fake_claude = SimpleNamespace(beta=SimpleNamespace(messages=SimpleNamespace(create=create)))
    ClaudeWriter(client=fake_claude).write("규칙", "장면")
    assert captured["output_config"]["effort"] == "medium"

    monkeypatch.setenv("STORY_EFFORT", "high")
    ClaudeWriter(client=fake_claude).write("규칙", "장면")
    assert captured["output_config"]["effort"] == "high"
    w, gem_models = gemini()
    w.write("규칙", "장면")
    assert gem_models.calls[0]["config"].thinking_config.thinking_level == types.ThinkingLevel.HIGH

    img_calls = []

    class FakeImgModels:
        def generate_content(self, **kwargs):
            img_calls.append(kwargs)
            from PIL import Image
            import io
            buf = io.BytesIO()
            Image.new("RGB", (20, 20), (255, 200, 100)).save(buf, "PNG")
            return SimpleNamespace(parts=[SimpleNamespace(inline_data=SimpleNamespace(data=buf.getvalue()))])

    painter = GeminiPainter(client=SimpleNamespace(models=FakeImgModels()))
    painter.paint("반테가 웃는다")
    assert img_calls[0]["config"].thinking_config.thinking_level == types.ThinkingLevel.MINIMAL


def test_the_belt_mission_is_drawn_with_the_seat_sheets_and_buckled_from_its_own_picture():
    from app.painter import BELT_BEFORE, BUCKLED, GeminiPainter

    calls = []

    class FakeImgModels:
        def generate_content(self, **kwargs):
            calls.append(kwargs)
            from PIL import Image
            import io
            buf = io.BytesIO()
            Image.new("RGB", (20, 20), (90, 120, 180)).save(buf, "PNG")
            return SimpleNamespace(parts=[SimpleNamespace(inline_data=SimpleNamespace(data=buf.getvalue()))])

    painter = GeminiPainter(client=SimpleNamespace(models=FakeImgModels()))
    before = painter.paint_belt("반테가 공룡 섬의 탐험대 카시트에 앉아 있다")
    painter.buckle(before)
    prompt, *refs = calls[0]["contents"]
    # the model sheet, the style picture and the sheet of every friend in the seat
    assert prompt.endswith(BELT_BEFORE) and "공룡 섬" in prompt and refs == [*painter.refs, painter.belt_before]
    prompt, first, sheet = calls[1]["contents"]
    assert prompt == BUCKLED and first.inline_data.data == before and sheet is painter.belt_after


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


def test_the_script_is_written_with_the_anthropic_key(monkeypatch):
    for name in ("STORY_PROVIDER", "GEMINI_API_KEY", "GEMINI_API_KEY_PAID", "STORY_MODEL"):
        monkeypatch.delenv(name, raising=False)
    assert isinstance(make_writer(), ClaudeWriter)
    # the Gemini keys are for speech and pictures: having them does not change the writer
    monkeypatch.setenv("GEMINI_API_KEY", "free")
    monkeypatch.setenv("GEMINI_API_KEY_PAID", "paid")
    assert isinstance(make_writer(), ClaudeWriter)
    monkeypatch.setenv("STORY_PROVIDER", "gemini")
    assert isinstance(make_writer(), GeminiWriter)
    monkeypatch.setenv("STORY_PROVIDER", "openai")
    with pytest.raises(ValueError):
        make_writer()


def test_pictures_are_drawn_with_the_paid_key_only(monkeypatch):
    from app.painter import GeminiPainter, PainterError, make_painter

    for name in ("GEMINI_API_KEY", "GEMINI_API_KEY_PAID", "IMAGE_MODEL"):
        monkeypatch.delenv(name, raising=False)
    # the free key alone draws nothing: the image model has no free tier
    monkeypatch.setenv("GEMINI_API_KEY", "free")
    assert make_painter() is None
    with pytest.raises(PainterError):
        GeminiPainter()
    monkeypatch.setenv("GEMINI_API_KEY_PAID", "paid")
    painter = make_painter()
    assert isinstance(painter, GeminiPainter)
    assert painter.client._api_client.api_key == "paid"
    monkeypatch.setenv("IMAGE_MODEL", "off")
    assert make_painter() is None


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
    # a Gemini key alone still means Claude writes, as in app.writer
    monkeypatch.setenv("GEMINI_API_KEY", "g")
    assert story_spec.configured_price() == story_spec.CLAUDE_PRICES["claude-opus"]

    monkeypatch.setenv("STORY_MODEL", "claude-sonnet-5-5")
    assert story_spec.configured_price() == (3.00, 15.00)

    monkeypatch.setenv("STORY_PROVIDER", "gemini")
    monkeypatch.delenv("STORY_MODEL")
    assert story_spec.configured_price() == (story_spec.PRICE_IN, story_spec.PRICE_OUT)

