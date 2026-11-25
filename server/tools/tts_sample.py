"""Reads a few of Lumi's lines with several Gemini voices, to pick one by ear. Paid calls (voices x lines).

    python -m tools.tts_sample                         (needs GEMINI_API_KEY; reads .env)
    python -m tools.tts_sample --voices Leda,Puck      only these voices
    python -m tools.tts_sample --plain                 also each line read plainly, to hear what the style adds
    python -m tools.tts_sample --out data/tts_sample   where the WAV files go (the default)

The lines are the app's own (car/app/.../story/Lines.kt): the greeting, the dust puzzle and the praise.
"""
import argparse
import os
from pathlib import Path

from app.voice import PLAIN, STYLE, GeminiVoice

VOICES = ["Leda", "Puck", "Sadachbia", "Laomedeia", "Achird", "Vindemiatrix"]
LINES = {
    "intro": "안녕, 탐험대장! 나는 루미야. 오늘 밤 별 축제에 가야 하는데 별빛 기차 칸 곳곳이 고장 났대. 펭귄 선장과 친구들을 도와 기차를 고쳐 보자! 단서를 듣고 버튼을 찾으면, 손가락으로 가리키며 안전 지킴이에게 알려 줘.",
    "dust": "수달 요리사가 큰일이래! 밖에서 먼지 구름이 몰려와서 수프에 먼지가 들어가겠어. 바깥 공기가 들어오지 않게 막는 버튼을 찾아서 안전 지킴이에게 가리켜 줘.",
    "success": "고쳤다! 기차 칸 하나가 반짝 살아났어. 우리 탐험대장 최고야!",
}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--voices", default=",".join(VOICES), help="comma separated voice names")
    ap.add_argument("--plain", action="store_true", help="also record each line in a plain tone")
    ap.add_argument("--out", default="data/tts_sample", type=Path)
    args = ap.parse_args()

    try:
        from dotenv import load_dotenv
        load_dotenv()
    except ImportError:
        pass
    if not os.environ.get("GEMINI_API_KEY"):
        raise SystemExit("GEMINI_API_KEY가 없어요 (.env)")

    args.out.mkdir(parents=True, exist_ok=True)
    tones = {"story": STYLE} | ({"plain": PLAIN} if args.plain else {})
    client = None
    for name in [v.strip() for v in args.voices.split(",") if v.strip()]:
        for tone, style in tones.items():
            voice = GeminiVoice(voice=name, style=style, client=client)
            client = voice.client
            for line, text in LINES.items():
                path = args.out / f"{name}_{tone}_{line}.wav"
                try:
                    path.write_bytes(voice.speak(text))
                    print(f"ok   {path}")
                except Exception as e:
                    print(f"fail {path}: {e!r}")


if __name__ == "__main__":
    main()
