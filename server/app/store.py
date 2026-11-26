"""Daily limits, image TTL cleanup, and the records kept for review (stories sent, stories parents reported)."""
import hashlib
import json
import re
import shutil
import threading
import time
from datetime import date, datetime, timezone
from pathlib import Path

from .models import Episode, EpisodeRequest, Report

_STORY_ID = re.compile(r"^[0-9a-f]{32}$")


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


class DailyLimit:
    """Stories per install, per IP address, and server-wide per day, persisted as hashed counters across restarts."""

    def __init__(self, per_install: int, per_ip: int, per_day: int = 300, state_file: Path | None = None):
        self.per_install = per_install
        self.per_ip = per_ip
        self.per_day = per_day
        self._state_file = state_file
        self._day = date.today()
        self._total = 0
        self._counts: dict[str, int] = {}
        self._lock = threading.Lock()
        self._load()

    def _load(self) -> None:
        if self._state_file is None or not self._state_file.is_file():
            return
        try:
            raw = json.loads(self._state_file.read_text(encoding="utf-8"))
            if raw.get("day") == self._day.isoformat():
                self._total = int(raw.get("total", 0))
                self._counts = {str(k): int(v) for k, v in raw.get("counts", {}).items()}
        except Exception:
            self._total = 0
            self._counts = {}

    def _save(self) -> None:
        if self._state_file is None:
            return
        self._state_file.parent.mkdir(parents=True, exist_ok=True)
        tmp = self._state_file.with_suffix(".tmp")
        tmp.write_text(
            json.dumps({"day": self._day.isoformat(), "total": self._total, "counts": self._counts}),
            encoding="utf-8",
        )
        tmp.replace(self._state_file)

    def take(self, install_id: str, ip: str, today: date | None = None) -> bool:
        today = today or date.today()
        with self._lock:
            if today != self._day:
                self._day = today
                self._total = 0
                self._counts.clear()
            a, b = f"i:{_digest(install_id)}", f"a:{_digest(ip)}"
            if (
                self._total >= self.per_day
                or self._counts.get(a, 0) >= self.per_install
                or self._counts.get(b, 0) >= self.per_ip
            ):
                return False
            self._total += 1
            self._counts[a] = self._counts.get(a, 0) + 1
            self._counts[b] = self._counts.get(b, 0) + 1
            self._save()
            return True


class IpLimit:
    """Calls per IP address per day, hashed like DailyLimit and kept in memory only."""

    def __init__(self, per_ip: int):
        self.per_ip = per_ip
        self._day = date.today()
        self._counts: dict[str, int] = {}
        self._lock = threading.Lock()

    def take(self, ip: str, today: date | None = None) -> bool:
        today = today or date.today()
        with self._lock:
            if today != self._day:
                self._day = today
                self._counts.clear()
            key = _digest(ip)
            if self._counts.get(key, 0) >= self.per_ip:
                return False
            self._counts[key] = self._counts.get(key, 0) + 1
            return True


class ReportLimit:
    """Bounds /v1/report spam per IP and per day, and ignores duplicate (storyId, reason) pairs."""

    def __init__(self, per_ip: int = 30, per_day: int = 500):
        self.per_ip = per_ip
        self.per_day = per_day
        self._day = date.today()
        self._total = 0
        self._by_ip: dict[str, int] = {}
        self._seen: set[tuple[str, str]] = set()
        self._lock = threading.Lock()

    def allow(self, ip: str, story_id: str, reason: str, today: date | None = None) -> tuple[bool, bool]:
        """Returns (allowed_by_rate_limit, should_write_record)."""
        today = today or date.today()
        with self._lock:
            if today != self._day:
                self._day = today
                self._total = 0
                self._by_ip.clear()
                self._seen.clear()
            key = _digest(ip)
            if self._total >= self.per_day or self._by_ip.get(key, 0) >= self.per_ip:
                return False, False
            self._total += 1
            self._by_ip[key] = self._by_ip.get(key, 0) + 1
            pair = (story_id, reason)
            if pair in self._seen:
                return True, False
            self._seen.add(pair)
            return True, True


def prune_images(images_dir: Path, max_age_hours: float, now: float | None = None) -> int:
    """Deletes episode image folders older than ``max_age_hours``. Zero or negative disables cleanup."""
    if max_age_hours <= 0 or not images_dir.is_dir():
        return 0
    cutoff = (time.time() if now is None else now) - max_age_hours * 3600
    removed = 0
    for child in list(images_dir.iterdir()):
        if not child.is_dir() or not _STORY_ID.fullmatch(child.name):
            continue
        try:
            if child.stat().st_mtime < cutoff:
                shutil.rmtree(child, ignore_errors=True)
                removed += 1
        except OSError:
            continue
    return removed


class Store:
    """JSON lines in a folder. The install id is never written down."""

    def __init__(self, folder: Path):
        self.folder = folder
        self._lock = threading.Lock()

    def _append(self, name: str, record: dict) -> None:
        record = {"at": datetime.now(timezone.utc).isoformat(timespec="seconds"), **record}
        with self._lock:
            self.folder.mkdir(parents=True, exist_ok=True)
            with open(self.folder / name, "a", encoding="utf-8") as f:
                f.write(json.dumps(record, ensure_ascii=False) + "\n")

    def episode(self, ep: Episode, req: EpisodeRequest) -> None:
        # the family's wish is free text they typed, so only whether there was one is kept
        self._append("episodes.jsonl", {"ages": req.ages, "keys": req.keys, "wish": req.wish is not None,
                                        "episode": ep.model_dump()})

    def report(self, r: Report) -> None:
        self._append("reports.jsonl", r.model_dump())
