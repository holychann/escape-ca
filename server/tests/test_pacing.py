"""The paid key's per-minute pacing: a busy minute waits instead of failing. No real model and no real sleep."""
import pytest

from app.pacing import Pace, PaceTimeout, retry_delay


class Clock:
    def __init__(self):
        self.now = 0.0
        self.slept: list[float] = []

    def __call__(self):
        return self.now

    def sleep(self, s):
        self.slept.append(round(s, 2))
        self.now += s


class Quota429(Exception):
    code = 429


def test_calls_past_the_limit_wait_for_room_instead_of_failing():
    c = Clock()
    pace = Pace(2, "speech", clock=c, sleep=c.sleep)
    assert pace.take() == 0 and pace.take() == 0
    # the third call in the same minute waits until the first one is a minute old
    assert pace.take() == pytest.approx(60.05)
    assert c.slept == [60.05]


def test_too_long_a_wait_gives_up():
    c = Clock()
    pace = Pace(1, "speech", max_wait=10, clock=c, sleep=c.sleep)
    pace.take()
    with pytest.raises(PaceTimeout):
        pace.take()


def test_a_per_minute_429_is_waited_out_as_google_asks():
    c = Clock()
    pace = Pace(100, "pictures", clock=c, sleep=c.sleep)
    answers = [Quota429("429 RESOURCE_EXHAUSTED ... 'retryDelay': '7s'"), "picture"]

    def call():
        a = answers.pop(0)
        if isinstance(a, Exception):
            raise a
        return a

    assert pace.call(call) == "picture"
    assert c.slept == [7.0]


def test_a_daily_429_is_given_up_on_at_once():
    c = Clock()
    pace = Pace(100, "speech", clock=c, sleep=c.sleep)

    def call():
        raise Quota429("429 RESOURCE_EXHAUSTED GenerateRequestsPerDayPerProjectPerModel 'retryDelay': '5s'")

    with pytest.raises(Quota429):
        pace.call(call)
    assert c.slept == []


def test_the_retry_delay_is_read_from_googles_answer():
    assert retry_delay(Exception("{'retryDelay': '54712s'}")) == 54712.0
    assert retry_delay(Exception('"retryDelay": "12.5s"')) == 12.5
    assert retry_delay(Exception("no delay here")) is None
