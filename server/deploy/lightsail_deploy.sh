#!/usr/bin/env bash
# Lightsail 서버에서 이야기 서버를 다시 배포합니다.
# 보통은 lightsail_poll.sh(2분마다 도는 타이머)가 테스트를 통과한 main 커밋을 넘겨 부릅니다.
#
# 손으로 돌려도 됩니다: bash ~/escape-car/server/deploy/lightsail_deploy.sh [커밋 SHA]
# SHA를 주지 않으면 팀 저장소 main의 맨 끝을 배포합니다(이때는 테스트 결과를 보지 않습니다).
#
# 하는 일: 팀 저장소를 받아 그 커밋까지 fast-forward하고, docker compose up -d --build로 다시 띄우고,
# story가 healthy가 될 때까지 기다린 뒤 배포한 커밋을 ~/.escape-deploy/deployed에 적습니다.
# 코드가 그대로면 이미지가 캐시에서 나와 컨테이너를 다시 만들지 않습니다(car/만 바뀐 커밋 등).
#
# 함수 안에 모두 넣은 까닭: git merge가 이 파일 자체를 바꿀 수 있는데, bash는 함수를 끝까지 읽은 뒤 실행하므로
# 도중에 파일이 바뀌어도 안전합니다.
main() {
  set -euo pipefail

  local server_dir repo_dir state_dir target
  server_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  repo_dir="$(cd "$server_dir/.." && pwd)"
  state_dir="$HOME/.escape-deploy"
  mkdir -p "$state_dir"

  target="${1:-}"
  if [[ -n "$target" && ! "$target" =~ ^[0-9a-f]{40}$ ]]; then
    echo "deploy: 커밋 SHA(40자리 16진수)만 받습니다" >&2
    exit 2
  fi

  # 손으로 배포하는 중에 타이머 배포가 겹치지 않게
  exec 9>"$state_dir/lock"
  if ! flock -w 600 9; then
    echo "deploy: 다른 배포가 10분 넘게 끝나지 않습니다" >&2
    exit 1
  fi

  cd "$repo_dir"
  if [[ "$(git rev-parse --abbrev-ref HEAD)" != "main" ]]; then
    echo "deploy: 서버 저장소가 main이 아닙니다($(git rev-parse --abbrev-ref HEAD)). 손으로 확인해 주세요" >&2
    exit 1
  fi

  git fetch --quiet origin main
  if [[ -z "$target" ]]; then
    target="$(git rev-parse origin/main)"
  fi

  echo "deploy: 지금 $(git rev-parse --short HEAD) → 배포할 커밋 ${target:0:7}"
  # 서버에서 코드를 손으로 고쳐 두었으면 여기서 멈춥니다(.env는 git이 보지 않으니 상관없음)
  git merge --ff-only --quiet "$target"
  if [[ "$(git rev-parse HEAD)" != "$target" ]]; then
    echo "deploy: ${target:0:7}은 지금 코드보다 예전 커밋이라 배포하지 않습니다" >&2
    exit 1
  fi
  echo "deploy: 코드 $(git log -1 --format='%h %s')"

  cd "$server_dir"
  # --wait: story의 healthcheck(/health)가 통과할 때까지 기다리고, 실패하면 0이 아닌 값으로 끝납니다
  if ! docker compose up -d --build --remove-orphans --wait --wait-timeout 300; then
    echo "deploy: 서버가 healthy가 되지 않았습니다. 최근 로그:" >&2
    docker compose ps >&2 || true
    docker compose logs --tail 80 story >&2 || true
    exit 1
  fi
  docker compose ps

  echo "$target" > "$state_dir/deployed"
  # 다시 빌드할 때마다 남는 옛 이미지가 작은 디스크를 채우지 않게
  docker image prune -f >/dev/null
  echo "deploy: 끝 (${target:0:7})"
}

main "$@"
