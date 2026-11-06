#!/usr/bin/env bash
# 시나리오 하나의 k6 요약으로 SLO 충족 여부와 기대 결과와의 일치를 판정한다 (F-007, design 결정 7, 8).
#
#   lib/verdict.sh <k6 요약 파일> <시나리오 ID> [기대 결과 파일]
#
# - k6 요약 파일은 `k6 run --summary-export`의 결과다. `{scenario:measure}`가 붙은 지표의 threshold만 본다.
#   이 형식에서 threshold 값 true는 기준을 넘었다(실패)는 뜻이다.
# - 기대 결과 파일의 기본은 experiments/expectations.json이다.
# - 표준 출력에 판정 JSON 하나를 쓴다. 형식은 e2e ExperimentVerdictScenarioTest KDoc이 정한다.
# - 종료 코드: 0 기대와 같음, 1 기대와 다름, 2 판정하지 못함(표준 출력에는 쓰지 않고 이유를 표준 오류에 쓴다).
set -u

here="$(cd "$(dirname "$0")" && pwd)"

fail() {
  echo "verdict.sh: $*" >&2
  exit 2
}

[ $# -ge 2 ] && [ $# -le 3 ] || fail "사용법: lib/verdict.sh <k6 요약 파일> <시나리오 ID> [기대 결과 파일]"
summary="$1"
scenario="$2"
expectations="${3:-$here/../expectations.json}"

command -v jq >/dev/null 2>&1 || fail "jq가 필요하다"
[ -f "$summary" ] || fail "k6 요약 파일이 없다: $summary"
[ -f "$expectations" ] || fail "기대 결과 파일이 없다: $expectations"
jq -e 'type == "object" and (.metrics | type == "object")' "$summary" >/dev/null 2>&1 ||
  fail "k6 요약 파일이 --summary-export 형식의 JSON이 아니다: $summary"
jq -e '.scenarios | type == "object"' "$expectations" >/dev/null 2>&1 ||
  fail "기대 결과 파일 형식이 아니다({\"scenarios\": {...}}): $expectations"

expectation="$(jq -r --arg id "$scenario" '.scenarios[$id] // empty' "$expectations")"
case "$expectation" in
  meet | miss) ;;
  "") fail "기대 결과에 $scenario 가 없다: $expectations" ;;
  *) fail "$scenario 의 기대 결과는 meet 또는 miss여야 한다: $expectation" ;;
esac

thresholds="$(
  jq -c '
    [.metrics | to_entries[]
      | select(.key | endswith("{scenario:measure}"))
      | .key as $metric
      | (.value.thresholds // {}) | to_entries[]
      | {name: "\($metric): \(.key)", failed: (.value == true)}]
  ' "$summary"
)" || fail "k6 요약 파일의 threshold를 읽지 못했다: $summary"
[ "$(jq 'length' <<<"$thresholds")" -gt 0 ] || fail "측정 구간({scenario:measure}) threshold가 없다: $summary"

result="$(
  jq -n \
    --arg scenario "$scenario" \
    --arg expectation "$expectation" \
    --argjson thresholds "$thresholds" '
    ([$thresholds[] | select(.failed) | .name]) as $failed
    | ($failed | length == 0) as $met
    | (($expectation == "meet") == $met) as $matches
    | {
        scenario: $scenario,
        sloMet: $met,
        expectation: $expectation,
        matchesExpectation: $matches,
        verdict: "\(if $met then "SLO 충족" else "SLO 미충족" end), \(if $matches then "기대와 같음" else "기대와 다름" end)",
        failedThresholds: $failed
      }
  '
)" || fail "판정 JSON을 만들지 못했다"

echo "$result"
if [ "$(jq -r '.matchesExpectation' <<<"$result")" = "true" ]; then
  exit 0
fi
exit 1
