#!/bin/bash
# Stop 훅. 안쪽 루프: conventions/harness.md 3-1
# 에이전트가 턴을 끝내려 할 때, 바뀐 모듈의 린트와 단위 테스트를 돌리고 실패하면 턴을 끝내지 못하게 막는다.
# 같은 세션에서 세 번 연속 막혔으면 더 막지 않고 사람에게 넘긴다.
set -uo pipefail

input=$(cat)
session=$(jq -r '.session_id // "unknown"' <<<"$input")
root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"
cd "$root" || exit 0

counter_dir="$root/logs/harness"
counter="$counter_dir/stop-$session.count"
mkdir -p "$counter_dir"

# develop에서 갈라진 뒤 바뀐 파일(커밋한 것과 아직 안 한 것)을 모은다
base=$(git merge-base HEAD develop 2>/dev/null || git rev-parse HEAD)
changed=$( { git diff --name-only "$base"; git ls-files --others --exclude-standard; } | sort -u)

needs() { grep -qE "$1" <<<"$changed"; }

# 바뀐 모듈의 Gradle 경로를 모은다. 빌드 설정이 바뀌면 전체를 본다
modules=()
if needs '^(buildSrc/|gradle/|settings\.gradle\.kts$|build\.gradle\.kts$)'; then
  modules=("")
else
  for dir in feed-service post-service user-service like-service recommendation-service ad-service libs/observability libs/fault-injection e2e; do
    if needs "^$dir/"; then
      modules+=(":${dir//\//:}:")
    fi
  done
fi

failures=""
run() {
  local name="$1"; shift
  local out
  if ! out=$("$@" 2>&1); then
    failures+=$'\n'"## $name 실패 ($*)"$'\n'"$(tail -n 40 <<<"$out")"$'\n'
  fi
}

if [ ${#modules[@]} -gt 0 ]; then
  lint=() tests=()
  for m in "${modules[@]}"; do
    lint+=("${m}ktlintCheck")
    tests+=("${m}test")
  done
  # ktlint-gradle은 위반이 있던 파일을 지워도 이전 결과를 계속 보고할 때가 있다.
  # 실패하면 증분 결과를 버리고 한 번 더 돌려 진짜 위반인지 확인한다(ktlint는 컴파일에 기대지 않아 빠르다)
  ./gradlew --quiet "${lint[@]}" >/dev/null 2>&1 \
    || run "Kotlin 린트" ./gradlew --quiet --rerun-tasks "${lint[@]}"
  run "타입 검사·단위 테스트" ./gradlew --quiet "${tests[@]}"
fi

if needs '^openspec/' && command -v openspec >/dev/null; then
  run "OpenSpec 검사" openspec validate --all --strict --no-interactive
fi

if [ -z "$failures" ]; then
  rm -f "$counter"
  exit 0
fi

count=$(( $(cat "$counter" 2>/dev/null || echo 0) + 1 ))
echo "$count" > "$counter"

if [ "$count" -ge 3 ]; then
  rm -f "$counter"
  jq -n --arg msg "하네스 검사가 세 번 연속 실패해 더 막지 않습니다. 사람이 확인해야 합니다.$failures" \
    '{systemMessage: $msg}'
  exit 0
fi

jq -n --arg reason "자동 검사가 실패했습니다($count/3). 고친 뒤 다시 끝내세요.$failures" \
  '{decision: "block", reason: $reason}'
exit 0
