"""Runs the server with a fixed-text writer and a placeholder painter instead of real models, for wiring checks on
the emulator without a key.

    python -m tools.dev_server        (then: adb reverse tcp:8787 tcp:8787)

Its stories are plain templates built from the checker's own rules and its episodes say model "dev-template".
A story wish shows up in the title and world, and every picture is a plain card saying which picture it is.
Never use it for a demo of the AI story.
"""
import io
import itertools
import os
import re
import time

import uvicorn
from PIL import Image, ImageDraw

from app.main import create_app
from app.models import Art, Draft, DraftScene, NewFriend
from app.painter import to_webp
from app.rules import rule_for


_FRIEND_NAMES = itertools.cycle(["가", "나", "다", "라", "마", "바", "사", "아", "자", "차"])


class TemplateWriter:
    def write(self, system: str, user: str) -> tuple[Draft, str]:
        # prompt.py writes each scene as "[key=FOG]"
        keys = re.findall(r"\[key=([^\]]+)\]", user)
        wish = re.search(r"가족이 바란 이야기: «([^»]+)»", user)
        world = wish.group(1) if wish else "별빛 기차"
        slots = len(re.findall(r"^\d+번째 퍼즐", user, flags=re.M))
        scenes = []
        for key in keys:
            rule = rule_for(key)
            words = " ".join(group[0] for group in rule.must)
            ask = "" if not rule.needs_guardian else " 찾으면 안전 지킴이에게 가리켜 알려 주자!"
            hint = " ".join(group[0] for group in rule.hint_must) or "다시 들어 보자."
            scenes.append(DraftScene(key=key, bubble="[개발용] 장면", speech=f"[개발용] {words}.{ask}", hint=f"[개발용] {hint}"))
        draft = Draft(
            theme="STAR_TRAIN",
            world=world,
            title=f"[개발용] {world}"[:20],
            intro="[개발용] 서버에서 받은 이야기야.",
            success=["[개발용] 칭찬 하나", "[개발용] 칭찬 둘", "[개발용] 칭찬 셋"],
            treasureBadge="[개발용] 배지 받았어!",
            scenes=scenes,
            art=Art(cover=f"{world} 표지", puzzles=[f"{world} 퍼즐 {i}" for i in range(1, slots + 1)], ending=f"{world} 마지막"),
            # a different friend each time, so the book can be seen growing
            friend=NewFriend(
                name=f"꼬마 친구 {next(_FRIEND_NAMES)}",
                look="동그란 몸에 별 모자를 쓴 꼬마",
                trait="[개발용] 노래를 좋아해.",
                hello="[개발용] 반가워, 탐험대장!",
            ),
        )
        return draft, "dev-template"


class CardPainter:
    """A plain coloured card per picture, after a wait (DEV_PAINT_SECONDS, default 1.5), so the app's waiting
    screen can be seen without paying for real pictures."""

    COLORS = [(255, 197, 49), (255, 122, 89), (46, 196, 166), (77, 163, 255), (155, 123, 255), (30, 43, 94)]

    def paint(self, description: str, friend: bytes | None = None) -> bytes:
        time.sleep(float(os.environ.get("DEV_PAINT_SECONDS", "1.5")))
        n = int(re.search(r"퍼즐 (\d+)", description).group(1)) if re.search(r"퍼즐 (\d+)", description) else 0
        return self._card((1536, 1024), self.COLORS[n % len(self.COLORS)], f"DEV PICTURE {'cover/ending' if n == 0 else n}")

    def portrait(self, description: str) -> bytes:
        time.sleep(float(os.environ.get("DEV_PAINT_SECONDS", "1.5")))
        return self._card((640, 640), self.COLORS[hash(description) % len(self.COLORS)], "DEV FRIEND")

    @staticmethod
    def _card(size: tuple[int, int], color, label: str) -> bytes:
        im = Image.new("RGB", size, color)
        d = ImageDraw.Draw(im)
        w, h = size
        for r in range(0, max(size), 60):
            d.ellipse((w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r), outline=(255, 255, 255), width=4)
        d.text((40, 40), label, fill=(255, 255, 255))
        out = io.BytesIO(); im.save(out, "PNG")
        return to_webp(out.getvalue())


if __name__ == "__main__":
    uvicorn.run(create_app(writer=TemplateWriter(), painter=CardPainter()), host="127.0.0.1", port=8787)
