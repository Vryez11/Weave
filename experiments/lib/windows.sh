#!/usr/bin/env bash
# k6 원본으로 측정 구간의 10초 구간 표, 장애 시각, 복구 시간을 계산한다 (F-007, design 결정 7).
#
#   lib/windows.sh <k6 원본 파일(.json.gz)> <측정 시간(초)>
#
# - k6 원본은 `k6 run --out json`의 결과를 gzip한 파일이다. Point 줄 중 피드 요청의 feed_latency, 장애 요청(kind: fault)의
#   http_req_duration, dropped_iterations만 읽는다. 피드 요청의 http_req_duration은 시간 초과된 요청이 0으로 남을 수 있어
#   읽지 않는다(add-fault-experiments design 결정 5).
# - 보낸 시각은 요청이면 data.time - data.value(k6는 끝난 시각에 점을 찍는다), 보내지 못한 요청이면 data.time이다.
# - 측정 시작은 scenario가 measure인 요청 중 가장 이른 보낸 시각이다. 시각은 모두 이것부터 잰 초로 적는다.
# - SLO는 experiments/slo.json에서 읽는다.
# - 표준 출력에 JSON 하나를 쓰고 0으로 끝난다. 형식은 e2e ExperimentWindowsScenarioTest KDoc이 정한다.
#   원본 파일이 없거나 측정 요청이 없으면 표준 출력에 쓰지 않고 2로 끝난다.
set -u
set -o pipefail

here="$(cd "$(dirname "$0")" && pwd)"

fail() {
  echo "windows.sh: $*" >&2
  exit 2
}

[ $# -eq 2 ] || fail "사용법: lib/windows.sh <k6 원본 파일(.json.gz)> <측정 시간(초)>"
raw="$1"
duration="$2"
slo="$here/../slo.json"

command -v jq >/dev/null 2>&1 || fail "jq가 필요하다"
command -v gzip >/dev/null 2>&1 || fail "gzip이 필요하다"
case "$duration" in
  '' | *[!0-9]* | 0) fail "측정 시간은 1 이상의 정수 초여야 한다: $duration" ;;
esac
[ -f "$raw" ] || fail "k6 원본 파일이 없다: $raw"
jq -e '(.availability | type == "number") and (.p99Ms | type == "number")' "$slo" >/dev/null 2>&1 ||
  fail "SLO 파일 형식이 아니다({\"availability\": ..., \"p99Ms\": ...}): $slo"

# 첫 jq는 줄마다 필요한 값만 남겨 [보낸 시각(epoch ms), 종류, 지연 또는 개수, 태그] 배열로 줄이고, 둘째 jq가 모아 계산한다.
# RFC 3339 시각의 소수 자릿수는 줄마다 다르므로 초와 소수 부분을 나눠 읽는다(jq의 fromdateiso8601은 소수 초를 받지 않는다).
result="$(
  gzip -dc "$raw" | jq -c '
    def epoch_ms:
      capture("^(?<s>[^.Z]+)(\\.(?<f>[0-9]+))?Z$")
      | ((.s + "Z") | fromdateiso8601) * 1000
        + (if .f == null then 0 else ((.f + "000000000")[0:9] | tonumber) / 1000000 end);
    select(.type == "Point"
      and (.metric == "feed_latency" or .metric == "dropped_iterations"
        or (.metric == "http_req_duration" and .data.tags.kind == "fault")))
    | .data as $d
    | ($d.time | epoch_ms) as $t
    | ($d.tags | {scenario, status, kind, action, target}) as $tags
    | if .metric != "dropped_iterations"
      then [$t - $d.value, "req", $d.value, $tags]
      else [$t, "dropped", $d.value, $tags]
      end
  ' | jq -s -c \
    --argjson duration "$duration" \
    --slurpfile slo "$slo" '
    def ms3: . * 1000 | round / 1000;
    def p99:
      sort as $v
      | ($v | length) as $n
      | if $n == 0 then null
        else (($n - 1) * 0.99) as $rank
          | ($rank | floor) as $lo
          | ($rank | ceil) as $hi
          | ($v[$lo] + ($v[$hi] - $v[$lo]) * ($rank - $lo)) * 10 | round / 10
        end;

    $slo[0] as $slo
    | (map(select(.[1] == "req" and .[3].scenario == "measure")) | map(.[0]) | min) as $start
    | if $start == null then error("측정 요청이 없다") else . end
    # 측정 시작부터 잰 밀리초. 구간 경계에 걸린 요청이 부동소수 오차로 앞 구간에 들어가지 않게 마이크로초에서 반올림한다.
    | def rel_ms: ((. - $start) * 1000 | round) / 1000;
    (($duration + 9) / 10 | floor) as $count
    | def window_of: (rel_ms / 10000 | floor) as $i | if $i < 0 then 0 elif $i >= $count then $count - 1 else $i end;

    map(select(.[3].scenario == "measure") | . + [.[0] | window_of]) as $measure
    | [range(0; $count) as $i
        | [$measure[] | select(.[4] == $i)] as $in
        | [$in[] | select(.[1] == "req")] as $reqs
        | ([$in[] | select(.[1] == "dropped") | .[2]] | add // 0) as $dropped
        | ($reqs | map(.[2]) | p99) as $p99
        | ($reqs | length) as $n
        | (if $n + $dropped == 0 then null
           else ([$reqs[] | select(.[3].status == "200")] | length) / ($n + $dropped)
           end) as $availability
        | {
            startSeconds: ($i * 10),
            endSeconds: ([($i + 1) * 10, $duration] | min),
            requests: $n,
            dropped: $dropped,
            p99Ms: $p99,
            availability: $availability,
            sloMet: ($p99 != null and $availability != null and $p99 <= $slo.p99Ms and $availability >= $slo.availability)
          }
      ] as $windows

    | [.[] | select(.[1] == "req" and .[3].kind == "fault")]
    | sort_by(.[0])
    | map({atSeconds: (.[0] | rel_ms / 1000 | ms3), action: .[3].action, target: .[3].target, status: .[3].status})
    | . as $faults

    | ([$faults[] | select(.action == "clear") | .atSeconds] | max) as $removed
    | (if $removed == null then null
       else
         ([range(0; $count) | select($windows[.].endSeconds > $removed)]) as $candidates
         | ([$candidates[] | select(. as $j | all($windows[$j:][]; .sloMet))] | first) as $j
         | if $j == null then
             {removedAtSeconds: $removed, recoveredWindowStartSeconds: null, recoverySeconds: null,
              summary: "측정 구간 안에 복구하지 못함"}
           else
             ($windows[$j].startSeconds) as $s
             | ([0, $s - $removed] | max | round) as $seconds
             | {removedAtSeconds: $removed, recoveredWindowStartSeconds: $s, recoverySeconds: $seconds,
                summary: "\($seconds)초"}
           end
       end) as $recovery

    | {measureStartEpochMs: ($start | round), windows: $windows, faults: $faults, recovery: $recovery}
  '
)" || fail "k6 원본 파일로 10초 구간을 계산하지 못했다(gzip한 k6 --out json 형식이고 측정 요청이 있어야 한다): $raw"

echo "$result"
