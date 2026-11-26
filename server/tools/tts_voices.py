"""Looks a voice name up in Gemini's voice list (the 30 prebuilt voices and the Extended Voice Library).

    python -m tools.tts_voices Sira Sami

It only lists voices (GET /v1beta/voices); nothing is read aloud. Use it to check TTS_VOICE before the first story.
"""
import os
import sys

from app.voice import TTS_VOICE

# the 30 prebuilt voices in the speech generation guide
PREBUILT = {
    "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe", "Enceladus",
    "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam",
    "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat",
}


def main() -> None:
    try:
        from dotenv import load_dotenv
        load_dotenv()
    except ImportError:
        pass
    names = sys.argv[1:] or [os.environ.get("TTS_VOICE", TTS_VOICE)]
    if not os.environ.get("GEMINI_API_KEY"):
        for n in names:
            print(f"{n}: {'기본 목소리 30개에 있음' if n in PREBUILT else '기본 목록에 없음 (키를 넣으면 확장 목록도 찾아봄)'}")
        return
    from google import genai

    client = genai.Client()
    for n in names:
        if n in PREBUILT:
            print(f"{n}: 기본 목소리 30개에 있음 -> TTS_VOICE={n}")
            continue
        found = []
        listed = client.voices.list(search=n)
        # a list response or a pager, depending on the SDK version
        voices = getattr(listed, "voices", None)
        for v in voices if voices is not None else listed:
            label = getattr(v, "display_name", "") or ""
            vid = getattr(v, "id", "") or ""
            if n.lower() in (label.lower(), vid.lower()) or n.lower() in label.lower():
                found.append((vid, label, getattr(v, "language_code", ""), getattr(v, "description", "")))
        if not found:
            print(f"{n}: 없음")
        for vid, label, lang, desc in found:
            print(f"{n}: 있음 -> TTS_VOICE={vid or label}  ({label}, {lang}) {desc}")


if __name__ == "__main__":
    main()
