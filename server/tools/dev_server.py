"""Runs the server with a fixed-text writer instead of a real model, for wiring checks on the emulator without a key.

    python -m tools.dev_server        (then: adb reverse tcp:8787 tcp:8787)

Its stories are plain templates built from the checker's own rules and its episodes say model "dev-template".
Never use it for a demo of the AI story.
"""
import uvicorn

from app.keys import rule_for
from app.main import create_app
from app.models import Draft, DraftScene


class TemplateWriter:
    def write(self, system: str, user: str) -> tuple[Draft, str]:
        keys = [line.split("key=", 1)[1] for line in user.splitlines() if "key=" in line]
        scenes = []
        for key in keys:
            rule = rule_for(key)
            words = " ".join(group[0] for group in rule.must)
            ask = "" if not rule.needs_guardian else " 안전 지킴이에게 부탁하자!"
            hint = " ".join(group[0] for group in rule.hint_must) or "다시 들어 보자."
            scenes.append(DraftScene(key=key, bubble="[개발용] 장면", speech=f"[개발용] {words}.{ask}", hint=f"[개발용] {hint}"))
        draft = Draft(
            theme="SEA_SUB",
            title="[개발용] 잠수함",
            intro="[개발용] 서버에서 받은 이야기야.",
            success=["[개발용] 칭찬 하나", "[개발용] 칭찬 둘", "[개발용] 칭찬 셋"],
            treasureGift="[개발용] 선물 시간!",
            treasureBadge="[개발용] 배지 받았어!",
            scenes=scenes,
        )
        return draft, "dev-template"


if __name__ == "__main__":
    uvicorn.run(create_app(writer=TemplateWriter()), host="127.0.0.1", port=8787)
