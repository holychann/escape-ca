"""The story shelf: checked stories kept for other cars to reuse.

A story is shelved as two objects in a folder named by its cell (friend, youngest age, minutes):
``library/{friend}/{youngest}/{minutes}m/{storyId}/draft.json`` (the writer's draft and the request it answered,
without the install id, wish and context) and ``story.json`` (what the car gets, with its meta). ``story.json`` is written last, so a
folder with only one of the two is never indexed. Nothing here is personal: no install id, IP, wish, season or time
of day is stored.

Storage is one bucket (S3) or a local folder (dev server, tests). The index of what is shelved lives in memory and is
read back from the storage when the server starts.
"""
import json
import logging
import os
import threading
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from pydantic import BaseModel

from .models import Age, Draft, Episode, EpisodeRequest, FriendCode, Lesson, Theme

log = logging.getLogger("story")
ROOT = "library/"
# the install id put back into a stored request when it is read: a shelved story belongs to no install
SHELF_INSTALL = "0" * 16
# a placeholder only, put back where the request needs a context: the checks never read it
SHELF_CONTEXT = {"timeOfDay": "AFTERNOON", "season": "SPRING"}
DEFAULT_MODELS = "claude"


class Storage(Protocol):
    def put(self, path: str, data: bytes, content_type: str) -> None: ...

    def get(self, path: str) -> bytes | None:
        """None when there is no such object."""

    def list(self, prefix: str) -> list[str]:
        """Every object path under prefix, at any depth."""


class FolderStorage:
    """Storage on a local folder."""

    def __init__(self, root: Path):
        self.root = Path(root)

    def put(self, path: str, data: bytes, content_type: str) -> None:
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)

    def get(self, path: str) -> bytes | None:
        target = self.root / path
        return target.read_bytes() if target.is_file() else None

    def list(self, prefix: str) -> list[str]:
        return sorted(p.relative_to(self.root).as_posix() for p in self.root.rglob("*")
                      if p.is_file() and p.relative_to(self.root).as_posix().startswith(prefix))


class S3Storage:
    """Storage on one bucket, every path under ``prefix``."""

    def __init__(self, bucket: str, prefix: str = "", client=None):
        self.bucket = bucket
        self.prefix = prefix.strip("/") + "/" if prefix.strip("/") else ""
        if client is None:
            import boto3
            from botocore.config import Config
            client = boto3.client("s3", config=Config(connect_timeout=3, read_timeout=10, retries={"max_attempts": 2}))
        self.client = client

    def put(self, path: str, data: bytes, content_type: str) -> None:
        self.client.put_object(Bucket=self.bucket, Key=self.prefix + path, Body=data, ContentType=content_type)

    def get(self, path: str) -> bytes | None:
        from botocore.exceptions import ClientError
        try:
            return self.client.get_object(Bucket=self.bucket, Key=self.prefix + path)["Body"].read()
        except ClientError as e:
            if e.response.get("Error", {}).get("Code") in ("NoSuchKey", "404"):
                return None
            raise

    def list(self, prefix: str) -> list[str]:
        out = []
        for page in self.client.get_paginator("list_objects_v2").paginate(Bucket=self.bucket, Prefix=self.prefix + prefix):
            out += [o["Key"][len(self.prefix):] for o in page.get("Contents", [])]
        return out


@dataclass(frozen=True)
class Cell:
    friend: str
    youngest: str
    minutes: int

    def path(self) -> str:
        return f"library/{self.friend}/{self.youngest}/{self.minutes}m"


class ShelfMeta(BaseModel):
    ages: list[Age]
    minutes: int
    friend: FriendCode
    lesson: Lesson | None
    theme: Theme
    # the writer model as the episode names it
    writer: str
    # ISO date (UTC), e.g. "2026-11-26"
    made: str
    # Bank.digest() when shelved
    bank: str


class Shelved(BaseModel):
    """story.json."""

    meta: ShelfMeta
    # the request's slots, alternates included
    puzzles: list[list[str]]
    # exactly what the car gets (no images)
    episode: Episode

    @property
    def cell(self) -> Cell:
        youngest = min(self.meta.ages, key=["LITTLE", "CAPTAIN", "DETECTIVE"].index)
        return Cell(self.meta.friend, youngest, self.meta.minutes)


Check = Callable[[Shelved, Draft, EpisodeRequest], list[str]]


