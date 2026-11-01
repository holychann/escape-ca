"""Daily limits and the records kept for review (stories sent, stories parents reported)."""
import json
import threading
from datetime import date, datetime, timezone
from pathlib import Path

from .models import Episode, EpisodeRequest, Report


class DailyLimit:
    """Stories per install and per address per day, so one car cannot run up the bill."""

    def __init__(self, per_install: int, per_ip: int):
        self.per_install = per_install
        self.per_ip = per_ip
        self._day = date.today()
        self._counts: dict[str, int] = {}
        self._lock = threading.Lock()

    def take(self, install_id: str, ip: str, today: date | None = None) -> bool:
        today = today or date.today()
        with self._lock:
            if today != self._day:
                self._day = today
                self._counts.clear()
            a, b = f"i:{install_id}", f"a:{ip}"
            if self._counts.get(a, 0) >= self.per_install or self._counts.get(b, 0) >= self.per_ip:
                return False
            self._counts[a] = self._counts.get(a, 0) + 1
            self._counts[b] = self._counts.get(b, 0) + 1
            return True


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
        self._append("episodes.jsonl", {"ages": req.ages, "keys": req.keys, "episode": ep.model_dump()})

    def report(self, r: Report) -> None:
        self._append("reports.jsonl", r.model_dump())
