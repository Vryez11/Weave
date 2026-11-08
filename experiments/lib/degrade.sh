#!/usr/bin/env bash
# 측정 구간의 200 응답 중 영역이 빠지거나 stale이었던 비율을 의존성마다 계산한다 (F-002, add-graceful-degradation design 결정 12).
#
#   lib/degrade.sh <k6 요약 파일>
#
# - k6 요약 파일은 `k6 run --summary-export`의 결과다. 분모는 availability{scenario:measure}의 passes(200 응답 수),
#   분자는 degraded_sections{scenario:measure,section:<의존성>}과 degraded_responses{scenario:measure}의 count다
#   (k6/feed.js가 센다). 지표가 없으면 0으로 본다.
# - 의존성(post user like recommendation ad)과 any(하나라도)마다 비율을 소수 넷째 자리까지 반올림해 JSON 하나로 쓴다.
#   200 응답이 없으면 JSON 문자열 "없음"이다. 형식은 e2e ExperimentDegradeRatioScenarioTest KDoc이 정한다.
# - 종료 코드: 0 계산함("없음" 포함), 2 계산하지 못함(표준 출력에는 쓰지 않고 이유를 표준 오류에 쓴다).
set -u
set -o pipefail

DEPENDENCIES="post user like recommendation ad"

fail() {
  echo "degrade.sh: $*" >&2
  exit 2
}

[ $# -eq 1 ] || fail "사용법: lib/degrade.sh <k6 요약 파일>"
summary="$1"

command -v jq >/dev/null 2>&1 || fail "jq가 필요하다"
[ -f "$summary" ] || fail "k6 요약 파일이 없다: $summary"

result="$(
  jq -ce --arg dependencies "$DEPENDENCIES" '
    def round_to($digits): pow(10; $digits) as $f | . * $f | round / $f;
    select(type == "object" and (.metrics | type) == "object")
    | .metrics as $m
    | ($m["availability{scenario:measure}"].passes // 0) as $ok
    | if $ok == 0 then "없음"
      else
        ($dependencies | split(" ")
          | map({key: ., value: (($m["degraded_sections{scenario:measure,section:\(.)}"].count // 0) / $ok | round_to(4))})
          | from_entries)
        + {any: (($m["degraded_responses{scenario:measure}"].count // 0) / $ok | round_to(4))}
      end
  ' "$summary" 2>/dev/null
)" || fail "k6 요약 파일이 --summary-export 형식의 JSON이 아니다: $summary"

echo "$result"
