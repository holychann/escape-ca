"""차량탈출 AI 이야기 서버.

POST /v1/episode                    the car sends this episode's answer keys (and maybe the family's story wish)
                                    and gets checked story lines back, with pictures when it asked for them
GET  /v1/images/{storyId}/{name}    one of those pictures (WebP)
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
from .service import make_episode, paint_pictures
from .store import DailyLimit, Store
from .writer import Writer, make_writer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("story")

_UNSET = object()


def create_app(writer: Writer | None = None, data_dir: Path | None = None, painter: Painter | None = _UNSET) -> FastAPI:
    app = FastAPI(title="차량탈출 이야기 서버", docs_url=None, redoc_url=None)
    writer = writer or make_writer()
    painter = make_painter() if painter is _UNSET else painter
    folder = data_dir or Path(os.environ.get("STORY_DATA_DIR", "data"))
    store = Store(folder)
    images = folder / "images"
    limit = DailyLimit(
        per_install=int(os.environ.get("STORY_DAILY_PER_INSTALL", "20")),
        per_ip=int(os.environ.get("STORY_DAILY_PER_IP", "200")),
    )
    log.info("writer=%s painter=%s", type(writer).__name__, type(painter).__name__ if painter else "none")

    @app.get("/health")
    def health() -> dict:
        return {"ok": True, "pictures": painter is not None}

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
        if req.pictures and painter is not None:
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

    @app.post("/v1/report", status_code=204)
    def report(r: Report) -> Response:
        store.report(r)
        return Response(status_code=204)

    return app


app = create_app()
