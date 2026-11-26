#!/usr/bin/env bash
# Lightsail 서버가 2분마다 돌리는 자동 배포 확인(escape-deploy.timer → escape-deploy.service).
#
# 1. 팀 저장소 main의 맨 끝 커밋을 받아 봅니다(git fetch).
# 2. 이미 배포한 커밋이면 아무것도 하지 않습니다.
# 3. 그 커밋의 GitHub Actions 'server-tests' 결과를 GitHub API로 봅니다. 공개 저장소라 키가 필요 없습니다.
#    - 통과: lightsail_deploy.sh로 배포합니다.
#    - 도는 중이거나 아직 없음: 다음 차례(2분 뒤)에 다시 봅니다.
#    - 실패, 취소: 배포하지 않습니다. Actions에서 다시 돌려 통과하면 그때 배포합니다.
# 4. 배포가 실패한 커밋은 다시 시도하지 않습니다(망가진 빌드를 2분마다 되풀이하지 않게). 다음 커밋을 기다립니다.
#    고친 뒤 손으로 다시 하려면: bash ~/escape-car/server/deploy/lightsail_deploy.sh
#
# 기록은 journalctl -u escape-deploy 로 봅니다. 할 일이 없을 때는 아무것도 남기지 않습니다.
# GITHUB_API는 시험할 때만 바꿉니다.
# 같은 알림을 2분마다 되풀이해 남기지 않게, 바뀌었을 때만 남깁니다
notice() {
  local state_dir="$1" key="$2" message="$3"
  if [[ "$(cat "$state_dir/notice" 2>/dev/null || true)" != "$key" ]]; then
    echo "$key" > "$state_dir/notice"
    echo "$message"
  fi
}

main() {
  set -euo pipefail

  local deploy_dir repo_dir state_dir slug target status
  deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  repo_dir="$(cd "$deploy_dir/../.." && pwd)"
  state_dir="$HOME/.escape-deploy"
  mkdir -p "$state_dir"

  cd "$repo_dir"
  git fetch --quiet origin main
  target="$(git rev-parse origin/main)"

  [[ "$(cat "$state_dir/deployed" 2>/dev/null || true)" == "$target" ]] && return 0
  [[ "$(cat "$state_dir/skipped" 2>/dev/null || true)" == "$target" ]] && return 0

  # https://github.com/owner/repo(.git) 또는 git@github.com:owner/repo(.git)
  slug="$(git remote get-url origin)"
  slug="${slug#*github.com[:/]}"
  slug="${slug%.git}"

  status="$(python3 - "$target" "$slug" "${GITHUB_API:-https://api.github.com}" <<'PY'
import json, sys, urllib.request
sha, slug, api = sys.argv[1:4]
url = f"{api}/repos/{slug}/commits/{sha}/check-runs?check_name=server-tests&per_page=100"
req = urllib.request.Request(url, headers={
    "Accept": "application/vnd.github+json",
    "User-Agent": "escape-deploy",
    "X-GitHub-Api-Version": "2022-11-28",
})
try:
    with urllib.request.urlopen(req, timeout=20) as resp:
        runs = json.load(resp).get("check_runs") or []
except Exception as exc:  # 네트워크, 요청 한도 등: 다음 차례에 다시 봅니다
    print(f"error {exc}")
    sys.exit(0)
if not runs:
    print("none")
else:
    run = max(runs, key=lambda r: r["id"])  # 다시 돌린 적이 있으면 가장 마지막 것
    if run["status"] != "completed":
        print("pending")
    elif run["conclusion"] == "success":
        print("success")
    else:
        print(f"failed {run['conclusion']} {run.get('html_url', '')}")
PY
)"

  case "$status" in
    success)
      echo "poll: ${target:0:7} 테스트 통과, 배포합니다"
      if ! bash "$deploy_dir/lightsail_deploy.sh" "$target"; then
        echo "$target" > "$state_dir/skipped"
        echo "poll: ${target:0:7} 배포 실패. 다음 커밋까지 다시 시도하지 않습니다" >&2
        return 1
      fi
      ;;
    failed*)
      # 다시 돌려서 통과하면 그때 배포하도록 계속 봅니다. 같은 내용은 한 번만 남깁니다
      notice "$state_dir" "$target failed" \
        "poll: ${target:0:7} 테스트가 통과하지 않아 배포하지 않습니다 (${status#failed })"
      ;;
    pending | none)
      notice "$state_dir" "$target waiting" "poll: ${target:0:7} 테스트 결과를 기다립니다"
      ;;
    *)
      echo "poll: GitHub에서 테스트 결과를 못 읽었습니다(${status#error }). 다음 차례에 다시 봅니다" >&2
      ;;
  esac
}

main "$@"
