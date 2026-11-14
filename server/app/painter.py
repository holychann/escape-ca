"""Draws an episode's pictures with Gemini's image model, in the app's soft 3D clay look.

The writer only describes each picture (Draft.art) and the checker has already removed any description that
would show an answer, so a picture can set the scene but never tell the child which button to press.
Every call gets the same three reference pictures (Lumi's faces, the six friends, one finished scene), so a new
world still has the same characters drawn the same way.
"""
import io
import logging
import os
from pathlib import Path
from typing import Protocol

from PIL import Image

log = logging.getLogger("painter")

IMAGE_MODEL = "gemini-nano-banana-2.1"
ART_DIR = Path(__file__).with_name("art")

STYLE = (
    "Soft 3D clay render, claymation / plasticine look, rounded chunky forms, matte surfaces with faint fingerprint "
    "texture, soft warm lighting, gentle depth of field, refined like a premium animated short, cute but not babyish, "
    "for children aged 4 to 12. Landscape picture, main subjects centered with margin on the left and right. "
    "The first reference picture shows Lumi, a small round white robot guide; the second shows the six friends "
    "(sprout robot, otter cook, penguin captain, grumpy lilac machine, fennec fox, rabbit engineer); draw any of them "
    "that appear exactly as in those pictures. The third reference picture shows the art style to match. "
    "Nothing scary or dangerous. No text, no letters, no numbers, no signs. Scene: "
)


class PainterError(Exception):
    pass


class Painter(Protocol):
    def paint(self, description: str) -> bytes:
        """WebP bytes of one picture for this scene description."""


def to_webp(png_or_jpeg: bytes, width: int = 1536) -> bytes:
    im = Image.open(io.BytesIO(png_or_jpeg)).convert("RGB")
    if im.width > width:
        im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    out = io.BytesIO()
    im.save(out, "WEBP", quality=82, method=6)
    return out.getvalue()


class GeminiPainter:
    """Shared by the threads that draw one episode's pictures at the same time, so everything it holds is made once
    up front: one client (a client made lazily per thread got closed under another thread) and the reference pictures
    as bytes (PIL images read lazily from several threads came back truncated)."""

    def __init__(self, model: str | None = None, client=None):
        from google import genai
        from google.genai import types

        self.model = model or os.environ.get("IMAGE_MODEL", IMAGE_MODEL)
        # one picture usually takes 10~20 s; a busy minute is tried again a few seconds later
        self.client = client or genai.Client(http_options=types.HttpOptions(
            timeout=120_000,
            retry_options=types.HttpRetryOptions(attempts=2, initial_delay=4, max_delay=20, http_status_codes=[429, 500, 503]),
        ))
        self.refs = [
            types.Part.from_bytes(data=(ART_DIR / f"{n}.jpg").read_bytes(), mime_type="image/jpeg")
            for n in ("ref_lumi", "ref_friends", "ref_style")
        ]

    def paint(self, description: str) -> bytes:
        from google.genai import types
        response = self.client.models.generate_content(
            model=self.model,
            contents=[STYLE + description, *self.refs],
            config=types.GenerateContentConfig(
                response_modalities=["TEXT", "IMAGE"],
                image_config=types.ImageConfig(aspect_ratio="3:2", image_size="1K"),
            ),
        )
        for part in response.parts or []:
            if part.inline_data is not None and part.inline_data.data:
                return to_webp(part.inline_data.data)
        raise PainterError(f"no image in the reply (prompt_feedback={response.prompt_feedback})")


def make_painter() -> Painter | None:
    """Gemini draws when a Gemini key is set; IMAGE_MODEL=off keeps the app's own pictures."""
    if os.environ.get("IMAGE_MODEL", "").lower() == "off" or not os.environ.get("GEMINI_API_KEY"):
        return None
    return GeminiPainter()
