"""Runs the server with a fixed-text writer instead of a real model, for wiring checks on the emulator without a key.

    python -m tools.dev_server        (then: adb reverse tcp:8787 tcp:8787)

Its stories are plain templates built from the checker's own rules and its episodes say model "dev-template".
Never use it for a demo of the AI story.
"""
import re

import uvicorn

from app.rules import rule_for
from app.main import create_app
from app.models import Draft, DraftScene


class TemplateWriter:
    def write(self, system: str, user: str) -> tuple[Draft, str]:
        # prompt.py writes each scene as "[key=FOG]"
        keys = re.findall(r"\[key=([^\]]+)\]", user)
        scenes = []
        for key in keys:
            rule = rule_for(key)
            words = " ".join(group[0] for group in rule.must)
            ask = "" if not rule.needs_guardian else " 찾으면 안전 지킴이에게 가리켜 알려 주자!"
            hint = " ".join(group[0] for group in rule.hint_must) or "다시 들어 보자."
            scenes.append(DraftScene(key=key, bubble="[개발용] 장면", speech=f"[개발용] {words}.{ask}", hint=f"[개발용] {hint}"))
        draft = Draft(
            theme="SEA_SUB",
            title="[개발용] 잠수함",
            intro="[개발용] 서버에서 받은 이야기야.",
            success=["[개발용] 칭찬 하나", "[개발용] 칭찬 둘", "[개발용] 칭찬 셋"],
            # the checker drops the whole draft unless the gift line names the 안전 지킴이
            treasureGift="[개발용] 안전 지킴이가 글로브박스에서 선물을 꺼내 줄 거야.",
            treasureBadge="[개발용] 배지 받았어!",
            scenes=scenes,
        )
        return draft, "dev-template"


if __name__ == "__main__":
    uvicorn.run(create_app(writer=TemplateWriter()), host="127.0.0.1", port=8787)
