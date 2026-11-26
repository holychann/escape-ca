"""Draws an episode's pictures with Gemini's image model, in the app's soft felt-doll look.

The writer only describes each picture (Draft.art) and the checker has already removed any description that
would show an answer, so a picture can set the scene but never tell the child which button to press.
Every call gets the same two reference pictures (the nine friends' model sheet, one finished scene), so a new world
still has the same characters drawn the same way.
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

# The app's own pictures are soft needle-felted dolls in warm light; a polished CGI render with glow, bokeh and props
# everywhere reads as "AI picture" next to them.
LOOK = (
    "Soft 3D felt-doll look, like needle-felted wool figures on a small cosy tabletop set. Fuzzy wool texture on the "
    "characters and on the world around them, with a faint soft fuzz at the edges; rounded chunky shapes with few "
    "details, not smooth clay and not shiny plastic. Warm golden light like a cosy lamp-lit room or a late afternoon, "
    "gentle soft shadows, almost everything in focus. Rich warm colours, calm, not neon, not oversaturated. "
    "A simple, uncluttered background with only a few props and some empty space. Cute but not babyish, for children "
    "aged 4 to 12. "
)
# what makes a picture look machine-made; said every time, because the model falls back to it unasked
NOT_AI = (
    "Avoid the typical AI-image look: no glossy CGI render, no cinematic or dramatic lighting, no glow, no sparkles, "
    "no light rays, no lens flare, no bokeh, no HDR, no hyper-detailed textures, no ornament on every surface, "
    "no perfectly symmetrical staging, no extra limbs or melted shapes. Nothing scary or dangerous. "
    "No text, no letters, no numbers, no signs. "
)
STYLE = LOOK + (
    "Landscape picture, main subjects centered with margin on the left and right. "
    "The first reference picture is the model sheet of the nine friends, read row by row: Bante (puppy, light brown and white, symmetric half-folded button ears, red scarf), Konyang (gray "
    "tabby kitten, lime vest, magnifying glass), Ria (white bunny, yellow star hairpin, lilac cape with stars, crayon), "
    "Tubugi (green turtle, dark green shell, small backpack), Pappi (tiny round long-tailed tit bird, short beak, orange "
    "hard hat, pink ribbon), Potto (panda, blue overalls, bamboo basket), Io (orange fox, sky-blue scarf, winking), "
    "Ssolrang (chubby tiger cub, brown explorer hat, small yellow flag) and Caspin (friendly blue baby shark with no "
    "teeth, yellow backpack). Every friend wears the same yellow star badge on the chest. Anyone who appears keeps the "
    "exact face, colours, outfit and prop from the model sheet; never mix two friends or invent new parts. No robot guide or "
    "any other character outside the model sheet appears unless the scene asks for it. The second reference picture "
    "shows the drawing style: copy its felt texture, light and colours, but not its place, props or composition. "
) + NOT_AI


class PainterError(Exception):
    pass


class Painter(Protocol):
    def paint(self, description: str) -> bytes:
        """WebP bytes of one landscape picture for this scene description."""


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
            for n in ("ref_friends", "ref_style")
        ]

    def paint(self, description: str) -> bytes:
        return to_webp(self._draw(STYLE + "Scene: " + description, self.refs, "3:2"))

    def _draw(self, prompt: str, refs: list, aspect: str) -> bytes:
        from google.genai import types
        response = self.client.models.generate_content(
            model=self.model,
            contents=[prompt, *refs],
            config=types.GenerateContentConfig(
                response_modalities=["TEXT", "IMAGE"],
                image_config=types.ImageConfig(aspect_ratio=aspect, image_size="1K"),
            ),
        )
        for part in response.parts or []:
            if part.inline_data is not None and part.inline_data.data:
                return part.inline_data.data
        raise PainterError(f"no image in the reply (prompt_feedback={response.prompt_feedback})")


def make_painter() -> Painter | None:
    """Gemini draws when a Gemini key is set; IMAGE_MODEL=off keeps the app's own pictures."""
    if os.environ.get("IMAGE_MODEL", "").lower() == "off" or not os.environ.get("GEMINI_API_KEY"):
        return None
    return GeminiPainter()
