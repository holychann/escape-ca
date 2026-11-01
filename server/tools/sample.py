"""Writes N real episodes with random answers and prints how many scenes passed the checker.

    python -m tools.sample 30

Reads keys from .env (ANTHROPIC_API_KEY or GEMINI_API_KEY, see .env.example). Every episode is a paid API call.
Drafts and verdicts go to data/sample-<time>.jsonl for reading through.
"""
import json
import random
import sys
import time
from collections import Counter
from dotenv import load_dotenv
from datetime import datetime
from pathlib import Path

from app.checker import check
from app.models import EpisodeRequest
from app.prompt import SYSTEM, user_message
from app.writer import make_writer

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
    lambda r: [f"TOGETHER:{r.choice(['SAME_TIME', 'CHILD_ONCE_PARENT_TWICE', 'FAN_WHILE_LIT'])}:{r.choice(['REACH', 'REACH', 'TIMING'])}"],
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


def main(n: int) -> None:
    load_dotenv()
    r = random.Random(7)
    writer = make_writer()
    out = Path("data") / f"sample-{datetime.now():%Y%m%d-%H%M}.jsonl"
    out.parent.mkdir(exist_ok=True)
    scenes = Counter()
    usable = failed_calls = 0
    problems = Counter()
    seconds = []
    tokens = []
    with open(out, "w", encoding="utf-8") as f:
        last_start = -MIN_GAP_S
        for i in range(n):
            req = random_request(r)
            time.sleep(max(0.0, last_start + MIN_GAP_S - time.monotonic()))
            started = last_start = time.monotonic()
            try:
                draft, model = writer.write(SYSTEM, user_message(req))
            except Exception as e:
                failed_calls += 1
                print(f"{i + 1}/{n} writer failed: {e!r}"[:300])
                time.sleep(AFTER_FAILURE_S)
                continue
            seconds.append(time.monotonic() - started)
            # only the Gemini writer keeps token counts
            u = getattr(writer, "last_usage", None)
            if u is not None:
                tokens.append((u.prompt_token_count or 0, (u.candidates_token_count or 0) + (u.thoughts_token_count or 0)))
            v = check(draft, req)
            usable += v.usable
            scenes["total"] += len(req.keys)
            scenes["passed"] += len(req.keys) - len(v.scenes)
            for ps in [v.episode, *v.scenes.values()]:
                for p in ps:
                    problems[p.split(":")[0]] += 1
            f.write(json.dumps({"request": req.model_dump(), "draft": draft.model_dump(), "episode": v.episode,
                                "scenes": v.scenes, "model": model, "tokens": tokens[-1] if u else None}, ensure_ascii=False) + "\n")
            print(f"{i + 1}/{n} {seconds[-1]:.1f}s episode={'ok' if v.usable else v.episode} dropped={list(v.scenes)}")
    done = n - failed_calls
    print(f"\nwritten {done}/{n}, usable episodes {usable}/{done}, scenes passed {scenes['passed']}/{scenes['total']}")
    if seconds:
        print(f"seconds per draft: median {sorted(seconds)[len(seconds) // 2]:.1f}, max {max(seconds):.1f}")
    if tokens:
        t_in = sum(t[0] for t in tokens) / len(tokens)
        t_out = sum(t[1] for t in tokens) / len(tokens)
        usd = (t_in * PRICE_IN + t_out * PRICE_OUT) / 1e6
        print(f"tokens per draft: in {t_in:.0f}, out {t_out:.0f} (thinking included), "
              f"about ${usd:.4f} a draft, ${usd * len(tokens):.3f} for this run")
    print("problems:", dict(problems.most_common()))
    print("drafts:", out)


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 10)
