import pytest


@pytest.fixture(autouse=True)
def no_real_models(monkeypatch):
    """A key left in the shell must never turn a test into a paid call: without one, create_app() gets no painter and
    no voice unless a test hands in a fake."""
    for name in ("GEMINI_API_KEY", "GEMINI_API_KEY_PAID", "GOOGLE_API_KEY", "ANTHROPIC_API_KEY"):
        monkeypatch.delenv(name, raising=False)
