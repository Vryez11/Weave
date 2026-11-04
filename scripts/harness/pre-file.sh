#!/bin/bash
# PreToolUse 훅 (Read, Edit, Write, MultiEdit). 퍼미션 하네스: conventions/harness.md 2-2
# - .env 계열(.env.example 제외)은 읽지도 고치지도 않는다
# - Ralph 루프 중(WEAVE_RALPH=1)에는 이미 커밋된 테스트와 장애 실험 시나리오를 고치지 않는다
set -euo pipefail

input=$(cat)
tool=$(jq -r '.tool_name // empty' <<<"$input")
file=$(jq -r '.tool_input.file_path // empty' <<<"$input")
[ -n "$file" ] || exit 0

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"
rel="${file#"$root"/}"

deny() {
  jq -n --arg reason "$1" '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason: $reason
    }
  }'
  exit 0
}

case "$rel" in
  .env.example | */.env.example) ;;
  .env | .env.* | */.env | */.env.*)
    deny "비밀 값 파일($rel)은 읽거나 고치지 않습니다. 필요한 변수 이름은 .env.example을 보세요." ;;
esac

if [ "${WEAVE_RALPH:-}" = "1" ] && [ "$tool" != "Read" ]; then
  case "$rel" in
    */src/test/* | experiments/scenarios/*)
      if git -C "$root" ls-files --error-unmatch "$rel" >/dev/null 2>&1; then
        deny "Ralph 루프 중에는 이미 있는 시나리오 테스트나 실험 시나리오($rel)를 고치지 않습니다. 틀렸다면 progress.md에 이유를 적고 <promise>BLOCKED</promise>로 멈추세요."
      fi
      ;;
  esac
fi

exit 0
