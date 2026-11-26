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
import random
import threading
from concurrent.futures import ThreadPoolExecutor, wait
from datetime import date, datetime, timezone
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from pydantic import BaseModel

from . import checker, quiz
from .bank import Bank
from .checker import result_problems, timeless_problems
from .store import _digest
from .models import Age, Draft, Episode, EpisodeRequest, FriendCode, Lesson, LibraryRequest, Theme

log = logging.getLogger("story")
ROOT = "library/"
BLOCKED = "blocked/"
# the install id put back into a stored request when it is read: a shelved story belongs to no install
SHELF_INSTALL = "0" * 16
# a placeholder only, put back where the request needs a context: the checks never read it
SHELF_CONTEXT = {"timeOfDay": "AFTERNOON", "season": "SPRING"}
DEFAULT_MODELS = "claude"
LOAD_WORKERS = 16
DEFAULT_LOAD_SECONDS = 60.0


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
                 fresh: float = 0.3, offer: int = 10, blocks_per_ip: int = 5, load_seconds: float = DEFAULT_LOAD_SECONDS,
                 run: Callable[[Callable[[], None]], object] | None = None):
        self.storage = storage
        self.models = models
        self.load_seconds = load_seconds
        self.cap, self.min_fresh, self.fresh, self.offer, self.blocks_per_ip = cap, min_fresh, fresh, offer, blocks_per_ip
        self._pool = None if run else ThreadPoolExecutor(max_workers=1)
        self.run = run or self._pool.submit
        # request threads and the shelving job both touch the index
        self.lock = threading.Lock()
        # the check ``load`` was given: a story is held to it before it is shelved too, so none is shelved that the
        # next start would drop
        self.check: Check | None = None
        self.stories: dict[str, Shelved] = {}
        self.cells: dict[Cell, list[str]] = {}
        # stories nobody is offered any more (filled in when a story is blocked)
        self.blocked: set[str] = set()
        # reports that took a story off the shelf today, per hashed address
        self._block_day = date.today()
        self._blocks: dict[str, int] = {}

    def drain(self, timeout: float) -> None:
        """Waits until the default worker has finished the jobs it was given so far (for tests); nothing to wait for
        when ``run`` was injected."""
        if self._pool is not None:
            self._pool.submit(lambda: None).result(timeout)

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

    def shelve_later(self, ep: Episode, draft: Draft, req: EpisodeRequest, bank_tag: str) -> bool:
        """Queues putting a story on the shelf after the response. False when it is skipped up front: it came from a
        wish, its writer is not allowed, a scene was dropped, it names a season or time of day, or its cell is full."""
        try:
            if req.wish is not None or not self.allows(ep.model) or ep.dropped or timeless_problems(ep):
                return False
            # the shelf keeps no pictures: the drawn ones are on this server for a day only
            bare = ep.model_copy(update={
                "images": {}, "pictures": {},
                "scenes": {k: s.model_copy(update={"picture": None}) for k, s in ep.scenes.items()}})
            shelved = Shelved(
                meta=ShelfMeta(ages=req.ages, minutes=req.minutes, friend=req.friend, lesson=req.lesson,
                               theme=draft.theme, writer=ep.model,
                               made=datetime.now(timezone.utc).date().isoformat(), bank=bank_tag),
                puzzles=req.puzzles, episode=bare)
            if self.size(shelved.cell) >= self.cap:
                return False

            def job() -> None:
                try:
                    if self.check is not None:
                        # the request as ``load`` will rebuild it from what is stored
                        stored = EpisodeRequest(installId=SHELF_INSTALL, context=SHELF_CONTEXT,
                                                **req.model_dump(exclude={"installId", "wish", "context"}))
                        if problems := self.check(shelved, draft, stored):
                            log.warning("shelf: %s fails its check, not shelved: %s", ep.storyId, problems)
                            return
                    self.add(shelved, draft, req)
                except Exception:
                    log.exception("shelf: %s could not be shelved", ep.storyId)

            self.run(job)
            return True
        except Exception:
            # the family already has its story: a shelf failure must never reach them
            log.exception("shelf: %s could not be queued", ep.storyId)
            return False

    def get(self, story_id: str) -> Shelved | None:
        with self.lock:
            return self.stories.get(story_id)

    def size(self, cell: Cell) -> int:
        with self.lock:
            return sum(1 for sid in self.cells.get(cell, ()) if sid not in self.blocked)

    def candidates(self, req: LibraryRequest, rnd: random.Random | None = None) -> list[Shelved]:
        """Up to ``offer`` shelved stories this car can play, chosen at random; none at all when a small cell asks the
        car to have a new story written."""
        rnd = rnd or random.Random()
        cell = Cell(req.friend, req.youngest, req.minutes)
        playable = set(req.playable) | {"BELT"}
        ages = sorted(req.ages)
        with self.lock:
            fits = [s for sid in self.cells.get(cell, ()) if sid not in self.blocked
                    for s in [self.stories[sid]]
                    if sorted(s.meta.ages) == ages and self.allows(s.meta.writer)
                    and all(playable.intersection(slot) for slot in s.puzzles)]
            size = sum(1 for sid in self.cells.get(cell, ()) if sid not in self.blocked)
        if req.allowFresh and size < self.min_fresh and rnd.random() < self.fresh:
            return []
        return rnd.sample(fits, min(self.offer, len(fits)))

    def block(self, story_id: str, reason: str, ip: str, today: date | None = None) -> bool:
        """Takes a reported story off the shelf for good (its files stay). False when it is not on the shelf or this
        address has already taken ``blocks_per_ip`` stories off today."""
        today = today or date.today()
        key = _digest(ip)
        with self.lock:
            if today != self._block_day:
                self._block_day = today
                self._blocks.clear()
            if story_id not in self.stories:
                return False
            if story_id in self.blocked:
                return True
            if self._blocks.get(key, 0) >= self.blocks_per_ip:
                return False
            self._blocks[key] = self._blocks.get(key, 0) + 1
            self.blocked.add(story_id)
        try:
            body = {"reason": reason, "at": datetime.now(timezone.utc).isoformat()}
            self.storage.put(f"{BLOCKED}{story_id}.json", json.dumps(body).encode("utf-8"), "application/json")
        except Exception:
            # blocked in memory for now; a restart would offer it again until the next report
            log.exception("shelf: the block of %s could not be saved", story_id)
        return True

    def load(self, check: Check | None = None) -> None:
        """Index every complete story in the storage; a broken, half-written, unallowed or failing one is skipped.

        The blocked marks are read first, then the stories are fetched by a pool of workers. When ``load_seconds``
        have passed the server stops waiting and starts with the stories indexed so far; the blocked ids are known
        by then, so a reported story is never offered either way."""
        self.check = check
        blocked = [p.removeprefix(BLOCKED).removesuffix(".json") for p in self.storage.list(BLOCKED) if p.endswith(".json")]
        with self.lock:
            self.blocked.update(blocked)
        paths = [p for p in self.storage.list(ROOT) if p.endswith("/story.json")]
        stop = threading.Event()
        pool = ThreadPoolExecutor(max_workers=LOAD_WORKERS)
        try:
            futures = [pool.submit(self._load_one, p, check, stop) for p in paths]
            done, pending = wait(futures, timeout=self.load_seconds)
            if pending:
                stop.set()
                with self.lock:
                    indexed = len(self.stories)
                log.warning("shelf: loading took more than %s s, starting with %d of %d stories", self.load_seconds,
                            indexed, len(paths))
        finally:
            pool.shutdown(wait=False, cancel_futures=True)

    def _load_one(self, path: str, check: Check | None, stop: threading.Event) -> None:
        if stop.is_set():
            return
        try:
            shelved = Shelved.model_validate_json(self.storage.get(path) or b"")
            raw = self.storage.get(path.removesuffix("story.json") + "draft.json")
            if raw is None:
                log.warning("shelf: %s has no draft.json, skipped", path)
                return
            body = json.loads(raw)
            draft = Draft.model_validate(body["draft"])
            request = EpisodeRequest(installId=SHELF_INSTALL, context=SHELF_CONTEXT, **body["request"])
            with self.lock:
                reported = shelved.episode.storyId in self.blocked
            if reported:
                log.info("shelf: %s was reported, skipped", path)
                return
            if not self.allows(shelved.meta.writer) or not self.allows(shelved.episode.model):
                log.info("shelf: %s was written by %s, skipped", path, shelved.meta.writer)
                return
            if check and (problems := check(shelved, draft, request)):
                log.warning("shelf: %s fails its check, skipped: %s", path, problems)
                return
        except Exception as e:
            log.warning("shelf: %s cannot be read or checked, skipped: %s", path, e)
            return
        if not stop.is_set():
            self._index(shelved)


def recheck(shelved: Shelved, draft: Draft, request: EpisodeRequest, bank: Bank) -> list[str]:
    """Every problem the rules of today find in a shelved story: the checks of a new story, the lines said after an
    answer, a question the bank no longer holds, and season or time words. The request's context is never read."""
    verdict = checker.check(draft, request)
    problems = list(verdict.whole) + [p for ps in verdict.items.values() for p in ps]
    for scene in draft.scenes:
        problems += result_problems(scene, request)
    for key, scene in shelved.episode.scenes.items():
        q = quiz.parse(key)
        if q is not None and scene.question is not None and not bank.has_text(q, request.youngest, scene.question.text, scene.hint):
            problems.append(f"문제가 문제 은행에 없음: {key}")
    return problems + timeless_problems(shelved.episode)


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
                      offer=_int("STORY_LIBRARY_CANDIDATES", 10), blocks_per_ip=_int("STORY_LIBRARY_BLOCKS_PER_IP", 5),
                      load_seconds=float(os.environ.get("STORY_LIBRARY_LOAD_SECONDS") or DEFAULT_LOAD_SECONDS))
        lib.load(check)
        return lib
    except Exception:
        log.exception("the story shelf cannot be loaded: the server runs without it")
        return None
