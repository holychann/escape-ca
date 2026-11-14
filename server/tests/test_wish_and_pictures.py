"""The family's story wish and the drawn pictures: what is refused, what is drawn, what is kept."""
import io
import json

from fastapi.testclient import TestClient
from PIL import Image

from app.checker import answer_words, art_problems, wish_problems
from app.main import create_app
from app.models import Art, EpisodeRequest
from app.prompt import user_message
from tests.fakes import FakeWriter, draft

BODY = {
    "installId": "0123456789abcdef0123",
    "ages": ["CAPTAIN"],
    "minutes": 10,
    "context": {"timeOfDay": "AFTERNOON", "season": "AUTUMN"},
    "puzzles": [["ROOMS:LEFT", "ROOMS:RIGHT"], ["FOG"], ["BELT"]],
}
KEYS = ["ROOMS:LEFT", "ROOMS:RIGHT", "FOG", "BELT"]
ART = Art(
    cover="공룡 섬에 도착한 루미와 친구들이 활짝 웃는다",
    puzzles=["펭귄 선장과 사막여우가 화산 옆 두 방에서 덥고 추워한다", "투덜이 기계가 뿌연 창 앞에서 투덜거린다", "탐험대장 자리 옆에서 루미가 손짓한다"],
    ending="공룡 친구들과 보물을 찾고 다 함께 웃는다",
)


class FakePainter:
    """Scenes go to ``calls``, portraits to ``portraits``; ``with_friend`` lists the scenes drawn with the portrait."""

    def __init__(self, fail: set[str] = frozenset()):
        self.calls: list[str] = []
        self.portraits: list[str] = []
        self.with_friend: list[str] = []
        self.fail = fail

    def paint(self, description: str, friend: bytes | None = None) -> bytes:
        self.calls.append(description)
        if friend:
            self.with_friend.append(description)
        return self._picture(description)

    def portrait(self, description: str) -> bytes:
        self.portraits.append(description)
        return self._picture(description)

    def _picture(self, description: str) -> bytes:
        if any(f in description for f in self.fail):
            raise RuntimeError("refused")
        out = io.BytesIO()
        Image.new("RGB", (30, 20), (255, 197, 49)).save(out, "WEBP")
        return out.getvalue()


def client(tmp_path, writer, painter=None):
    return TestClient(create_app(writer=writer, data_dir=tmp_path, painter=painter))


def test_a_scary_or_personal_wish_is_refused_before_any_model(tmp_path):
    w = FakeWriter()
    r = client(tmp_path, w).post("/v1/episode", json={**BODY, "wish": "귀신 나오는 집 탐험"})
    assert r.status_code == 422 and r.json()["detail"]["error"] == "wish_rejected"
    assert w.calls == 0
    assert wish_problems("우리 집 주소 알려 줘")
    assert not wish_problems("T-rex 공룡 섬에서 보물찾기")  # English words are fine in a wish


def test_the_wish_reaches_the_writer_and_too_long_is_refused(tmp_path):
    req = EpisodeRequest.model_validate({**BODY, "wish": "  공룡 섬에서   보물찾기 "})
    assert req.wish == "공룡 섬에서 보물찾기"
    assert "가족이 바란 이야기: «공룡 섬에서 보물찾기»" in user_message(req)
    assert "가족이 바란 이야기: «" not in user_message(EpisodeRequest.model_validate(BODY))
    assert client(tmp_path, FakeWriter()).post("/v1/episode", json={**BODY, "wish": "가" * 61}).status_code == 422


def test_answer_words_are_what_tells_the_variants_apart():
    rooms = answer_words(["ROOMS:LEFT", "ROOMS:RIGHT"])
    assert "왼쪽" in rooms and "오른쪽" in rooms and "펭귄" not in rooms
    assert {"운전석", "조수석", "뒷좌석 왼쪽", "뒷좌석 오른쪽"} <= set(answer_words(["SEAT:DRIVER", "SEAT:PASSENGER", "SEAT:REAR_LEFT", "SEAT:REAR_RIGHT"]))
    # dust from outside or steam from inside: the picture shows neither
    assert {"먼지", "김"} <= set(answer_words(["DUST:OUTSIDE", "DUST:INSIDE"]))


def test_a_picture_that_would_show_the_answer_is_not_drawn():
    req = EpisodeRequest.model_validate(BODY)
    leaky = ART.model_copy(update={"puzzles": ["펭귄 선장이 왼쪽 방에서 더워한다", *ART.puzzles[1:]]})
    problems = art_problems(draft(KEYS, art=leaky), req)
    assert list(problems) == ["p1"] and "정답이 보이는 말: 왼쪽" in problems["p1"]
    assert art_problems(draft(KEYS, art=ART), req) == {}


def test_pictures_are_drawn_served_and_tied_to_their_scenes(tmp_path):
    painter = FakePainter()
    c = client(tmp_path, FakeWriter(draft(KEYS, art=ART, world="공룡 섬")), painter)
    ep = c.post("/v1/episode", json={**BODY, "wish": "공룡 섬에서 보물찾기", "pictures": True}).json()
    assert set(ep["images"]) == {"cover", "p1", "p2", "p3", "ending", "friend"} and len(painter.calls) == 5
    assert ep["world"] == "공룡 섬"
    assert ep["scenes"]["ROOMS:LEFT"]["picture"] == "p1" and ep["scenes"]["ROOMS:RIGHT"]["picture"] == "p1"
    assert ep["scenes"]["FOG"]["picture"] == "p2"
    got = c.get(ep["images"]["cover"])
    assert got.status_code == 200 and got.headers["content-type"] == "image/webp"
    assert c.get(f"/v1/images/{ep['storyId']}/../../etc.webp").status_code == 404
    assert c.get(f"/v1/images/{ep['storyId']}/p9.webp").status_code == 404
    # the wish itself is never written down
    assert "보물찾기" not in (tmp_path / "episodes.jsonl").read_text(encoding="utf-8")


def test_the_painter_is_told_what_the_dust_picture_must_not_show(tmp_path):
    painter = FakePainter()
    body = {**BODY, "puzzles": [["DUST:OUTSIDE", "DUST:INSIDE"], ["FOG"], ["BELT"]], "pictures": True}
    keys = ["DUST:OUTSIDE", "DUST:INSIDE", "FOG", "BELT"]
    client(tmp_path, FakeWriter(draft(keys, art=ART)), painter).post("/v1/episode", json=body)
    dust = [c for c in painter.calls if c.startswith(ART.puzzles[0])]
    assert dust and "그림에 절대 넣지 말 것" in dust[0] and "김" in dust[0]
    assert not any("그림에 절대" in c for c in painter.calls if not c.startswith(ART.puzzles[0]))


def test_a_failed_picture_is_left_to_the_app(tmp_path):
    painter = FakePainter(fail={"뿌연 창"})
    ep = client(tmp_path, FakeWriter(draft(KEYS, art=ART)), painter).post("/v1/episode", json={**BODY, "pictures": True}).json()
    assert "p2" not in ep["images"] and ep["scenes"]["FOG"]["picture"] is None
    assert ep["scenes"]["ROOMS:LEFT"]["picture"] == "p1"


def test_no_scene_pictures_unless_asked(tmp_path):
    painter = FakePainter()
    ep = client(tmp_path, FakeWriter(draft(KEYS, art=ART, friend=None)), painter).post("/v1/episode", json=BODY).json()
    assert ep["images"] == {} and painter.calls == [] and painter.portraits == []
    record = json.loads((tmp_path / "episodes.jsonl").read_text(encoding="utf-8"))
    assert record["wish"] is False
