"""Asks the story model (Claude or Gemini) for a draft in the fixed JSON shape. API keys stay on this server only."""
import os
import re
from typing import Protocol

import anthropic
from google import genai
from google.genai import types

from .models import DRAFT_SCHEMA, Draft

DEFAULT_MODEL = "claude-opus-5-5"
GEMINI_MODEL = "gemini-3.6-flash"
DEFAULT_EFFORT = "medium"
EFFORTS = ("low", "medium", "high")
_GEMINI_THINKING = {
    "low": types.ThinkingLevel.LOW,
    "medium": types.ThinkingLevel.MEDIUM,
    "high": types.ThinkingLevel.HIGH,
}


def _story_effort(effort: str | None = None) -> str:
    chosen = (effort or os.environ.get("STORY_EFFORT") or DEFAULT_EFFORT).strip().lower()
    if chosen not in EFFORTS:
        raise ValueError(f"unknown STORY_EFFORT: {chosen}")
    return chosen


class WriterError(Exception):
    pass


class Writer(Protocol):
    def write(self, system: str, user: str) -> tuple[Draft, str]:
        """Returns the draft and the model that wrote it."""


class ClaudeWriter:
    def __init__(self, model: str | None = None, client: anthropic.Anthropic | None = None,
                 effort: str | None = None):
        self.model = model or os.environ.get("STORY_MODEL", DEFAULT_MODEL)
        self.effort = _story_effort(effort)
        self._client = client
        self.last_usage: anthropic.types.beta.BetaUsage | None = None

    @property
    def client(self) -> anthropic.Anthropic:
        # made on first use, so the server starts (and tests run) without a key
        if self._client is None:
            self._client = anthropic.Anthropic(timeout=90.0, max_retries=1)
        return self._client

    @property
    def last_tokens(self) -> tuple[int, int] | None:
        u = self.last_usage
        if u is None:
            return None
        tokens_in = (u.input_tokens or 0) + (u.cache_creation_input_tokens or 0) + (u.cache_read_input_tokens or 0)
        return tokens_in, (u.output_tokens or 0)

    def write(self, system: str, user: str) -> tuple[Draft, str]:
        extra = {}
        # a policy decline on these models is retried server side on the recommended model
        if self.model.startswith(("claude-opus", "claude-sonnet", "claude-fable")):
            extra = {"betas": ["server-side-fallback-2026-07-01"], "fallbacks": "default"}
        response = self.client.beta.messages.create(
            model=self.model,
            max_tokens=16000,
            system=system,
            messages=[{"role": "user", "content": user}],
            # medium effort gives enough planning for 2-variant continuity and lesson callbacks without high's wait
            output_config={"effort": self.effort, "format": {"type": "json_schema", "schema": DRAFT_SCHEMA}},
            **extra,
        )
        self.last_usage = getattr(response, "usage", None)
        if response.stop_reason != "end_turn":
            raise WriterError(f"stop_reason={response.stop_reason}")
        text = next((b.text for b in response.content if b.type == "text"), None)
        if text is None:
            raise WriterError("no text block")
        return Draft.model_validate_json(text), response.model


class GeminiWriter:
    def __init__(self, model: str | None = None, client: genai.Client | None = None, effort: str | None = None):
        self.model = model or os.environ.get("STORY_MODEL", GEMINI_MODEL)
        self.effort = _story_effort(effort)
        self._client = client
        # token counts of the last call, read by tools/story_spec.py to work out the cost
        self.last_usage: types.GenerateContentResponseUsageMetadata | None = None

    @property
    def last_tokens(self) -> tuple[int, int] | None:
        u = self.last_usage
        if u is None:
            return None
        return (u.prompt_token_count or 0), (u.candidates_token_count or 0) + (u.thoughts_token_count or 0)

    @property
    def client(self) -> genai.Client:
        # reads GEMINI_API_KEY; made on first use for the same reason as ClaudeWriter
        if self._client is None:
            self._client = genai.Client(http_options=types.HttpOptions(
                # also sent as the server deadline: a draft takes 40~70 s, more when Google is busy
                timeout=150_000,
                # busy and per-minute limits pass in seconds; a timed-out draft is not tried again here
                retry_options=types.HttpRetryOptions(
                    attempts=3, initial_delay=5, max_delay=30, http_status_codes=[429, 500, 503]),
            ))
        return self._client

    def write(self, system: str, user: str) -> tuple[Draft, str]:
        response = self.client.models.generate_content(
            model=self.model,
            contents=user,
            config=types.GenerateContentConfig(
                system_instruction=system,
                response_mime_type="application/json",
                response_json_schema=DRAFT_SCHEMA,
                thinking_config=types.ThinkingConfig(thinking_level=_GEMINI_THINKING[self.effort]),
                max_output_tokens=16000,
            ),
        )
        self.last_usage = response.usage_metadata
        candidate = response.candidates[0] if response.candidates else None
        if candidate is None:
            raise WriterError(f"no candidate, prompt_feedback={response.prompt_feedback}")
        if candidate.finish_reason != types.FinishReason.STOP:
            raise WriterError(f"finish_reason={candidate.finish_reason}")
        if not response.text:
            raise WriterError("no text")
        return Draft.model_validate_json(response.text), response.model_version or self.model


def _provider() -> str:
    """The script is written with the Anthropic key (Claude). The Gemini keys are kept for speech and pictures, so
    having them never changes the writer; STORY_PROVIDER=gemini switches to Gemini on the free key, for trying only."""
    return os.environ.get("STORY_PROVIDER") or "claude"


def make_writer() -> Writer:
    provider = _provider()
    if provider == "gemini":
        return GeminiWriter()
    if provider == "claude":
        return ClaudeWriter()
    raise ValueError(f"unknown STORY_PROVIDER: {provider}")


# ---------------------------------------------------------------- blind check (tools/make_bank.py)

SOLVER_CLAUDE = "claude-haiku-5-5"
# room to think first: with a tiny limit a harder question runs out while thinking and gives no answer
SOLVER_TOKENS = 2048


def solver_prompt(question: str, choices: list[str]) -> str:
    lines = [f"문제: {question}", "보기:", *[f"{i}. {c}" for i, c in enumerate(choices, 1)], "정답 번호만 숫자로 답해."]
    return "\n".join(lines)


def picked(text: str | None, n: int) -> int | None:
    """The index of the choice a model's answer names, or None when it names no choice."""
    found = re.search(r"\d+", text or "")
    i = int(found.group()) - 1 if found else -1
    return i if 0 <= i < n else None
