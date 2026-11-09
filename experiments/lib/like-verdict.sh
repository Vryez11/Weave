#!/usr/bin/env bash
# S-11 방식별 결과를 판정한다 (F-003, add-idempotent-like design 결정 10).
#
#   experiments/lib/like-verdict.sh <방식별 결과 파일>
#
# 인자와 출력 형식은 e2e ExperimentLikeVerdictScenarioTest KDoc이 정한다.
# 종료 코드: 0 정합성 유지, 1 정합성 깨짐, 2 판정하지 못함(표준 출력에 아무것도 쓰지 않는다).
set -u
set -o pipefail

file="${1:-}"
if [ -z "$file" ] || [ ! -f "$file" ]; then
  echo "like-verdict.sh: 결과 파일이 없다: $file" >&2
  exit 2
fi
if ! jq -e '(.runs | type == "array") and (.runs | length > 0)' "$file" >/dev/null 2>&1; then
  echo "like-verdict.sh: runs가 있는 JSON이 아니다: $file" >&2
  exit 2
fi

# shellcheck disable=SC2016
output="$(jq '
  def broken_reasons:
    (if .mismatch == null then ["mismatch 값 없음"] elif .mismatch != 0 then ["mismatch \(.mismatch)"] else [] end)
    + (if .failed != 0 then ["failed \(.failed)"] else [] end);
  (.runs
    | map(select(.strategy != "read-modify-write") | {strategy, shards, reasons: broken_reasons} | select(.reasons | length > 0))
  ) as $broken
  | {
      verdict: (if ($broken | length) == 0 then "정합성 유지" else "정합성 깨짐" end),
      brokenRuns: $broken,
      notReproduced: ([.runs[] | select(.strategy == "read-modify-write" and .mismatch == 0) | .strategy] | unique)
    }
' "$file")" || {
  echo "like-verdict.sh: 결과 파일을 판정하지 못했다: $file" >&2
  exit 2
}

echo "$output"
[ "$(jq -r '.verdict' <<<"$output")" = "정합성 유지" ] || exit 1
exit 0
