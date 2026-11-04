#!/bin/bash
# weave용 Ralph 루프. OpenSpec change의 tasks.md를 작업 목록으로 쓴다.
# 사용법: scripts/ralph/ralph.sh <change-id> [최대 반복 수]
# Ralph(snarktank, MIT)의 ralph.sh를 바탕으로, prd.json 대신 OpenSpec tasks.md를 읽도록 고쳤다.
# 규칙: conventions/harness.md 3-2

set -euo pipefail

CHANGE_ID="${1:?사용법: scripts/ralph/ralph.sh <change-id> [최대 반복 수]}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CHANGE_DIR="$ROOT_DIR/openspec/changes/$CHANGE_ID"
TASKS_FILE="$CHANGE_DIR/tasks.md"

if [ ! -f "$TASKS_FILE" ]; then
  echo "tasks.md가 없습니다: $TASKS_FILE"
  exit 1
fi

# 기본 최대 반복 수는 남은 작업 수의 두 배
REMAINING=$(grep -cE '^[[:space:]]*- \[ \]' "$TASKS_FILE" || true)
MAX_ITERATIONS="${2:-$(( REMAINING * 2 ))}"
if [ "$MAX_ITERATIONS" -le 0 ]; then
  echo "남은 작업이 없습니다."
  exit 0
fi

PROMPT="$(sed "s/{{CHANGE_ID}}/$CHANGE_ID/g" "$SCRIPT_DIR/prompt.md")"
cd "$ROOT_DIR"
echo "Ralph 시작: $CHANGE_ID, 최대 ${MAX_ITERATIONS}번"

for i in $(seq 1 "$MAX_ITERATIONS"); do
  echo ""
  echo "=== Ralph 반복 $i / $MAX_ITERATIONS ($CHANGE_ID)"

  # 파일 수정은 허용하고, 셸 명령은 .claude/settings.json의 허용 목록 안에서만 쓴다.
  # WEAVE_RALPH=1이면 하네스 훅이 이미 있는 테스트와 실험 시나리오 수정을 막는다
  OUTPUT=$(WEAVE_RALPH=1 claude --print --permission-mode acceptEdits "$PROMPT" 2>&1 | tee /dev/stderr) || true

  if grep -q "<promise>COMPLETE</promise>" <<<"$OUTPUT"; then
    echo ""
    echo "구현 작업을 모두 마쳤습니다. 검토 루프로 넘깁니다."
    exit 0
  fi
  if grep -q "<promise>BLOCKED</promise>" <<<"$OUTPUT"; then
    echo ""
    echo "멈췄습니다. $CHANGE_DIR/progress.md를 확인하세요."
    exit 2
  fi
  sleep 2
done

echo ""
echo "최대 반복 수에 닿았습니다. $CHANGE_DIR/progress.md를 확인하세요."
exit 1
