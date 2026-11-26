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
import os
import re
import time
from pathlib import Path

import uvicorn
from PIL import Image, ImageDraw

from app import bank, quiz
from app.bank import Bank, Entry
from app.library import FolderStorage, Library, recheck
from app.main import create_app
from app.models import FRIEND_LABEL, Art, Draft, DraftScene
from app.painter import to_webp
from app.rules import rule_for


# picture names in the art descriptions: a digit there could be an answer and keep the picture from being drawn
_SLOT_NAMES = "가나다라마바"

# the bank refuses brackets in a question or hint (bank.ODD), so the template questions are marked with a colon
_DEV = "개발용:"
_HINT = f"{_DEV} 천천히 다시 생각해 봐."

# a situation for every function a situation puzzle can ask, held to the bank's rules (bank.reason_problems): the
# chosen friend's trouble as "나", no name, cause or button name, and a request to solve it with the car's buttons
_SITUATIONS = {
    ("AC", 1): "나 땀이 뻘뻘 나, 바람은 나오는데 안 시원해. 창문은 닫아 둔 채로 알맞은 차량 버튼을 찾아 나를 도와줘!",
    ("AC", 0): "나 추운데 더울 때 켜는 시원한 바람이 켜져 있어. 온도 숫자는 그대로 두고 알맞은 차량 버튼을 찾아 나를 도와줘!",
    ("RECIRC", 1): "바람 구멍 앞에서만 이상한 냄새가 나. 냄새는 차 밖에서 오는 것 같아. 알맞은 차량 버튼을 찾아 나를 도와줘!",
    ("RECIRC", 0): "같은 공기만 빙글빙글 돌아서 나는 숨이 답답해. 창문은 닫아 둔 채로, 알맞은 차량 버튼을 찾아 나를 도와줘!",
    ("DEFROST", 1): "앞 유리 안쪽이 뿌예지려고 해, 와이퍼는 바깥만 닦아. 알맞은 차량 버튼을 찾아 나를 도와줘!",
    ("TEMP_UP", 1): "내 몸이 으슬으슬 떨려. 의자 말고 차 안 공기를 따뜻하게, 알맞은 차량 버튼을 찾아 나를 도와줘!",
    ("TEMP_DOWN", 1): "에어컨은 켜져 있는데 나는 아직 조금 더워. 바람이 세지면 종이가 날아가니까 알맞은 차량 버튼을 찾아 나를 도와줘!",
    ("FAN_UP", 1): "내 바람개비가 느릿느릿 돌아. 빙글빙글 빨리 돌게 알맞은 차량 버튼을 찾아 나를 도와줘!",
    ("FAN_DOWN", 1): "바람이 쌩쌩 불어서 내 그림 종이가 날아가. 바람은 조금 나와야 하니까 알맞은 차량 버튼을 찾아 나를 도와줘!",
    ("SEAT_HEAT_ON", 1): "안전 지킴이 의자가 차가워졌어. 차 안 공기 말고 의자를 데우게 알맞은 차량 버튼을 찾아 안전 지킴이를 도와줘!",
    ("SEAT_HEAT_OFF", 0): "안전 지킴이 의자가 계속 뜨끈해. 의자가 그만 데워지게 알맞은 차량 버튼을 찾아 안전 지킴이를 도와줘!",
    ("LIGHT_CHILD", 1): "내가 준 그림 지도가 개미만큼 작아서 네 자리가 더 밝아야 해. 알맞은 차량 버튼을 찾아 나를 도와줘!",
    ("LIGHTS_OFF", 0): "작은 불이 하나도 안 켜져 있어야 별 스티커가 보여. 알맞은 차량 버튼을 찾아 나를 도와줘!",
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
# a sum is asked first and the instruction says "그 수", so no number of it sounds like the answer (bank.wording_problems)
_THAT_NUMBER = {
    "FAN": "그 수만큼 바람 세기를 맞춰 줘!",
    "TEMP_UP": "그 수만큼 온도를 올려 줘!",
    "TEMP_DOWN": "그 수만큼 온도를 내려 줘!",
    "LIGHTS": "켜진 독서등이 모두 그 수만큼 되게 해 줘!",
    "SEAT_HEAT": "의자 열선을 그 수와 같은 단으로 맞춰 줘!",
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
        a, b, op = (3, 2, "빼기") if v == 1 else (v // 2, v - v // 2, "더하기")
        text = f"{_DEV} {a} {op} {b}{bank.topic_particle(b)} 얼마일까? {_THAT_NUMBER[dial]}"
        return Entry(**base, text=text, expr=f"{a}{'-' if v == 1 else '+'}{b}")
    if kind == "ENGLISH":
        # asked first like a sum: "two는 숫자로 몇일까? 그 수만큼 ..."; past five a number word is for 7~9
        word = quiz.ENGLISH_OF[v]
        age = "LITTLE" if v <= quiz.english_reach("LITTLE") else "CAPTAIN"
        text = f"{_DEV} {word.lower()}{quiz.NUMBER_TOPIC[word]} 숫자로 몇일까? {_THAT_NUMBER[dial]}"
        return Entry(**{**base, "age": age}, text=text, word=word)
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
        # the checker wants the chosen friend to introduce itself
        friend = next((name for name, *_ in FRIEND_LABEL.values() if f"('나'): {name}." in user), "반테")
        slots = len(re.findall(r"^\d+번째 퍼즐", user, flags=re.M))
        scenes = []
        for key in keys:
            q = quiz.parse(key)
            if q is not None:
                # the lines name no world, number or button, so they never give the answer away; the question and its
                # hint are put in from the bank afterwards
                scenes.append(DraftScene(key=key, bubble="[개발용] 장면", speech="[개발용] 문제 앞 장면이야.", hint="",
                                         result="[개발용] 풀었어, 다음으로 가자!"))
                continue
            rule = rule_for(key)
            words = " ".join(group[0] for group in rule.must)
            ask = "" if not rule.needs_guardian else " 찾으면 안전 지킴이에게 가리켜 알려 주자!"
            hint = " ".join(group[0] for group in rule.hint_must) or "다시 들어 보자."
            scenes.append(DraftScene(key=key, bubble="[개발용] 장면", speech=f"[개발용] {words}.{ask}", hint=f"[개발용] {hint}",
                                     result="[개발용] 준비 끝, 다음으로 가자!"))
        # with the shelf on the request names its theme in the prompt ("theme에 SEA_SUB"), and the checker drops a draft
        # with another one
        named = re.search(r"theme에 (STAR_TRAIN|SEA_SUB|FOREST_SHIP)", user)
        draft = Draft(
            theme=named.group(1) if named else "STAR_TRAIN",
            world=world,
            title=f"[개발용] {world}"[:20],
            intro=f"[개발용] 나는 {friend}야. 서버에서 받은 이야기야.",
            treasureBadge="[개발용] 배지 받았어!",
            scenes=scenes,
            art=Art(cover=f"{world} 표지", puzzles=[f"{world} 퍼즐 {_SLOT_NAMES[i]}" for i in range(slots)]),
        )
        return draft, "dev-template"


class CardPainter:
    """A plain coloured card per picture, after a wait (DEV_PAINT_SECONDS, default 1.5), so the app's waiting
    screen can be seen without paying for real pictures."""

    COLORS = [(255, 197, 49), (255, 122, 89), (46, 196, 166), (77, 163, 255), (155, 123, 255), (30, 43, 94)]

    def paint(self, description: str) -> bytes:
        time.sleep(float(os.environ.get("DEV_PAINT_SECONDS", "1.5")))
        found = re.search(f"퍼즐 ([{_SLOT_NAMES}])", description)
        n = _SLOT_NAMES.index(found.group(1)) + 1 if found else 0
        return self._card((1536, 1024), self.COLORS[n % len(self.COLORS)], f"DEV PICTURE {'cover' if n == 0 else n}")

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


def dev_app():
    """The dev app with a shelf on a local folder (STORY_LIBRARY_DIR, default data/library-dev). The dev writer is called
    dev-template, which the default model list would not allow, so every writer is allowed here."""
    dev = dev_bank()
    folder = Path(os.environ.get("STORY_LIBRARY_DIR") or "data/library-dev")
    library = Library(FolderStorage(folder), models=())
    library.load(lambda s, d, r: recheck(s, d, r, dev))
    return create_app(writer=TemplateWriter(), painter=CardPainter(), bank=dev, library=library)


if __name__ == "__main__":
    uvicorn.run(dev_app(), host="127.0.0.1", port=8787)
