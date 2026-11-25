"""Runs the server with a fixed-text writer and a placeholder painter instead of real models, for wiring checks on
the emulator without a key.

    python -m tools.dev_server        (then: adb reverse tcp:8787 tcp:8787)

The questions come from the real question bank (app/question_bank.json) once tools/make_bank.py has made it, else
from a small built-in one: for every device, a template instruction of every number kind it asks for every answer it
takes, its amount a little puzzle (MATH a sum, ENGLISH a number word, PATTERN a run going up or down by one), and a
template situation for every function a situation puzzle (REASON) can ask. The stories around them are plain
templates and the belt mission is built from the checker's own rules. They still go through the real checker, and its
episodes say model "dev-template". A story wish shows up in the title and world, and every picture is a plain card
saying which picture it is. Never use it for a demo of the AI story.
"""
import io
import itertools
import os
import re
import time
from pathlib import Path

import uvicorn
from PIL import Image, ImageDraw

from app import bank, quiz
from app.bank import Bank, Entry
from app.main import create_app
from app.models import Art, Draft, DraftScene, NewFriend
from app.painter import to_webp
from app.rules import rule_for


_FRIEND_NAMES = itertools.cycle(["가", "나", "다", "라", "마", "바", "사", "아", "자", "차"])
# picture names in the art descriptions: a digit there could be an answer and keep the picture from being drawn
_SLOT_NAMES = "가나다라마바"

# the bank refuses brackets in a question or hint (bank.ODD), so the template questions are marked with a colon
_DEV = "개발용:"
_HINT = f"{_DEV} 천천히 다시 생각해 봐."

# a situation for every function a situation puzzle can ask, held to the bank's rules (bank.reason_problems): a
# trouble with a friend, no button name, and a request to solve it with the car's buttons
_SITUATIONS = {
    ("AC", 1): "수달 요리사가 뛰어놀고 와서 바람은 나오는데 안 시원하대. 차 버튼으로 도와줘!",
    ("AC", 0): "감기 걸린 펭귄 선장이 더울 때 켜는 시원한 바람이 켜져 있어서 떨어. 차 버튼으로 도와줘!",
    ("RECIRC", 1): "밖의 먼지 냄새가 바람 구멍으로 들어와서 사막여우가 콜록거려. 차 버튼으로 도와줘!",
    ("RECIRC", 0): "창문은 닫아 둔 채로, 같은 공기만 맴돌아서 답답한 새싹 로봇을 차 버튼으로 도와줘!",
    ("DEFROST", 1): "앞 유리 안쪽이 뿌예지려고 해, 와이퍼는 바깥만 닦아. 차 버튼으로 도와줘!",
    ("TEMP_UP", 1): "감기 걸린 토끼 기관사가 덜덜 떨어. 의자 말고 차 안을 따뜻하게, 차 버튼으로 도와줘!",
    ("TEMP_DOWN", 1): "뛰어놀고 온 펭귄 선장이 조금 덥대, 바람이 세지면 종이가 날아가. 차 버튼으로 도와줘!",
    ("FAN_UP", 1): "새싹 로봇의 바람개비가 느릿느릿 돌아. 빙글빙글 빨리 돌게 차 버튼으로 도와줘!",
    ("FAN_DOWN", 1): "바람이 쌩쌩 불어서 그림 종이가 날아가. 바람은 조금 나와야 하니까 차 버튼으로 도와줘!",
    ("SEAT_HEAT_ON", 1): "펭귄 선장이 얼음을 올려 둬서 안전 지킴이 의자가 차가워. 차 버튼으로 도와줘!",
    ("SEAT_HEAT_OFF", 0): "안전 지킴이 의자가 계속 뜨끈해. 의자가 그만 데워지게 차 버튼으로 도와줘!",
    ("LIGHT_CHILD", 1): "그림책 글씨가 개미만큼 작아서 네 자리가 더 밝아야 해. 차 버튼으로 도와줘!",
    ("LIGHTS_OFF", 0): "사막여우가 자려는데 작은 불이 하나도 안 켜져 있어야 잠이 온대. 차 버튼으로 도와줘!",
}


