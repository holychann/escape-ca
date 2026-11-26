#!/usr/bin/env bash
# Lightsail 서버에서 도는 배포 스크립트. GitHub Actions(.github/workflows/deploy-server.yml)가 SSH로 부릅니다.
# 배포 키는 authorized_keys에서 이 스크립트만 돌리게 묶어 둡니다(command=, 배포_방법.md 7번).
# 그래서 Actions가 보낸 명령은 실행되지 않고, SSH_ORIGINAL_COMMAND로 배포할 커밋 SHA만 받습니다.
#
# 하는 일: 팀 저장소 main을 fast-forward로 받고, 받은 코드에 그 커밋이 들어 있는지 확인한 뒤
# docker compose up -d --build로 다시 띄우고, story가 healthy가 될 때까지 기다립니다.
#
# 손으로 돌려도 됩니다: bash ~/escape-car/server/deploy/lightsail_deploy.sh
#
# 함수 안에 모두 넣은 까닭: git merge가 이 파일 자체를 바꿀 수 있는데, bash는 함수를 끝까지 읽은 뒤 실행하므로
# 도중에 파일이 바뀌어도 안전합니다.
main() {
  set -euo pipefail

  local server_dir repo_dir sha
  server_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  repo_dir="$(cd "$server_dir/.." && pwd)"

  sha="${SSH_ORIGINAL_COMMAND:-${1:-}}"
  sha="${sha//[[:space:]]/}"
  if [[ -n "$sha" && ! "$sha" =~ ^[0-9a-f]{40}$ ]]; then
    echo "deploy: 커밋 SHA(40자리 16진수)만 받습니다" >&2
    exit 2
  fi

  # 손으로 배포하는 중에 자동 배포가 겹치지 않게
  exec 9>"$HOME/.escape-deploy.lock"
  if ! flock -w 600 9; then
    echo "deploy: 다른 배포가 10분 넘게 끝나지 않습니다" >&2
    exit 1
  fi

  cd "$repo_dir"
  if [[ "$(git rev-parse --abbrev-ref HEAD)" != "main" ]]; then
    echo "deploy: 서버 저장소가 main이 아닙니다($(git rev-parse --abbrev-ref HEAD)). 손으로 확인해 주세요" >&2
    exit 1
  fi

  echo "deploy: 받기 전 $(git rev-parse --short HEAD)"
  git fetch --quiet origin main
  # 서버에서 코드를 손으로 고쳐 두었으면 여기서 멈춥니다(.env는 git이 보지 않으니 상관없음)
  git merge --ff-only --quiet origin/main
  echo "deploy: 받은 뒤 $(git rev-parse --short HEAD) $(git log -1 --format=%s)"

  # merge-base는 없는 커밋을 원격에서 찾으려 들어서(부분 복제) 받은 기록 안에서만 찾습니다.
  # grep -q는 쓰지 않습니다: 일찍 끝나면 pipefail 때문에 찾았는데도 실패가 됩니다
  if [[ -n "$sha" ]] && ! git rev-list HEAD | grep -xF "$sha" >/dev/null; then
    echo "deploy: 받은 main에 $sha 가 없습니다" >&2
    exit 1
  fi

  cd "$server_dir"
  # --wait: story의 healthcheck(/health)가 통과할 때까지 기다리고, 실패하면 0이 아닌 값으로 끝납니다
  if ! docker compose up -d --build --remove-orphans --wait --wait-timeout 300; then
    echo "deploy: 서버가 healthy가 되지 않았습니다. 최근 로그:" >&2
    docker compose ps >&2 || true
    docker compose logs --tail 80 story >&2 || true
    exit 1
  fi
  docker compose ps

  # 다시 빌드할 때마다 남는 옛 이미지가 작은 디스크를 채우지 않게
  docker image prune -f >/dev/null
  echo "deploy: 끝"
}

main "$@"
