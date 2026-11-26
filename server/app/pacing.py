"""Keeps the paid Gemini key's calls inside its per-minute limits, so a busy minute waits instead of failing.

Google counts the limits per model, so speech (TTS_PAID_RPM) and pictures (IMAGE_RPM) each get their own Pace. A call
waits for room in the last 60 s; a per-minute 429 that still comes back is waited out for as long as Google asks
(retryDelay) and tried again, at most twice. A daily 429 is given up on at once.
"""
import logging
import re
import threading
import time

log = logging.getLogger("pacing")

# a per-minute 429 asks for a few seconds; anything longer than this is not waited for
MAX_RETRY_DELAY = 30.0
RETRIES = 2


class PaceTimeout(Exception):
    """No room within the time a caller can wait."""


def is_quota(error: Exception) -> bool:
    return getattr(error, "code", None) == 429 or "RESOURCE_EXHAUSTED" in str(error)


def is_daily(error: Exception) -> bool:
    text = str(error)
    return "PerDay" in text or "per day" in text.lower()


def retry_delay(error: Exception) -> float | None:
    """The seconds Google asks to wait before trying again (retryDelay), or None when it says nothing."""
    found = re.search(r"retryDelay'?\"?\s*:\s*'?\"?([0-9.]+)s", str(error))
    return float(found.group(1)) if found else None


class Pace:
    """At most [per_minute] calls in any 60 s. Shared by the threads that call one model with one key."""

    def __init__(self, per_minute: int, name: str, max_wait: float = 90.0, clock=time.monotonic, sleep=time.sleep):
        self.per_minute = max(1, per_minute)
        self.name = name
        self.max_wait = max_wait
        self._clock = clock
        self._sleep = sleep
        self._sent: list[float] = []
        self._lock = threading.Lock()

    def take(self) -> float:
        """Waits for room and returns the seconds waited; PaceTimeout past [max_wait]."""
        start = self._clock()
        while True:
            with self._lock:
                now = self._clock()
                self._sent = [t for t in self._sent if now - t < 60.0]
                if len(self._sent) < self.per_minute:
                    self._sent.append(now)
                    waited = now - start
                    if waited >= 1.0:
                        log.info("pacing: %s waited %.1f s for room (%d a minute)", self.name, waited, self.per_minute)
                    return waited
                wait = 60.0 - (now - self._sent[0]) + 0.05
            if now - start + wait > self.max_wait:
                raise PaceTimeout(f"{self.name}: no room within {self.max_wait:.0f} s")
            self._sleep(wait)

    def call(self, fn):
        """Runs [fn] inside the limit; a per-minute 429 is waited out and tried again, at most RETRIES times."""
        for attempt in range(RETRIES + 1):
            self.take()
            try:
                return fn()
            except Exception as e:
                if not is_quota(e) or is_daily(e) or attempt == RETRIES:
                    raise
                delay = retry_delay(e) or 10.0
                if delay > MAX_RETRY_DELAY:
                    raise
                log.warning("pacing: %s got 429 (per minute), waiting %.0f s, retry %d of %d",
                            self.name, delay, attempt + 1, RETRIES)
                self._sleep(delay)