class Library:
    def __init__(self, storage: Storage, models: tuple[str, ...] = (), cap: int = 200, min_fresh: int = 30,
                 fresh: float = 0.3, offer: int = 10, blocks_per_ip: int = 5,
                 run: Callable[[Callable[[], None]], object] | None = None):
        self.storage = storage
        self.models = models
        self.cap, self.min_fresh, self.fresh, self.offer, self.blocks_per_ip = cap, min_fresh, fresh, offer, blocks_per_ip
        self.run = run
        # request threads and the shelving job both touch the index
        self.lock = threading.Lock()
        self.stories: dict[str, Shelved] = {}
        self.cells: dict[Cell, list[str]] = {}

    def allows(self, model: str) -> bool:
        return not self.models or model.startswith(self.models)

    def _index(self, shelved: Shelved) -> None:
        with self.lock:
            sid = shelved.episode.storyId
            if sid not in self.stories:
                self.cells.setdefault(shelved.cell, []).append(sid)
            self.stories[sid] = shelved

    def add(self, shelved: Shelved, draft: Draft, request: EpisodeRequest) -> None:
        base = f"{shelved.cell.path()}/{shelved.episode.storyId}"
        # the draft first and story.json last: a half-written folder is never indexed
        draft_body = {"draft": draft.model_dump(), "request": request.model_dump(exclude={"installId", "wish", "context"})}
        self.storage.put(f"{base}/draft.json", json.dumps(draft_body, ensure_ascii=False).encode("utf-8"),
                         "application/json")
        self.storage.put(f"{base}/story.json", shelved.model_dump_json().encode("utf-8"), "application/json")
        self._index(shelved)

    def get(self, story_id: str) -> Shelved | None:
        with self.lock:
            return self.stories.get(story_id)

    def size(self, cell: Cell) -> int:
        with self.lock:
            return len(self.cells.get(cell, ()))

    def load(self, check: Check | None = None) -> None:
        """Index every complete story in the storage; a broken, half-written, unallowed or failing one is skipped."""
        for path in self.storage.list(ROOT):
            if not path.endswith("/story.json"):
                continue
            try:
                shelved = Shelved.model_validate_json(self.storage.get(path) or b"")
                raw = self.storage.get(path.removesuffix("story.json") + "draft.json")
                if raw is None:
                    log.warning("shelf: %s has no draft.json, skipped", path)
                    continue
                body = json.loads(raw)
                draft = Draft.model_validate(body["draft"])
                request = EpisodeRequest(installId=SHELF_INSTALL, context=SHELF_CONTEXT, **body["request"])
            except Exception as e:
                log.warning("shelf: %s cannot be read, skipped: %s", path, e)
                continue
            if not self.allows(shelved.meta.writer) or not self.allows(shelved.episode.model):
                log.info("shelf: %s was written by %s, skipped", path, shelved.meta.writer)
                continue
            if check and (problems := check(shelved, draft, request)):
                log.warning("shelf: %s fails its check, skipped: %s", path, problems)
                continue
            self._index(shelved)


def _int(name: str, default: int) -> int:
    return int(os.environ.get(name) or default)


def make_library(check: Check | None = None) -> Library | None:
    """The shelf the environment asks for; None when it is off or cannot be loaded (the server then runs without)."""
    try:
        if bucket := os.environ.get("STORY_LIBRARY_BUCKET"):
            storage: Storage = S3Storage(bucket, os.environ.get("STORY_LIBRARY_PREFIX", ""))
        elif folder := os.environ.get("STORY_LIBRARY_DIR"):
            storage = FolderStorage(Path(folder))
        else:
            return None
        models = tuple(m.strip() for m in os.environ.get("STORY_LIBRARY_MODELS", DEFAULT_MODELS).split(",") if m.strip())
        lib = Library(storage, models=models, cap=_int("STORY_LIBRARY_CAP", 200), min_fresh=_int("STORY_LIBRARY_MIN", 30),
                      fresh=float(os.environ.get("STORY_LIBRARY_FRESH") or 0.3),
                      offer=_int("STORY_LIBRARY_CANDIDATES", 10), blocks_per_ip=_int("STORY_LIBRARY_BLOCKS_PER_IP", 5))
        lib.load(check)
        return lib
    except Exception:
        log.exception("the story shelf cannot be loaded: the server runs without it")
        return None