# how each device is told its amount, as the bank wants it (bank.problems: the device and what to do with it, said
# as an instruction, not asked): the sentence around the amount, the amount's unit and what follows a run of amounts
_TELL = {
    "FAN": ("바람 세기를 {}으로 맞춰 줘!", "단", "다음 단"),
    "TEMP_UP": ("{}만큼 온도를 올려 줘!", "도", ""),
    "TEMP_DOWN": ("{}만큼 온도를 내려 줘!", "도", ""),
    "LIGHTS": ("켜진 독서등이 모두 {}가 되게 해 줘!", "개", "다음 수"),
    "SEAT_HEAT": ("의자 열선을 {}으로 맞춰 줘!", "단", ""),
}


def template_entry(dial: str, kind: str, v: int) -> Entry:
    """An instruction to the device made for the youngest whose amount works out to v; the older ages get it too
    (Bank.candidates)."""
    sentence, unit, after_run = _TELL[dial]
    base = dict(id=f"dev-{dial.lower()}-{kind[0].lower()}-{v}", kind=kind, dial=dial, age="LITTLE", hint=_HINT, answer=v)

    def told(amount: str) -> str:
        return f"{_DEV} {sentence.format(amount)}"

    if kind == "MATH":
        # a sum of two small numbers, neither of them the answer; 1 is the only answer that needs taking away
        if v == 1:
            return Entry(**base, text=told(f"3 빼기 2{unit}"), expr="3-2")
        a = v // 2
        return Entry(**base, text=told(f"{a} 더하기 {v - a}{unit}"), expr=f"{a}+{v - a}")
    if kind == "ENGLISH":
        word = quiz.ENGLISH_OF[v]
        return Entry(**base, text=told(f"{word}{unit}"), word=word)
    # up by one whenever the run can start at 0 or more, else down by one
    terms = (v - 3, v - 2, v - 1) if v >= 3 else (v + 3, v + 2, v + 1)
    return Entry(**base, text=told(", ".join(f"{t}{unit}" for t in terms) + f" {after_run}"), terms=terms)


def template_situation(dial: str, v: int) -> Entry:
    """The situation for one function, for the youngest age it is told to (quiz.REASON_NOT_LITTLE)."""
    age = "CAPTAIN" if dial in quiz.REASON_NOT_LITTLE else "LITTLE"
    return Entry(id=f"dev-{dial.lower()}-r-{v}", kind="REASON", dial=dial, age=age,
                 text=f"{_DEV} {_SITUATIONS[(dial, v)]}", hint=_HINT, answer=v)


TEMPLATE_ENTRIES = [
    template_entry(dial, kind, v)
    for dial, (lo, hi) in quiz.DIALS.items()
    for kind in quiz.KINDS_FOR[dial] if kind != "REASON"
    for v in range(lo, hi + 1)
] + [template_situation(dial, v) for dial, v in _SITUATIONS]


def dev_bank(path: Path = bank.BANK_FILE) -> Bank:
    """The real question bank once it has been made (checked as the server checks it), else the template questions."""
    return bank.load(path) if path.is_file() else Bank(TEMPLATE_ENTRIES)


class TemplateWriter:
    def write(self, system: str, user: str) -> tuple[Draft, str]:
        # prompt.py writes each scene as "[key=Q:MATH:FAN:5]"
        keys = re.findall(r"\[key=([^\]]+)\]", user)
        wish = re.search(r"가족이 바란 이야기: «([^»]+)»", user)
        world = wish.group(1) if wish else "별빛 기차"
        slots = len(re.findall(r"^\d+번째 퍼즐", user, flags=re.M))
        scenes = []
        for key in keys:
            q = quiz.parse(key)
            if q is not None:
                # the lines name no world, number or button, so they never give the answer away; the question and its
                # hint are put in from the bank afterwards
                scenes.append(DraftScene(key=key, bubble="[개발용] 장면", speech=f"[개발용] {quiz.friend(q)} 장면이야.", hint=""))
                continue
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
            # the checker drops the whole draft unless the gift line names the 안전 지킴이
            treasureGift="[개발용] 안전 지킴이가 글로브박스에서 선물을 꺼내 줄 거야.",
            treasureBadge="[개발용] 배지 받았어!",
            scenes=scenes,
            art=Art(cover=f"{world} 표지", puzzles=[f"{world} 퍼즐 {_SLOT_NAMES[i]}" for i in range(slots)], ending=f"{world} 마지막"),
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
        found = re.search(f"퍼즐 ([{_SLOT_NAMES}])", description)
        n = _SLOT_NAMES.index(found.group(1)) + 1 if found else 0
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
    uvicorn.run(create_app(writer=TemplateWriter(), painter=CardPainter(), bank=dev_bank()), host="127.0.0.1", port=8787)
