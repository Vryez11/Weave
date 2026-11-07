#!/usr/bin/env bash
# 측정 구간의 서킷 상태 표본으로 의존성마다 거친 상태를 이어 적는다 (F-001, add-call-isolation design 결정 10).
#
#   lib/circuits.sh <query_range 결과 파일>
#
# - 결과 파일은 Prometheus /api/v1/query_range에 resilience4j_circuitbreaker_state를 측정 구간·5초 간격으로 물은 응답 본문이다.
#   시계열은 (name, state)마다 하나이고, 값은 그 시각에 그 상태면 "1"이다.
# - 의존성(post user like recommendation ad)마다 값이 1인 상태를 시각 순서로 읽어 대문자로 바꾸고, 바로 앞과 같은 상태는
#   한 번만 적은 뒤 " → "로 잇는다. 그 의존성의 시계열이 없으면 "없음"이다. 다른 name의 시계열은 건너뛴다.
# - 5초 간격 표본이라 그보다 짧게 머문 상태는 빠질 수 있다. 정확한 전이는 circuit_state_changed 로그로 본다.
# - 표준 출력에 JSON 하나를 쓰고 0으로 끝난다. 형식은 e2e ExperimentCircuitStateScenarioTest KDoc이 정한다.
#   결과 파일이 없거나 형식이 아니면 표준 출력에 쓰지 않고 2로 끝난다.
set -u
set -o pipefail

DEPENDENCIES="post user like recommendation ad"

fail() {
  echo "circuits.sh: $*" >&2
  exit 2
}

[ $# -eq 1 ] || fail "사용법: lib/circuits.sh <query_range 결과 파일>"
range="$1"

command -v jq >/dev/null 2>&1 || fail "jq가 필요하다"
[ -f "$range" ] || fail "query_range 결과 파일이 없다: $range"

result="$(
  jq -ce --arg dependencies "$DEPENDENCIES" '
    select(.status == "success" and .data.resultType == "matrix" and (.data.result | type) == "array")
    | .data.result as $series
    | $dependencies | split(" ")
    | map(. as $name
        | [$series[] | select(.metric.name == $name)] as $mine
        | {key: $name, value: (
            if ($mine | length) == 0 then "없음"
            else
              [$mine[] | .metric.state as $state | .values[]? | select(.[1] == "1") | {at: .[0], state: ($state | ascii_upcase)}]
              | sort_by(.at)
              | reduce .[].state as $s ([]; if length > 0 and .[-1] == $s then . else . + [$s] end)
              | if length == 0 then "없음" else join(" → ") end
            end)})
    | from_entries
  ' "$range"
)" || fail "Prometheus query_range 응답 형식이 아니다({\"status\": \"success\", \"data\": {\"resultType\": \"matrix\", ...}}): $range"

echo "$result"
