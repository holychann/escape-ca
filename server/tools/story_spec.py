"""What textgate needs to measure the story writer: random requests, the real writer and the checker.

    textgate sample tools.story_spec:SPEC -n 30                 every request is a paid API call
    textgate recheck tools.story_spec:SPEC data/sample-*.jsonl  the saved drafts against today's rules, free

Reads keys from .env (ANTHROPIC_API_KEY or GEMINI_API_KEY, see .env.example). Each story is written around questions
drawn from the question bank (app/question_bank.json), as the server does, and saved with them. Drafts and verdicts
go to data/sample-<time>.jsonl for reading through.
"""
import random

from dotenv import load_dotenv
from textgate import Spec, Written

from app import quiz
from app.bank import Bank, load as load_bank
from app.checker import check
from app.models import FRIEND_LABEL, Draft, EpisodeRequest
from app.prompt import SYSTEM, user_message
from app.service import pick_questions, put_questions
from app.writer import Writer, make_writer

# the free tier allows 5 requests a minute; failures come back fast, so pace by start time
MIN_GAP_S = 15
AFTER_FAILURE_S = 30
# gemini-3.6-flash paid prices in USD per 1M tokens until 2026-12-31 (thinking is billed as output); doubled from 2027
PRICE_IN, PRICE_OUT = 0.75, 3.75

# quizzes per waiting time, as the car plans them (car Planner.puzzleCount, less the belt mission)
PUZZLES = {5: 2, 10: 3, 15: 4}

# the car's devices and the dials each one is asked on (car Puzzles.kt Dial.device)
DEVICES = {
    "FAN_SPEED": ("FAN", "FAN_UP", "FAN_DOWN"),
    "TEMPERATURE": ("TEMP_UP", "TEMP_DOWN"),
    "READING_LIGHT": ("LIGHTS", "LIGHT_CHILD", "LIGHTS_OFF"),
    "SEAT_HEAT": ("SEAT_HEAT", "SEAT_HEAT_ON", "SEAT_HEAT_OFF"),
    "AC": ("AC",),
    "RECIRCULATION": ("RECIRC",),
    "DEFROST": ("DEFROST",),
}


def options(device: str, age: str) -> list[quiz.QuizKey]:
    """Every quiz the device can ask a child of this age, with every answer its key can take."""
    out = []
    for dial in DEVICES[device]:
        for kind in quiz.KINDS_FOR[dial]:
            if kind == "REASON" and age == "LITTLE" and dial in quiz.REASON_NOT_LITTLE:
                continue
            lo, hi = quiz.values(kind, dial)
            out += [quiz.QuizKey(kind, dial, v) for v in range(lo, hi + 1)]
    return out


def random_quizzes(r: random.Random, count: int, age: str) -> list[list[str]]:
    """Quiz keys the way the car picks them (car Planner.plan): one per device, about half of them a situation
    (REASON), every device that can only tell a situation telling one, and a number kind for each of the others that
    differs where the devices allow it (the most limited device chooses first). The car also skips an answer it
    already shows; the sampler knows no car state, so any answer the key can take is fine."""
    # a device with nothing to ask this age sits the episode out
    devices = r.sample([d for d in DEVICES if options(d, age)], count)
    situations = {d: [q for q in options(d, age) if q.kind == "REASON"] for d in devices}
    numbers = {d: [q for q in options(d, age) if q.kind != "REASON"] for d in devices}
    only = [d for d in devices if not numbers[d]]
    either = [d for d in devices if situations[d] and numbers[d]]
    r.shuffle(either)
    want = max((len(devices) + r.randint(0, 1)) // 2, len(only))
    told = set(only + either[: want - len(only)])
    used: set[str] = set()
    picked = {d: r.choice(situations[d]) for d in devices if d in told}
    for d in sorted((d for d in devices if d not in told), key=lambda d: len({q.kind for q in numbers[d]})):
        kinds = sorted({q.kind for q in numbers[d]})
        r.shuffle(kinds)
        kind = next((k for k in kinds if k not in used), kinds[0])
        used.add(kind)
        picked[d] = r.choice([q for q in numbers[d] if q.kind == kind])
    return [[picked[d].key] for d in devices]


def random_request(r: random.Random) -> EpisodeRequest:
    minutes = r.choice(list(PUZZLES))
    age = r.choice(["LITTLE", "CAPTAIN", "DETECTIVE"])
    picks = random_quizzes(r, PUZZLES[minutes], age)
    # like the app: every course ends with the belt mission once
    picks.append(["BELT"])
    return EpisodeRequest(
        installId="00000000-0000-0000-0000-000000000000",
        ages=[age],
        minutes=minutes,
        context={
            "timeOfDay": r.choice(["MORNING", "AFTERNOON", "EVENING", "NIGHT"]),
            "season": r.choice(["SPRING", "SUMMER", "AUTUMN", "WINTER"]),
            "outsideTempC": r.choice([None, -3, 8, 18, 31]),
        },
        puzzles=picks,
        friend=r.choice(list(FRIEND_LABEL)),
    )


_writer: Writer | None = None
_bank: Bank | None = None


def write(req: EpisodeRequest) -> Written:
    global _writer, _bank
    if _writer is None:
        # made on first call, so recheck never needs a key
        load_dotenv()
        _writer = make_writer()
    if _bank is None:
        _bank = load_bank()
    picks = pick_questions(req, _bank)
    draft, model = _writer.write(SYSTEM, user_message(req, picks))
    # the draft is saved with its questions, so a recheck holds the same ones to the rules of the day
    put_questions(draft, picks)
    # only the Gemini writer keeps token counts
    u = getattr(_writer, "last_usage", None)
    if u is None:
        return Written(draft, model)
    return Written(draft, model, tokens_in=u.prompt_token_count or 0,
                   tokens_out=(u.candidates_token_count or 0) + (u.thoughts_token_count or 0))


SPEC = Spec(
    make_request=random_request,
    write=write,
    judge=lambda req, draft: check(draft, req),
    read_request=EpisodeRequest.model_validate,
    read_draft=Draft.model_validate,
    min_gap_s=MIN_GAP_S,
    after_failure_s=AFTER_FAILURE_S,
    price=(PRICE_IN, PRICE_OUT),
)
