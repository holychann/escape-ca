import pytest


@pytest.fixture(autouse=True)
def no_real_models(monkeypatch):
    """A key left in the shell must never turn a test into a paid call: without one, create_app() gets no painter and
    no voice unless a test hands in a fake."""
    for name in ("GEMINI_API_KEY", "GEMINI_API_KEY_PAID", "GOOGLE_API_KEY", "ANTHROPIC_API_KEY"):
        monkeypatch.delenv(name, raising=False)


@pytest.fixture(autouse=True)
def pictures_drawn_in_place(monkeypatch):
    """An episode's pictures are drawn after its response on a thread of their own; in a test they are drawn before
    the response returns, so what a test sees does not depend on timing. tests/test_pictures.py runs them later."""
    import app.main
    monkeypatch.setattr(app.main, "_run_pictures", lambda job: job())
