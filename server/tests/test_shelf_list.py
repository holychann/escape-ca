"""app/shelf_list.py: the shelf grouped by what a car asks it with."""
from app.library import FolderStorage
from tests.test_library_wish import WISH, stocked, wished
from tests.test_library_api import story
from app.shelf_list import report, storage_from


def test_stories_are_grouped_by_their_key_with_lesson_and_pictures(tmp_path):
    a = wished("a" * 32, lesson="COURAGE")
    b = wished("b" * 32, lesson="THANKS")
    winter = wished("c" * 32)
    winter = winter.model_copy(update={"meta": winter.meta.model_copy(update={"season": "WINTER"})})
    lib = stocked(tmp_path, a, b, winter, story("d" * 32))
    (tmp_path / "shelf" / a.cell.path() / a.episode.storyId / "img").mkdir(parents=True)
    (tmp_path / "shelf" / a.cell.path() / a.episode.storyId / "img" / "cover.webp").write_bytes(b"x")
    lib.block("d" * 32, "OTHER", "1.1.1.1")
    text = report(FolderStorage(tmp_path / "shelf"))
    assert text.startswith("이야기 4편, 열쇠 3개, 신고로 빠진 이야기 1편")
    picked = "1. 친구 반테 | 2. 나이 7~9세 | 3. 시간 10분 | 4. 장소 칸에 «공룡 섬» 입력, «보물찾기» 선택"
    assert f"[2편] {picked} | 계절 계절 없음" in text
    assert f"[1편] {picked} | 계절 겨울" in text
    assert "[1편] 1. 친구 반테 | 2. 나이 7~9세 | 3. 시간 10분 | 4. 소원 없음 | 계절 계절 없음" in text
    assert "용기  그림 1장" in text and "감사  그림 0장" in text and "(신고로 빠짐)" in text


def test_without_a_shelf_named_there_is_nothing_to_read(monkeypatch):
    monkeypatch.delenv("STORY_LIBRARY_BUCKET", raising=False)
    monkeypatch.delenv("STORY_LIBRARY_DIR", raising=False)
    assert storage_from(None) is None


def test_a_wish_in_another_form_is_shown_whole():
    from app.shelf_list import wish_parts
    assert wish_parts("공룡 섬에서 보물찾기") == "소원 «공룡 섬에서 보물찾기»"
    assert wish_parts("바닷속 마을에서 펼쳐지는 탐정 놀이 이야기") == "장소 칸에 «바닷속 마을» 입력, «탐정 놀이» 선택"
