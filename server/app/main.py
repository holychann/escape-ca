"""차량탈출 AI 이야기 서버.

POST /v1/episode                    the car sends this episode's answer keys (and maybe the family's story wish)
                                    and gets checked story lines back, with pictures when it asked for them
GET  /v1/images/{storyId}/{name}    one of those pictures (WebP), or today's new friend's portrait
GET  /v1/audio/{storyId}/{name}.wav one of Lumi's lines read aloud (WAV)
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
from .models import Episode, EpisodeRequest, Report
from .painter import Painter, make_painter
from .service import make_episode, paint_pictures, read_aloud
from .store import DailyLimit, Store
from .voice import Voice, make_voice
from .writer import Writer, make_writer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("story")

_UNSET = object()


def create_app(
    writer: Writer | None = None,
    data_dir: Path | None = None,
    painter: Painter | None = _UNSET,
    voice: Voice | None = _UNSET,
) -> FastAPI:
    app = FastAPI(title="차량탈출 이야기 서버", docs_url=None, redoc_url=None)
    writer = writer or make_writer()
    painter = make_painter() if painter is _UNSET else painter
    voice = make_voice() if voice is _UNSET else voice
    folder = data_dir or Path(os.environ.get("STORY_DATA_DIR", "data"))
    store = Store(folder)
    images = folder / "images"
    audio = folder / "audio"
    limit = DailyLimit(
        per_install=int(os.environ.get("STORY_DAILY_PER_INSTALL", "20")),
        per_ip=int(os.environ.get("STORY_DAILY_PER_IP", "200")),
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
        made = make_episode(req, writer)
        if made is None:
            # the car plays its own reviewed lines
            raise HTTPException(status_code=502, detail="story_rejected")
        ep, draft = made
        # today's new friend gets its portrait for the friend book on every story; the scenes only when asked
        if painter is not None and (req.pictures or ep.friend is not None):
            ep = paint_pictures(ep, draft, req, painter, images, scenes=req.pictures)
        # Lumi's lines read aloud; a line without a recording is read by the car's own voice
        if voice is not None:
            ep = read_aloud(ep, voice, audio)
        store.episode(ep, req)
        return ep

    @app.get("/v1/images/{story_id}/{name}.webp")
    def image(story_id: str, name: str) -> FileResponse:
        if not re.fullmatch(r"[0-9a-f]{32}", story_id) or not re.fullmatch(r"cover|ending|friend|p[1-6]", name):
            raise HTTPException(status_code=404)
        path = images / story_id / f"{name}.webp"
        if not path.is_file():
            raise HTTPException(status_code=404)
        return FileResponse(path, media_type="image/webp")

    @app.get("/v1/audio/{story_id}/{name}.wav")
    def recording(story_id: str, name: str) -> FileResponse:
        if not re.fullmatch(r"[0-9a-f]{32}", story_id) or not re.fullmatch(r"[A-Za-z0-9_.]{1,64}", name) or ".." in name:
            raise HTTPException(status_code=404)
        path = audio / story_id / f"{name}.wav"
        if not path.is_file():
            raise HTTPException(status_code=404)
        return FileResponse(path, media_type="audio/wav")

    @app.post("/v1/report", status_code=204)
    def report(r: Report) -> Response:
        store.report(r)
        return Response(status_code=204)

    return app


app = create_app()
