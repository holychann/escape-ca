"""Today's new friend: every story brings one, it is checked like the bubble, and its portrait is drawn first."""
from fastapi.testclient import TestClient

from app.checker import friend_problems
from app.main import create_app
from app.models import EpisodeRequest
from app.prompt import SYSTEM, user_message
from tests.fakes import FRIEND, FakeWriter, draft
from tests.test_wish_and_pictures import ART, BODY, KEYS, FakePainter


def post(tmp_path, writer, painter=None, **body):
    c = TestClient(create_app(writer=writer, data_dir=tmp_path, painter=painter))
    return c, c.post("/v1/episode", json={**BODY, **body})


def test_the_writer_is_asked_for_a_new_friend():
    assert "오늘의 새 친구 (friend)" in SYSTEM
    assert "friend(오늘의 세계에 사는 새 친구 한 명)" in user_message(EpisodeRequest.model_validate(BODY))


def test_the_writer_is_told_every_episode_wide_length_limit():
    # the treasure lines also greet today's friend; a draft whose treasure line runs long is thrown away whole
    msg = user_message(EpisodeRequest.model_validate(BODY))
    assert "treasureGift와 treasureBadge는 각각 90자 이내" in msg and "title 20자" in msg and "intro 180자" in msg


def test_a_good_friend_comes_back_with_its_portrait(tmp_path):
    painter = FakePainter()
    c, r = post(tmp_path, FakeWriter(draft(KEYS, art=ART)), painter, wish="별빛 기차 여행", pictures=True)
    ep = r.json()
    assert ep["friend"] == FRIEND.model_dump()
    assert painter.portraits and painter.portraits[0].startswith("별똥 차장. 노란 별")
    # the cover and the ending are drawn after the portrait and with it; the puzzle pictures do not wait for it
    assert sorted(painter.with_friend) == sorted([ART.cover, ART.ending])
    got = c.get(ep["images"]["friend"])
    assert got.status_code == 200 and got.headers["content-type"] == "image/webp"


def test_every_story_brings_a_portrait_even_without_scene_pictures(tmp_path):
    painter = FakePainter()
    _, r = post(tmp_path, FakeWriter(draft(KEYS, art=ART)), painter)
    ep = r.json()
    assert set(ep["images"]) == {"friend"} and painter.calls == [] and len(painter.portraits) == 1
    assert ep["scenes"]["FOG"]["picture"] is None


def test_a_friend_that_breaks_a_rule_is_left_out_but_the_story_plays(tmp_path):
    req = EpisodeRequest.model_validate(BODY)
    assert friend_problems(FRIEND, req) == []
    bad = {
        "taken name": FRIEND.model_copy(update={"name": "펭귄 선장"}),
        "english name": FRIEND.model_copy(update={"name": "Star Kid"}),
        "answer word": FRIEND.model_copy(update={"look": "왼쪽 귀만 파란 꼬마"}),
        "button word": FRIEND.model_copy(update={"trait": "독서등 아래에서 책 읽기를 좋아해."}),
        "scary": FRIEND.model_copy(update={"hello": "귀신이 나타났다!"}),
        "too long": FRIEND.model_copy(update={"hello": "가" * 41}),
    }
    for why, friend in bad.items():
        assert friend_problems(friend, req), why
    painter = FakePainter()
    two = [draft(KEYS, art=ART, friend=bad["answer word"]), draft(KEYS, art=ART, friend=bad["answer word"])]
    _, r = post(tmp_path, FakeWriter(*two), painter)
    assert r.status_code == 200 and r.json()["friend"] is None and painter.portraits == []


def test_a_second_draft_is_asked_for_when_the_first_brings_no_usable_friend(tmp_path):
    writer = FakeWriter(draft(KEYS, friend=None), draft(KEYS))
    _, r = post(tmp_path, writer)
    assert writer.calls == 2 and r.json()["friend"]["name"] == "별똥 차장"


def test_a_failed_portrait_still_draws_the_cover_without_it(tmp_path):
    painter = FakePainter(fail={"별똥 차장"})
    _, r = post(tmp_path, FakeWriter(draft(KEYS, art=ART)), painter, pictures=True)
    ep = r.json()
    assert "friend" not in ep["images"] and {"cover", "ending"} <= set(ep["images"])
    assert painter.with_friend == []
    # the friend still joins the book; the app draws its own placeholder
    assert ep["friend"]["name"] == "별똥 차장"
