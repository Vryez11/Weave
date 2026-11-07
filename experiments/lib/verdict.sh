#!/usr/bin/env bash
# 시나리오 하나의 k6 요약으로 SLO 충족 여부와 기대 결과와의 일치를 판정한다 (F-007, design 결정 7, 8).
#
#   lib/verdict.sh <k6 요약 파일> <시나리오 ID> [기대 결과 파일]
#
# - k6 요약 파일은 `k6 run --summary-export`의 결과다. `{scenario:measure}`가 붙은 지표의 threshold만 본다.
#   이 형식에서 threshold 값 true는 기준을 넘었다(실패)는 뜻이다.
# - k6의 availability Rate는 보낸 요청만 센다. 보내지 못한 요청(dropped_iterations)도 실패로 세도록 가용성을
#   passes ÷ (측정 요청 수 + 보내지 못한 요청 수)로 다시 계산해 experiments/slo.json과 비교한다(스펙 "가용성 계산").
# - 기대 결과 파일의 기본은 experiments/expectations.json이다. 시나리오 값이 문자열이면 두 기준(가용성, p99)의 기대가
#   모두 그 값이고, 객체면 기준마다 따로다. 기준마다의 실제 결과가 모두 기대와 같아야 기대와 같다.
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
slo="$here/../slo.json"

command -v jq >/dev/null 2>&1 || fail "jq가 필요하다"
[ -f "$summary" ] || fail "k6 요약 파일이 없다: $summary"
[ -f "$expectations" ] || fail "기대 결과 파일이 없다: $expectations"
jq -e '.availability | type == "number"' "$slo" >/dev/null 2>&1 ||
  fail "SLO 파일 형식이 아니다({\"availability\": ..., \"p99Ms\": ...}): $slo"
jq -e 'type == "object" and (.metrics | type == "object")' "$summary" >/dev/null 2>&1 ||
  fail "k6 요약 파일이 --summary-export 형식의 JSON이 아니다: $summary"
jq -e '.scenarios | type == "object"' "$expectations" >/dev/null 2>&1 ||
  fail "기대 결과 파일 형식이 아니다({\"scenarios\": {...}}): $expectations"

# 기대는 문자열(두 기준 모두 같은 값)이나 기준마다의 객체({"availability": ..., "p99": ...})다 (add-call-isolation design 결정 10)
expectation="$(jq -c --arg id "$scenario" '.scenarios[$id] // empty' "$expectations")"
[ -n "$expectation" ] || fail "기대 결과에 $scenario 가 없다: $expectations"
jq -e '
  def value: . == "meet" or . == "miss";
  if type == "string" then value
  elif type == "object" then (keys == ["availability", "p99"]) and (.availability | value) and (.p99 | value)
  else false end
' <<<"$expectation" >/dev/null 2>&1 ||
  fail "$scenario 의 기대 결과는 meet, miss 또는 {\"availability\": meet|miss, \"p99\": meet|miss}여야 한다: $expectation"

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

# 보내지 못한 요청을 넣은 가용성이 SLO 아래인데 k6 availability threshold는 통과했으면 넘은 기준으로 더한다
thresholds="$(
  jq -c --slurpfile slo "$slo" --argjson thresholds "$thresholds" '
    $slo[0].availability as $target
    | .metrics as $m
    | ($m["availability{scenario:measure}"].passes // 0) as $ok
    | (($m["http_reqs{scenario:measure}"].count // 0) + ($m["dropped_iterations{scenario:measure}"].count // 0)) as $all
    | ([$thresholds[] | select(.failed and (.name | startswith("availability{scenario:measure}:")))] | length > 0) as $already
    | if $all > 0 and ($ok / $all) < $target and ($already | not)
      then $thresholds + [{name: "availability{scenario:measure}: 보내지 못한 요청 포함 rate>=\($target)", failed: true}]
      else $thresholds
      end
  ' "$summary"
)" || fail "k6 요약 파일로 가용성을 계산하지 못했다: $summary"

# 기준은 threshold 이름으로 가른다. 가용성(보내지 못한 요청을 넣은 것 포함), p99, 그 밖(other)은 기대가 없고 넘으면 기대와 다르다
result="$(
  jq -n \
    --arg scenario "$scenario" \
    --argjson expectation "$expectation" \
    --argjson thresholds "$thresholds" '
    def criterion: if startswith("availability{scenario:measure}:") then "availability"
                   elif startswith("feed_latency{scenario:measure}:") then "p99"
                   else "other" end;
    ([$thresholds[] | select(.failed) | .name]) as $failed
    | ($failed | length == 0) as $met
    | ([$failed[] | criterion]) as $failedCriteria
    | (["availability", "p99"] | map(. as $c | {key: $c, value: {
        met: ($failedCriteria | index($c) == null),
        expectation: (if ($expectation | type) == "string" then $expectation else $expectation[$c] end)
      }}) | from_entries) as $criteria
    | ([$criteria | to_entries[] | select((.value.expectation == "meet") != .value.met) | .key]
       + (if $failedCriteria | index("other") then ["other"] else [] end)) as $mismatched
    | ($mismatched | length == 0) as $matches
    | {
        scenario: $scenario,
        sloMet: $met,
        expectation: $expectation,
        criteria: $criteria,
        mismatchedCriteria: $mismatched,
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
