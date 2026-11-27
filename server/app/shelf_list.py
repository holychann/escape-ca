"""Lists what is on the story shelf, grouped by what a car asks it with: friend, ages, minutes, wish and season.

    python -m app.shelf_list                  (the shelf the environment names: STORY_LIBRARY_BUCKET or _DIR)
    python -m app.shelf_list --dir data/library
    docker compose exec story python -m app.shelf_list        (on the server)

Read only. It prints the wishes families typed, so it is for the people running the server and is never served.
"""
import argparse
import os
import re
import sys
from collections import defaultdict
from pathlib import Path

from .library import BLOCKED, ROOT, FolderStorage, S3Storage, Shelved, Storage
from .models import FRIEND_LABEL, LESSON_LABEL

SEASON_LABEL = {"SPRING": "봄", "SUMMER": "여름", "AUTUMN": "가을", "WINTER": "겨울", None: "계절 없음"}
AGE_ORDER = ["LITTLE", "CAPTAIN", "DETECTIVE"]
# the age buttons on the car's setup screen
AGE_BUTTON = {"LITTLE": "4~6세", "CAPTAIN": "7~9세", "DETECTIVE": "10~12세"}
# the wish the car sends, made from the place box and the story kind button (car MainActivity.wishOf)
WISH_FORM = re.compile(r"^(.+)에서 펼쳐지는 (.+) 이야기$")


def storage_from(folder: str | None) -> Storage | None:
    if folder:
        return FolderStorage(Path(folder))
    if bucket := os.environ.get("STORY_LIBRARY_BUCKET"):
        return S3Storage(bucket, os.environ.get("STORY_LIBRARY_PREFIX", ""))
    if env_folder := os.environ.get("STORY_LIBRARY_DIR"):
        return FolderStorage(Path(env_folder))
    return None


def read(storage: Storage) -> tuple[list[tuple[Shelved, int]], set[str], list[str]]:
    """Every story with how many pictures it has, the reported ids, and the paths that could not be read."""
    paths = storage.list(ROOT)
    pictures: dict[str, int] = defaultdict(int)
    for p in paths:
        if "/img/" in p and p.endswith(".webp"):
            pictures[p.split("/img/")[0]] += 1
    stories, broken = [], []
    for p in paths:
        if not p.endswith("/story.json"):
            continue
        try:
            shelved = Shelved.model_validate_json(storage.get(p) or b"")
        except Exception:
            broken.append(p)
            continue
        stories.append((shelved, pictures[p.removesuffix("/story.json")]))
    blocked = {p.removeprefix(BLOCKED).removesuffix(".json") for p in storage.list(BLOCKED) if p.endswith(".json")}
    return stories, blocked, broken


def wish_parts(wish: str | None) -> str:
    """What the parent types and picks under 4. 오늘의 이야기 소원 to send this wish."""
    if not wish:
        return "소원 없음"
    m = WISH_FORM.match(wish)
    if m is None:
        return f"소원 «{wish}»"
    return f"장소 칸에 «{m.group(1)}» 입력, «{m.group(2)}» 선택"


def key_line(s: Shelved) -> str:
    """The choices as the car's setup screen asks them, in its order."""
    ages = ", ".join(AGE_BUTTON[a] for a in sorted(s.meta.ages, key=AGE_ORDER.index))
    return (f"1. 친구 {FRIEND_LABEL[s.meta.friend][0]} | 2. 나이 {ages} | 3. 시간 {s.meta.minutes}분 | "
            f"4. {wish_parts(s.meta.wish)} | 계절 {SEASON_LABEL[s.meta.season]}")


def report(storage: Storage) -> str:
    stories, blocked, broken = read(storage)
    groups: dict[str, list[tuple[Shelved, int]]] = defaultdict(list)
    for s, n in stories:
        groups[key_line(s)].append((s, n))
    reported = blocked & {s.episode.storyId for s, _ in stories}
    out = [f"이야기 {len(stories)}편, 열쇠 {len(groups)}개, 신고로 빠진 이야기 {len(reported)}편",
           "준비 화면에서 아래처럼 고르면 그 이야기가 바로 나옵니다(그 계절에, 이 차에서 아직 안 들은 것만)", ""]
    for key in sorted(groups):
        rows = sorted(groups[key], key=lambda r: r[0].meta.made)
        out.append(f"[{len(rows)}편] {key}")
        for s, n in rows:
            lesson = LESSON_LABEL[s.meta.lesson][0] if s.meta.lesson else "교훈 없음"
            mark = "  (신고로 빠짐)" if s.episode.storyId in blocked else ""
            out.append(f"    {s.meta.made}  {lesson}  그림 {n}장  {s.episode.title}  [{s.episode.storyId[:8]}]{mark}")
        out.append("")
    out += [f"읽지 못함: {p}" for p in broken]
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description="이야기 창고 목록")
    parser.add_argument("--dir", help="창고 폴더 (없으면 STORY_LIBRARY_BUCKET, STORY_LIBRARY_DIR 순서로 찾음)")
    storage = storage_from(parser.parse_args().dir)
    if storage is None:
        print("창고가 꺼져 있습니다: STORY_LIBRARY_BUCKET이나 STORY_LIBRARY_DIR이 없고 --dir도 주지 않았습니다.")
        return 1
    print(report(storage))
    return 0


if __name__ == "__main__":
    sys.exit(main())
