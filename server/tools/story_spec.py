"""What textgate needs to measure the story writer: random requests, the real writer and the checker.

    textgate sample tools.story_spec:SPEC -n 30                 every request is a paid API call
    textgate recheck tools.story_spec:SPEC data/sample-*.jsonl  the saved drafts against today's rules, free

Reads keys from .env (ANTHROPIC_API_KEY or GEMINI_API_KEY, see .env.example). Drafts and verdicts go to
data/sample-<time>.jsonl for reading through.
"""
import random

from dotenv import load_dotenv
from textgate import Spec, Written

from app.checker import check
from app.models import Draft, EpisodeRequest
from app.prompt import SYSTEM, user_message
from app.writer import Writer, make_writer

# the free tier allows 5 requests a minute; failures come back fast, so pace by start time
MIN_GAP_S = 15
AFTER_FAILURE_S = 30
# gemini-3.6-flash paid prices in USD per 1M tokens until 2026-12-31 (thinking is billed as output); doubled from 2027
PRICE_IN, PRICE_OUT = 0.75, 3.75

SLOTS = [
    lambda r: [f"WIND:{d}:{s}" for d in r.sample(["FACE", "FLOOR"], 2) for s in [r.choice(["GENTLE", "STRONG"])]],
    lambda r: r.sample(["DUST:OUTSIDE", "DUST:INSIDE"], 2),
    lambda r: r.sample(["ROOMS:LEFT", "ROOMS:RIGHT"], 2),
    lambda r: ["FOG"],
    lambda r: [f"SEAT:{s}" for s in r.sample(["DRIVER", "PASSENGER", "REAR_LEFT", "REAR_RIGHT"], 4)],
    lambda r: [f"TOGETHER:{r.choice(['SAME_TIME', 'CHILD_ONCE_PARENT_TWICE', 'FAN_WHILE_LIT'])}"],
]


def random_request(r: random.Random) -> EpisodeRequest:
    picks = [f(r) for f in r.sample(SLOTS, r.choice([2, 3, 4]))]
    if len(picks) >= 3:
        picks.insert(2, ["BELT"])
    return EpisodeRequest(
        installId="00000000-0000-0000-0000-000000000000",
        ages=[r.choice(["LITTLE", "CAPTAIN", "DETECTIVE"])],
        minutes=r.choice([5, 10, 15]),
        context={
            "timeOfDay": r.choice(["MORNING", "AFTERNOON", "EVENING", "NIGHT"]),
            "season": r.choice(["SPRING", "SUMMER", "AUTUMN", "WINTER"]),
            "outsideTempC": r.choice([None, -3, 8, 18, 31]),
        },
        puzzles=picks,
    )


_writer: Writer | None = None


def write(req: EpisodeRequest) -> Written:
    global _writer
    if _writer is None:
        # made on first call, so recheck never needs a key
        load_dotenv()
        _writer = make_writer()
    draft, model = _writer.write(SYSTEM, user_message(req))
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
