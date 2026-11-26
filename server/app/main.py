"""차량탈출 AI 이야기 서버.

POST /v1/episode                    the car sends this episode's answer keys (and maybe the family's story wish)
                                    and gets checked story lines back, with pictures when it asked for them
GET  /v1/images/{storyId}/{name}    one of those pictures (WebP)
POST /v1/speak                      one of Lumi's lines read aloud (WAV), made once and kept
POST /v1/report                     a parent reports a story (이야기 신고)
GET  /health
"""
import logging
import os
import re
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.responses import FileResponse

from .checker import wish_problems
from .models import Episode, EpisodeRequest, Report, SpeakRequest
from .painter import Painter, make_painter
from .service import make_episode, paint_pictures, speak_line
from .store import DailyLimit, Store
from .voice import Voice, make_voice
from .bank import Bank, load as load_bank
from .writer import Writer, make_writer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("story")

_UNSET = object()


def create_app(
    writer: Writer | None = None,
    data_dir: Path | None = None,
    painter: Painter | None = _UNSET,
    bank: Bank | None = None,
    voice: Voice | None = _UNSET,
) -> FastAPI:
    app = FastAPI(title="차량탈출 이야기 서버", docs_url=None, redoc_url=None)
    writer = writer or make_writer()
    painter = make_painter() if painter is _UNSET else painter
    # the questions made once and checked (tools/make_bank.py); a broken bank stops the server here
    bank = bank or load_bank()
    voice = make_voice() if voice is _UNSET else voice
    folder = data_dir or Path(os.environ.get("STORY_DATA_DIR", "data"))
    store = Store(folder)
    images = folder / "images"
    # recordings by words, voice and style, shared by every story and every car
    audio_cache = folder / "audio_cache"
    limit = DailyLimit(
        per_install=int(os.environ.get("STORY_DAILY_PER_INSTALL", "20")),
        per_ip=int(os.environ.get("STORY_DAILY_PER_IP", "200")),
    )
    # lines read aloud a day: an episode is a few dozen; the paid key has no limit of its own, so this one guards it
    speak_limit = DailyLimit(
        per_install=int(os.environ.get("SPEAK_DAILY_PER_INSTALL", "400")),
        per_ip=int(os.environ.get("SPEAK_DAILY_PER_IP", "2000")),
    )
    log.info("writer=%s painter=%s voice=%s", type(writer).__name__, type(painter).__name__ if painter else "none",
             type(voice).__name__ if voice else "none")

    @app.get("/health")
    def health() -> dict:
        return {"ok": True, "pictures": painter is not None, "voice": voice is not None}

    @app.post("/v1/episode", response_model=Episode)
    def episode(req: EpisodeRequest, request: Request) -> Episode:
        if req.wish and (problems := wish_problems(req.wish)):
            # the parent sees why and can write the wish another way; nothing is sent to a model
            raise HTTPException(status_code=422, detail={"error": "wish_rejected", "reasons": problems})
        ip = request.client.host if request.client else "unknown"
        if not limit.take(req.installId, ip):
            raise HTTPException(status_code=429, detail="daily_limit")
        made = make_episode(req, writer, bank)
        if made is None:
            # the car plays its own reviewed lines
            raise HTTPException(status_code=502, detail="story_rejected")
        ep, draft = made
        if painter is not None and req.pictures:
            ep = paint_pictures(ep, draft, req, painter, images)
        store.episode(ep, req)
        return ep

    @app.get("/v1/images/{story_id}/{name}.webp")
    def image(story_id: str, name: str) -> FileResponse:
        if not re.fullmatch(r"[0-9a-f]{32}", story_id) or not re.fullmatch(r"cover|ending|p[1-6]", name):
            raise HTTPException(status_code=404)
        path = images / story_id / f"{name}.webp"
        if not path.is_file():
            raise HTTPException(status_code=404)
        return FileResponse(path, media_type="image/webp")

    @app.post("/v1/speak")
    def speak(req: SpeakRequest, request: Request) -> Response:
        ip = request.client.host if request.client else "unknown"
        if not speak_limit.take(req.installId, ip):
            raise HTTPException(status_code=429, detail="daily_limit")
        data = speak_line(req.text, voice, audio_cache)
        if data is None:
            # no sound for this line: the app shows it as a caption
            raise HTTPException(status_code=503, detail="no_voice")
        return Response(content=data, media_type="audio/wav")

    @app.post("/v1/report", status_code=204)
    def report(r: Report) -> Response:
        store.report(r)
        return Response(status_code=204)

    return app


def __getattr__(name: str):
    # the served app (uvicorn app.main:app) is made on first use, so importing create_app reads no question bank
    if name == "app":
        made = globals()["app"] = create_app()
        return made
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
