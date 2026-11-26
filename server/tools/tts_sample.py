"""Reads one or two of Lumi's lines aloud, to hear the voice by ear. Every line is one speech call on the key.

    python -m tools.tts_sample --yes                    1 line with TTS_VOICE / TTS_STYLE from .env
    python -m tools.tts_sample --yes --lines 2          the greeting and the praise (at most 2)
    python -m tools.tts_sample --yes --voices Sira,Sami one line per voice (still at most 2 calls in all)
    python -m tools.tts_sample --yes --plain            the same line read plainly, to hear what the style adds
    python -m tools.tts_sample --yes --out data/tts_sample

Nothing is called without --yes. The calls go through the same quota guard as the server (TTS_DAILY_MAX, and a
429 stops the rest), so on a key without billing a used-up free quota only ends the sample.
The lines are the app's own (car/app/.../story/Lines.kt).
"""
import argparse
import os
from pathlib import Path

from app.voice import PLAIN, GeminiVoice, Quota, QuotaError

MAX_CALLS = 2
LINES = {
    "dust": "수달 요리사가 큰일이래! 밖에서 먼지 구름이 몰려와서 수프에 먼지가 들어가겠어. 바깥 공기가 들어오지 않게 막는 버튼을 찾아서 안전 지킴이에게 가리켜 줘.",
    "success": "고쳤다! 기차 칸 하나가 반짝 살아났어. 우리 탐험대장 최고야!",
}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--yes", action="store_true", help="really make the calls")
    ap.add_argument("--lines", type=int, default=1, choices=[1, 2])
    ap.add_argument("--voices", default="", help="comma separated voice names (default: TTS_VOICE)")
    ap.add_argument("--plain", action="store_true", help="read the first line plainly too")
    ap.add_argument("--out", default="data/tts_sample", type=Path)
    args = ap.parse_args()

    try:
        from dotenv import load_dotenv
        load_dotenv()
    except ImportError:
        pass
    if not os.environ.get("GEMINI_API_KEY"):
        raise SystemExit("GEMINI_API_KEY가 없어요 (.env)")

    voices = [v.strip() for v in args.voices.split(",") if v.strip()] or [None]
    jobs = [(v, None, line) for v in voices for line in list(LINES)[: args.lines]]
    if args.plain:
        jobs.append((voices[0], PLAIN, next(iter(LINES))))
    jobs = jobs[:MAX_CALLS]
    if not args.yes:
        for v, style, line in jobs:
            print(f"할 일: {v or os.environ.get('TTS_VOICE', '(기본)')} {'plain' if style else 'story'} {line}")
        raise SystemExit(f"호출 {len(jobs)}번을 하려면 --yes를 붙여 주세요")

    args.out.mkdir(parents=True, exist_ok=True)
    quota = Quota(per_day=int(os.environ.get("TTS_DAILY_MAX", "100")))
    client = None
    for name, style, line in jobs:
        voice = GeminiVoice(voice=name, style=style, client=client, quota=quota)
        client = voice.client
        path = args.out / f"{voice.voice}_{'plain' if style else 'story'}_{line}.wav"
        try:
            path.write_bytes(voice.speak(LINES[line]))
            print(f"ok   {path}")
        except QuotaError as e:
            print(f"stop 무료 한도: {e}")
            break
        except Exception as e:
            print(f"fail {path}: {e!r}")


if __name__ == "__main__":
    main()
