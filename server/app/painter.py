"""Draws an episode's pictures with Gemini's image model, in the app's soft 3D animated storybook look.

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

from .pacing import Pace

log = logging.getLogger("painter")

IMAGE_MODEL = "gemini-nano-banana-2.1"
# gemini-nano-banana-2.1 refuses "low" (400 INVALID_ARGUMENT, measured 2026-11-27); "minimal" draws in about 13 s
IMAGE_EFFORT = "minimal"
IMAGE_EFFORTS = ("minimal", "low", "medium", "high", "off")
ART_DIR = Path(__file__).with_name("art")


def _image_effort(effort: str | None = None) -> str:
    chosen = (effort or os.environ.get("IMAGE_EFFORT") or IMAGE_EFFORT).strip().lower()
    if chosen not in IMAGE_EFFORTS:
        raise ValueError(f"unknown IMAGE_EFFORT: {chosen}")
    return chosen

# The app's own pictures look like a children's 3D animated film: plush-toy friends with glossy eyes in a rounded
# toy-like world, lit as the scene needs (a hot day, a cold night, fog), never dark or gloomy.
LOOK = (
    "Soft 3D animated storybook look, like a cosy children's animated film. Characters look like soft plush toys "
    "with a fine velvety fur surface and slightly fuzzy edges, round chunky shapes, and big glossy eyes with small "
    "highlights. The world is made of rounded, toy-like props: smooth painted wood, soft fabric, rounded furniture "
    "and plants, tidy with only a few clear props. Soft, gentle light with soft shadows; everything clear and in "
    "focus. Light and colours follow the scene: its place, time of day, weather and temperature (a hot sunny day is "
    "bright, a cold night is deep blue outside with warm lamp light inside, fog is a soft misty blue, underwater is "
    "clear aqua). Colours are rich and cheerful but harmonious, never neon, never dark or gloomy. Cute but not "
    "babyish, for children aged 4 to 12. "
)
# what makes a picture look machine-made; said every time, because the model falls back to it unasked
NOT_AI = (
    "Avoid the typical AI-image look: no lens flare, no bokeh or blurred background, no harsh HDR contrast, no "
    "dramatic spotlight or dark moody lighting, no ornament on every surface, no perfectly symmetrical staging, no "
    "extra limbs or melted shapes. Glow only on real light sources in the scene (lamps, windows, the sun, stars) and "
    "sparkle only on things like frost or stars; simple curved swirl lines may show moving air. Nothing scary or "
    "dangerous. No text, no letters, no numbers, no signs. "
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
    "shows the drawing style in four scenes: copy how the characters and the world are rendered (plush fur, glossy "
    "eyes, toy-like props, soft light), not the places, props, light or composition. "
) + NOT_AI
# 벨트 딸깍 미션: the friend sits in the explorer team's child seat, drawn in today's place but always the same seat and
# belt, from two sheets of all nine friends in it (ref_belt_before, ref_belt_after, laid out like the model sheet)
BELT_BEFORE = (
    "Belt mission: the friend has got into the ride of this scene (the little train carriage, cable car, balloon basket, "
    "submarine or whatever the scene names) and sits in the explorer team's round plush denim-blue child car seat, which "
    "is firmly fitted onto a seat inside that ride. Seen from inside the ride, facing the friend: the ride's own seat, "
    "walls, window frames or railing are around the child seat and the place of the scene is seen beyond them, so the "
    "child seat is never standing alone on the ground. No one else is in the ride and no one drives it. The child seat "
    "is in the middle of the picture. The third reference picture shows how each friend sits in "
    "that seat, in the same places as on the model sheet: draw only the friend of this scene, as in its own place there, "
    "with the same seat, dark gray five-point harness, two round silver buckle tongues and black buckle with a red "
    "button. The belt is not buckled yet: the friend holds one silver tongue in each paw, clearly apart from the "
    "red-button buckle between its legs, and smiles at the viewer. The seat and the buckle are large and clear in the "
    "middle; the top fifth and the bottom sixth of the picture are simple background. "
)
BUCKLED = (
    "Edit the first picture. Keep everything else exactly the same: the place, the light, the colours, the friend, the "
    "child seat, the camera and the composition. The friend keeps its whole outfit (vest, cape, overalls, scarf, hat), "
    "the yellow star badge on its chest and its prop exactly as in the first picture, with the straps going over them. "
    "Change only this: both silver tongues are clicked all the way into the "
    "black buckle with the red button between the friend's legs, joined together, and the shoulder straps run neatly in "
    "a V from the shoulders to the buckle, as for the same friend on the second reference picture. The friend no longer "
    "holds the tongues: it smiles with happy closed eyes and waves one paw at shoulder height. The buckle stays clearly "
    "visible. "
) + NOT_AI


class PainterError(Exception):
    pass


class Painter(Protocol):
    """A painter may also have paint_belt(description) and buckle(before) for the belt mission ([GeminiPainter]); one
    without them draws that scene with paint() and no picture for after the click."""

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

    def __init__(self, model: str | None = None, client=None, effort: str | None = None, api_key: str | None = None):
        from google import genai
        from google.genai import types

        self.model = model or os.environ.get("IMAGE_MODEL", IMAGE_MODEL)
        self.effort = _image_effort(effort)
        # pictures are drawn with the paid key only: the image model has no free tier, so the free key (GEMINI_API_KEY,
        # kept for speech) would only be refused
        key = api_key or os.environ.get("GEMINI_API_KEY_PAID", "").strip()
        if client is None and not key:
            raise PainterError("GEMINI_API_KEY_PAID is not set")
        # one picture usually takes 10~20 s; a busy minute is tried again a few seconds later
        self.client = client or genai.Client(api_key=key, http_options=types.HttpOptions(
            timeout=120_000,
            # a 429 is left to the pace below, which waits as long as Google asks
            retry_options=types.HttpRetryOptions(attempts=2, initial_delay=4, max_delay=20, http_status_codes=[500, 503]),
        ))
        # the paid key's per-minute limit for the image model, shared by every episode being drawn: Google allows 100
        # a minute on this project (AI Studio, 2026-11-27), so 60 leaves room and an episode's 6 pictures never wait
        self.pace = Pace(int(os.environ.get("IMAGE_RPM", "60")), "pictures (paid key)")
        self.refs = [
            types.Part.from_bytes(data=(ART_DIR / f"{n}.jpg").read_bytes(), mime_type="image/jpeg")
            for n in ("ref_friends", "ref_style")
        ]
        self.belt_before, self.belt_after = (
            types.Part.from_bytes(data=(ART_DIR / f"{n}.jpg").read_bytes(), mime_type="image/jpeg")
            for n in ("ref_belt_before", "ref_belt_after")
        )

    def paint(self, description: str) -> bytes:
        return to_webp(self._draw(STYLE + "Scene: " + description, self.refs, "3:2"))

    def paint_belt(self, description: str) -> bytes:
        """벨트 딸깍 미션 before the click: the friend of the scene in the child seat, the belt not buckled yet."""
        return to_webp(self._draw(STYLE + "Scene: " + description + " " + BELT_BEFORE, [*self.refs, self.belt_before], "3:2"))

    def buckle(self, before: bytes) -> bytes:
        """The same picture once the belt is clicked shut, edited from [paint_belt]'s picture."""
        from google.genai import types
        first = types.Part.from_bytes(data=before, mime_type="image/webp")
        return to_webp(self._draw(BUCKLED, [first, self.belt_after], "3:2"))

    def _draw(self, prompt: str, refs: list, aspect: str) -> bytes:
        from google.genai import types
        thinking = (
            types.ThinkingConfig(thinking_level=getattr(types.ThinkingLevel, self.effort.upper()))
            if self.effort != "off" else None
        )
        response = self.pace.call(lambda: self.client.models.generate_content(
            model=self.model,
            contents=[prompt, *refs],
            config=types.GenerateContentConfig(
                response_modalities=["TEXT", "IMAGE"],
                image_config=types.ImageConfig(aspect_ratio=aspect, image_size="1K"),
                thinking_config=thinking,
            ),
        ))
        for part in response.parts or []:
            if part.inline_data is not None and part.inline_data.data:
                return part.inline_data.data
        raise PainterError(f"no image in the reply (prompt_feedback={response.prompt_feedback})")


def make_painter() -> Painter | None:
    """Gemini draws with the paid key (GEMINI_API_KEY_PAID); without it, or with IMAGE_MODEL=off, the app keeps its own
    pictures."""
    paid_key = os.environ.get("GEMINI_API_KEY_PAID", "").strip()
    if os.environ.get("IMAGE_MODEL", "").lower() == "off" or not paid_key:
        return None
    return GeminiPainter(api_key=paid_key)
