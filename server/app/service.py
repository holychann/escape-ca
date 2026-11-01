"""One episode: write, check, and write once more if the first draft was not clean."""
import logging
import uuid

from .checker import Verdict, check, to_episode
from .models import Episode, EpisodeRequest
from .prompt import SYSTEM, user_message
from .writer import Writer

log = logging.getLogger("story")


def make_episode(req: EpisodeRequest, writer: Writer, attempts: int = 2) -> Episode | None:
    """The cleanest usable draft, or None when every draft failed the episode-wide checks."""
    best: tuple[Verdict, Episode] | None = None
    user = user_message(req)
    for attempt in range(1, attempts + 1):
        try:
            draft, model = writer.write(SYSTEM, user)
        except Exception as e:  # network, rate limit, refusal, broken JSON: all mean "no draft this time"
            log.warning("attempt %d: writer failed: %r", attempt, e)
            continue
        verdict = check(draft, req)
        log.info("attempt %d: episode=%s scenes=%s", attempt, verdict.episode, verdict.scenes)
        if not verdict.usable:
            continue
        episode = to_episode(draft, verdict, req, uuid.uuid4().hex, model)
        if best is None or len(verdict.scenes) < len(best[0].scenes):
            best = (verdict, episode)
        if verdict.clean:
            break
    return best[1] if best else None
