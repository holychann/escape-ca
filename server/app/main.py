"""차량탈출 AI 이야기 서버.

POST /v1/episode  the car sends this episode's answer keys and gets checked story lines back
POST /v1/report   a parent reports a story (이야기 신고)
GET  /health
"""
import logging
import os
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request, Response

from .models import Episode, EpisodeRequest, Report
from .service import make_episode
from .store import DailyLimit, Store
from .writer import Writer, make_writer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")


def create_app(writer: Writer | None = None, data_dir: Path | None = None) -> FastAPI:
    app = FastAPI(title="차량탈출 이야기 서버", docs_url=None, redoc_url=None)
    writer = writer or make_writer()
    store = Store(data_dir or Path(os.environ.get("STORY_DATA_DIR", "data")))
    limit = DailyLimit(
        per_install=int(os.environ.get("STORY_DAILY_PER_INSTALL", "20")),
        per_ip=int(os.environ.get("STORY_DAILY_PER_IP", "200")),
    )

    @app.get("/health")
    def health() -> dict:
        return {"ok": True}

    @app.post("/v1/episode", response_model=Episode)
    def episode(req: EpisodeRequest, request: Request) -> Episode:
        ip = request.client.host if request.client else "unknown"
        if not limit.take(req.installId, ip):
            raise HTTPException(status_code=429, detail="daily_limit")
        ep = make_episode(req, writer)
        if ep is None:
            # the car plays its own reviewed lines
            raise HTTPException(status_code=502, detail="story_rejected")
        store.episode(ep, req)
        return ep

    @app.post("/v1/report", status_code=204)
    def report(r: Report) -> Response:
        store.report(r)
        return Response(status_code=204)

    return app


app = create_app()
