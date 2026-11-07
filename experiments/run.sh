#!/usr/bin/env bash
# 장애 실험 시나리오 하나를 돌린다 (F-007, design 결정 6, 7).
#
#   experiments/run.sh <S-xx> [--rps N] [--warmup 1m] [--duration 5m]
#
# 1. 시나리오 ID와 옵션을 확인한다. 없는 ID면 있는 ID 목록을 출력하고 2로 끝난다(아무것도 바꾸지 않는다).
# 2. Compose 환경(feed-service, Toxiproxy, 하위 서비스 5개)이 healthy인지 본다. 아니면 띄우는 명령을 알려 주고 2로 끝난다.
# 3. trap(EXIT, INT, TERM)을 건다. 어떻게 끝나도 장애를 모두 되돌린다(k6/reset.js). 되돌리지 못하면 2로 끝난다.
# 4. 장애를 되돌리고 feed-service를 다시 띄워 healthy가 될 때까지 기다린다(앞 실행이 남긴 스레드를 지운다).
# 5. results/<UTC yyyyMMdd-HHmmss>-<S-xx>/에 k6 요약(summary.json)과 원본(raw.json, 끝나면 raw.json.gz)을 쓴다.
# 6. k6가 0이나 99(threshold 넘음)로 끝나면 판정(lib/verdict.sh), degrade 비율(lib/degrade.sh), 10초 구간(lib/windows.sh), Prometheus 질의로
#    report.json과 report.md를 쓴다. report.json 형식은 e2e ExperimentRunComposeScenarioTest KDoc이 정한다.
# 7. 종료 코드: 0 기대와 같음, 1 기대와 다름, 2 실행하지 못함(잘못된 인자, 환경 없음, 되돌리기 실패, k6 실패, 중단).
#
# - 옵션 기본값은 config.json이다. 시간은 h·m·s를 이어 쓴다(예: 20s, 5m, 1m30s).
# - Compose 프로젝트 이름과 env 파일은 환경 변수 COMPOSE_PROJECT_NAME, COMPOSE_ENV_FILE로 받는다. 없으면
#   docker compose의 기본 프로젝트와 레포 루트의 .env다. env 파일은 읽지 않고 docker compose --env-file에 경로만 넘긴다.
# - 표준 출력에는 마지막 판정과 리포트 경로만 쓴다. 진행 상황과 k6 출력은 표준 오류로 간다.
# - macOS 기본 bash 3.2에서 돈다. 필요한 것은 bash, jq, gzip, docker compose다.
set -u
set -o pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"

DEPENDENCIES="post user like recommendation ad"
REQUIRED_SERVICES="feed-service toxiproxy post-service user-service like-service recommendation-service ad-service"
FEED_HEALTH_TIMEOUT_SECONDS=180
# compose.yaml이 Grafana를 호스트에 여는 주소
GRAFANA_URL="http://localhost:3000"
# Prometheus 수집 간격 5초(docker/prometheus/prometheus.yml)의 두 배. 측정 구간 끝까지 긁힌 뒤에 질의한다
SCRAPE_MARGIN_SECONDS=10
# Prometheus의 feed-service 대상(prometheus.yml의 static_configs). 관리 포트다(compose.yaml MANAGEMENT_SERVER_PORT)
FEED='instance="feed-service:8090"'

log() { echo "run.sh: $*" >&2; }
fail() {
  log "$*"
  exit 2
}

usage() {
  cat >&2 <<'EOF'
사용법: experiments/run.sh <S-xx> [--rps N] [--warmup 1m] [--duration 5m]
  옵션 기본값은 experiments/config.json. 시간은 h·m·s를 이어 쓴다(예: 20s, 5m, 1m30s).
  환경 변수 COMPOSE_PROJECT_NAME, COMPOSE_ENV_FILE로 Compose 프로젝트와 env 파일을 바꾼다.
EOF
}

list_scenarios() {
  local file
  for file in "$here"/scenarios/*.json; do
    [ -f "$file" ] && basename "$file" .json
  done
}

# k6 시간 형식 중 h·m·s만 받아 정수 초로 바꾼다. 형식이 아니면 1로 끝난다
to_seconds() {
  local value="$1" pattern='^([0-9]+h)?([0-9]+m)?([0-9]+s)?$' total=0 part
  [ -n "$value" ] || return 1
  [[ "$value" =~ $pattern ]] || return 1
  for part in "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}"; do
    case "$part" in
      *h) total=$((total + 10#${part%h} * 3600)) ;;
      *m) total=$((total + 10#${part%m} * 60)) ;;
      *s) total=$((total + 10#${part%s})) ;;
    esac
  done
  echo "$total"
}

# epoch 밀리초를 Prometheus API의 초(소수 셋째 자리)로 바꾼다
ms_to_seconds() { printf '%d.%03d' $(($1 / 1000)) $(($1 % 1000)); }

# ---------- 1. 인자 ----------

case "${1:-}" in
  -h | --help)
    usage
    exit 0
    ;;
  '')
    usage
    exit 2
    ;;
esac
scenario="$1"
shift

command -v jq >/dev/null 2>&1 || fail "jq가 필요하다"
command -v gzip >/dev/null 2>&1 || fail "gzip이 필요하다"

known=1
case "$scenario" in
  *[!A-Za-z0-9_-]*) known=0 ;;
esac
if [ "$known" = 0 ] || [ ! -f "$here/scenarios/$scenario.json" ]; then
  log "없는 시나리오다: $scenario"
  echo "있는 시나리오:" >&2
  list_scenarios >&2
  exit 2
fi

rps=""
warmup=""
duration=""
while [ $# -gt 0 ]; do
  case "$1" in
    --rps | --warmup | --duration)
      [ $# -ge 2 ] || fail "$1 에 값이 없다"
      case "$1" in
        --rps) rps="$2" ;;
        --warmup) warmup="$2" ;;
        --duration) duration="$2" ;;
      esac
      shift 2
      ;;
    *)
      usage
      fail "알 수 없는 인자: $1"
      ;;
  esac
done

config="$here/config.json"
[ -n "$rps" ] || rps="$(jq -r '.rps' "$config")" || fail "config.json을 읽지 못했다: $config"
[ -n "$warmup" ] || warmup="$(jq -r '.warmup' "$config")" || fail "config.json을 읽지 못했다: $config"
[ -n "$duration" ] || duration="$(jq -r '.duration' "$config")" || fail "config.json을 읽지 못했다: $config"

case "$rps" in
  '' | *[!0-9]* | 0*) fail "--rps는 1 이상의 정수여야 한다: $rps" ;;
esac
warmup_seconds="$(to_seconds "$warmup")" || fail "--warmup은 h·m·s 시간이어야 한다(예: 5s, 1m): $warmup"
duration_seconds="$(to_seconds "$duration")" || fail "--duration은 h·m·s 시간이어야 한다(예: 20s, 5m): $duration"
[ "$warmup_seconds" -gt 0 ] || fail "--warmup은 0보다 길어야 한다: $warmup"
[ "$duration_seconds" -gt 0 ] || fail "--duration은 0보다 길어야 한다: $duration"

# ---------- 2. 환경 ----------

command -v docker >/dev/null 2>&1 || fail "docker가 필요하다"
env_file="${COMPOSE_ENV_FILE:-$root/.env}"
cd "$root" || fail "레포 루트로 옮기지 못했다: $root"

# COMPOSE_PROJECT_NAME은 docker compose가 직접 읽는다
compose() { docker compose -f "$root/compose.yaml" --env-file "$env_file" "$@"; }

up_hint() {
  local project=""
  [ -n "${COMPOSE_PROJECT_NAME:-}" ] && project=" -p $COMPOSE_PROJECT_NAME"
  echo "환경을 먼저 띄운다(레포 루트에서): ./gradlew bootJar && docker compose$project --env-file $env_file up -d --build --wait" >&2
}

# 서비스 이름 하나의 헬스 상태. 컨테이너가 없으면 "없음"
health_of() {
  jq -rs --arg service "$1" 'flatten | map(select(.Service == $service)) | .[0].Health // "없음" | if . == "" then "헬스 체크 없음" else . end' <<<"$2"
}

if [ ! -f "$env_file" ]; then
  log "env 파일이 없다: $env_file (항목은 .env.example)"
  up_hint
  exit 2
fi
states="$(compose ps --format json)" || {
  log "docker compose ps가 실패했다(Docker가 떠 있는지, env 파일 항목이 채워졌는지 본다)"
  up_hint
  exit 2
}
unhealthy=""
for service in $REQUIRED_SERVICES; do
  state="$(health_of "$service" "$states")"
  [ "$state" = healthy ] || unhealthy="$unhealthy $service($state)"
done
if [ -n "$unhealthy" ]; then
  log "Compose 환경이 떠 있지 않다. healthy가 아닌 서비스:$unhealthy"
  up_hint
  exit 2
fi

# ---------- 3. trap ----------

k6_pid=""
k6_container="weave-k6-$scenario-$$"

reset_faults() {
  log "장애를 되돌린다(Toxiproxy, 하위 서비스 5개, ad 요청 한도)"
  compose run --rm -T k6 run --quiet --summary-mode=disabled k6/reset.js >&2
}

# 부하 중에 끝나면 k6를 멈춘다. 먼저 SIGTERM으로 teardown()을 돌릴 틈을 주고, 남은 컨테이너는 지운다
stop_k6() {
  local waited=0
  [ -n "$k6_pid" ] || return 0
  if kill -0 "$k6_pid" 2>/dev/null; then
    log "k6를 멈춘다"
    kill -TERM "$k6_pid" 2>/dev/null
    while kill -0 "$k6_pid" 2>/dev/null && [ "$waited" -lt 15 ]; do
      sleep 1
      waited=$((waited + 1))
    done
  fi
  docker rm -f "$k6_container" >/dev/null 2>&1
  wait "$k6_pid" 2>/dev/null
  k6_pid=""
}

cleanup() {
  local status=$?
  # 되돌리기는 끝까지 한다. 도중에 온 신호는 무시한다
  trap '' INT TERM
  stop_k6
  if ! reset_faults; then
    log "장애를 되돌리지 못했다. Toxiproxy(127.0.0.1:8474)와 하위 서비스 /actuator/faults, ad /actuator/quota를 확인한다"
    status=2
  fi
  exit "$status"
}

on_signal() {
  log "중단 신호를 받았다"
  exit 2
}

trap cleanup EXIT
trap on_signal INT TERM

# ---------- 4. 같은 상태에서 시작 ----------

reset_faults || fail "장애를 되돌리지 못했다"

log "feed-service를 다시 띄운다"
compose restart feed-service >&2 || fail "feed-service를 다시 띄우지 못했다"
waited=0
while :; do
  state="$(health_of feed-service "$(compose ps --format json feed-service)")"
  [ "$state" = healthy ] && break
  [ "$waited" -lt "$FEED_HEALTH_TIMEOUT_SECONDS" ] ||
    fail "feed-service가 ${FEED_HEALTH_TIMEOUT_SECONDS}초 안에 healthy가 되지 않았다($state). docker compose logs feed-service를 본다"
  sleep 2
  waited=$((waited + 2))
done
log "feed-service가 healthy다(${waited}초 기다림)"

# ---------- 5. 부하 ----------

stamp="$(date -u '+%Y%m%d-%H%M%S|%Y-%m-%dT%H:%M:%SZ')"
folder_name="${stamp%%|*}-$scenario"
started_at="${stamp#*|}"
folder="$here/results/$folder_name"
mkdir -p "$here/results" || fail "결과 폴더를 만들지 못했다: $here/results"
mkdir "$folder" || fail "결과 폴더를 만들지 못했다(이미 있다?): $folder"

log "$scenario: ${rps} RPS, 준비 $warmup, 측정 $duration → ${folder#"$root"/}"
# 백그라운드로 돌려 wait가 신호에 바로 깨게 한다(앞에서 돌리면 bash는 k6가 끝날 때까지 trap을 미룬다).
# compose 함수를 거치지 않고 docker를 바로 띄워 $!가 docker 프로세스가 되게 한다(stop_k6가 SIGTERM을 보낸다)
docker compose -f "$root/compose.yaml" --env-file "$env_file" \
  run --rm -T --name "$k6_container" --user "$(id -u):$(id -g)" \
  -e "SCENARIO=$scenario" -e "RPS=$rps" -e "WARMUP=$warmup" -e "DURATION=$duration" \
  k6 run --quiet \
  --summary-export "results/$folder_name/summary.json" \
  --out "json=results/$folder_name/raw.json" \
  k6/feed.js >&2 &
k6_pid=$!
wait "$k6_pid"
k6_status=$?
k6_pid=""

case "$k6_status" in
  0 | 99) ;;
  *) fail "k6가 실패했다(종료 코드 $k6_status). 위의 k6 출력을 본다" ;;
esac
[ -f "$folder/summary.json" ] || fail "k6 요약이 없다: $folder/summary.json"
[ -f "$folder/raw.json" ] || fail "k6 원본이 없다: $folder/raw.json"
gzip -f "$folder/raw.json" || fail "k6 원본을 gzip하지 못했다: $folder/raw.json"

# ---------- 6. 판정과 리포트 ----------

verdict="$("$here/lib/verdict.sh" "$folder/summary.json" "$scenario")"
verdict_status=$?
[ "$verdict_status" -le 1 ] || fail "판정하지 못했다(lib/verdict.sh 종료 코드 $verdict_status)"

degrade_ratio="$("$here/lib/degrade.sh" "$folder/summary.json")" ||
  fail "degrade 비율을 계산하지 못했다(lib/degrade.sh)"

windows="$("$here/lib/windows.sh" "$folder/raw.json.gz" "$duration_seconds")" ||
  fail "10초 구간을 계산하지 못했다(lib/windows.sh)"
start_ms="$(jq -r '.measureStartEpochMs' <<<"$windows")"
end_ms=$((start_ms + duration_seconds * 1000))
start_s="$(ms_to_seconds "$start_ms")"
end_s="$(ms_to_seconds "$end_ms")"

ready=$((end_ms / 1000 + SCRAPE_MARGIN_SECONDS))
now=$(date +%s)
if [ "$now" -lt "$ready" ]; then
  log "Prometheus가 측정 구간 끝까지 긁기를 $((ready - now))초 기다린다"
  sleep $((ready - now))
fi

prometheus_failed=0
# prom <query|query_range> <PromQL>: 측정 구간으로 질의해 .data.result를 쓴다. 실패하면 null을 쓴다.
# Prometheus는 호스트에 열려 있어도 호스트에 curl이 있다고 보지 않아, 컨테이너 안의 wget으로 부른다
prom() {
  local api="$1" query="$2" params body
  params="query=$(jq -rn --arg q "$query" '$q | @uri')"
  if [ "$api" = query ]; then
    params="$params&time=$end_s"
  else
    params="$params&start=$start_s&end=$end_s&step=5"
  fi
  if body="$(compose exec -T prometheus wget -qO- "http://localhost:9090/api/v1/$api?$params" 2>/dev/null)" &&
    jq -ce 'select(.status == "success") | .data.result' <<<"$body"; then
    return 0
  fi
  log "Prometheus 질의 실패($api): $query"
  echo null
  return 1
}

range="${duration_seconds}s"
q_p99="$(prom query "histogram_quantile(0.99, sum by (client_name, le) (increase(http_client_requests_seconds_bucket{$FEED}[$range])))")" || prometheus_failed=1
q_calls="$(prom query "sum by (client_name) (increase(http_client_requests_seconds_count{$FEED}[$range]))")" || prometheus_failed=1
q_failed="$(prom query "sum by (client_name) (increase(http_client_requests_seconds_count{$FEED,outcome!=\"SUCCESS\"}[$range]))")" || prometheus_failed=1
q_received="$(prom query "sum by (instance) (increase(http_server_requests_seconds_count{instance=~\"(post|user|like|recommendation|ad)-service:[0-9]+\",uri!~\"/actuator.*\"}[$range]))")" || prometheus_failed=1
q_feed="$(prom query "sum(increase(http_server_requests_seconds_count{$FEED,uri=\"/feed\"}[$range]))")" || prometheus_failed=1
q_tomcat="$(prom query_range "sum(tomcat_threads_busy_threads{$FEED})")" || prometheus_failed=1
# 의존성별 ThreadPool 벌크헤드(F-001). Semaphore 벌크헤드는 지표 이름이 달라 섞이지 않는다
q_bulkhead_active="$(prom query_range "max by (name) (resilience4j_bulkhead_active_thread_count{$FEED})")" || prometheus_failed=1
q_bulkhead_queue="$(prom query_range "max by (name) (resilience4j_bulkhead_queue_depth{$FEED})")" || prometheus_failed=1
q_cpu="$(prom query_range "max(process_cpu_usage{$FEED})")" || prometheus_failed=1
q_leased="$(prom query_range "sum by (httpclient) (httpcomponents_httpclient_pool_total_connections{$FEED,state=\"leased\"})")" || prometheus_failed=1
q_pending="$(prom query_range "sum by (httpclient) (httpcomponents_httpclient_pool_total_pending{$FEED})")" || prometheus_failed=1
# 서킷 상태는 5초 간격 표본을 시간순으로 이어 적는다(lib/circuits.sh). 질의 응답 형식으로 결과 폴더에 남겨 넘긴다
circuit_state=null
if q_circuit="$(prom query_range "max by (name, state) (resilience4j_circuitbreaker_state{$FEED})")"; then
  jq -n --argjson result "$q_circuit" '{status: "success", data: {resultType: "matrix", result: $result}}' \
    >"$folder/circuit-state.json" &&
    circuit_state="$("$here/lib/circuits.sh" "$folder/circuit-state.json")" ||
    { log "서킷 상태를 이어 적지 못했다(lib/circuits.sh)"; circuit_state=null; }
else
  prometheus_failed=1
fi
# 측정 구간에 feed-service 지표를 긁은 비율. 1보다 작으면 자원 사용량은 긁힌 구간만의 값이다
q_up="$(prom query_range "up{$FEED}")" || prometheus_failed=1

commit="$(git -C "$root" rev-parse --short HEAD 2>/dev/null)" || commit="unknown"
if [ "$commit" != unknown ] && [ -n "$(git -C "$root" status --porcelain 2>/dev/null)" ]; then
  commit="$commit-dirty"
fi

report="$(
  jq -n \
    --arg scenario "$scenario" \
    --arg commit "$commit" \
    --arg startedAt "$started_at" \
    --argjson rps "$rps" \
    --argjson warmupSeconds "$warmup_seconds" \
    --argjson durationSeconds "$duration_seconds" \
    --argjson startMs "$start_ms" \
    --argjson endMs "$end_ms" \
    --arg grafana "$GRAFANA_URL/d/weave-feed?from=$start_ms&to=$end_ms" \
    --arg dependencies "$DEPENDENCIES" \
    --argjson prometheusFailed "$prometheus_failed" \
    --slurpfile summary "$folder/summary.json" \
    --slurpfile definition "$here/scenarios/$scenario.json" \
    --argjson verdict "$verdict" \
    --argjson degradeRatio "$degrade_ratio" \
    --argjson windows "$windows" \
    --argjson p99 "$q_p99" \
    --argjson calls "$q_calls" \
    --argjson failed "$q_failed" \
    --argjson received "$q_received" \
    --argjson feed "$q_feed" \
    --argjson tomcat "$q_tomcat" \
    --argjson bulkheadActive "$q_bulkhead_active" \
    --argjson bulkheadQueue "$q_bulkhead_queue" \
    --argjson cpu "$q_cpu" \
    --argjson leased "$q_leased" \
    --argjson pending "$q_pending" \
    --argjson circuitState "$circuit_state" \
    --argjson up "$q_up" '
    def round_to($digits): pow(10; $digits) as $f | . * $f | round / $f;
    # Prometheus 값은 문자열이다. NaN·Inf는 null로 둔다
    def num: if type == "string" and test("^[-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?$") then tonumber else null end;
    def instant_by($label): if . == null then null else map({key: .metric[$label], value: (.value[1] | num)}) | from_entries end;
    def instant_single: if . == null then null else (.[0].value[1] // null | num) end;
    def stats: [.[]?.values[]?[1] | num | select(. != null)] as $v
      | if ($v | length) == 0 then {max: null, avg: null}
        else {max: ($v | max | round_to(4)), avg: ($v | add / length | round_to(4))} end;
    def range_by($label; $names):
      if . == null then ($names | map({key: ., value: {max: null, avg: null}}) | from_entries)
      else . as $series | $names | map(. as $n | {key: $n, value: ([$series[] | select(.metric[$label] == $n)] | stats)}) | from_entries
      end;
    def proxy_action: . == "latency" or . == "timeout" or . == "down";

    ($dependencies | split(" ")) as $names
    | $summary[0].metrics as $m
    | ($m["http_reqs{scenario:measure}"].count // 0) as $requests
    | ($m["dropped_iterations{scenario:measure}"].count // 0) as $dropped
    | ($m["feed_latency{scenario:measure}"] // {}) as $latency
    | ($m["availability{scenario:measure}"].passes // 0) as $ok

    | ($p99 | instant_by("client_name")) as $p99By
    | ($calls | instant_by("client_name")) as $callsBy
    | ($failed | instant_by("client_name")) as $failedBy
    | (if $received == null then null
       else $received | map({key: (.metric.instance | sub("-service:[0-9]+$"; "")), value: (.value[1] | num)}) | from_entries
       end) as $receivedBy
    | ($feed | instant_single) as $feedRequests

    | ($windows.faults // []) as $sent
    | [$sent[] | select(.action != "clear" and .action != "reset")] as $injects
    | (($definition[0].faults // []) | to_entries | sort_by([.value.at, .key]) | map(.value | select(.action != "clear"))) as $entries
    | {
        scenario: $scenario,
        title: ($definition[0].title // null),
        commit: $commit,
        startedAt: $startedAt,
        rps: $rps,
        warmupSeconds: $warmupSeconds,
        durationSeconds: $durationSeconds,
        measure: {startEpochMs: $startMs, endEpochMs: $endMs},
        requests: $requests,
        dropped: $dropped,
        latencyMs: {
          p50: ($latency["p(50)"] // null | if . == null then null else round_to(1) end),
          p95: ($latency["p(95)"] // null | if . == null then null else round_to(1) end),
          p99: ($latency["p(99)"] // null | if . == null then null else round_to(1) end)
        },
        availability: (if $requests + $dropped == 0 then null else ($ok / ($requests + $dropped) | round_to(6)) end),
        sloMet: $verdict.sloMet,
        expectation: $verdict.expectation,
        criteria: $verdict.criteria,
        mismatchedCriteria: $verdict.mismatchedCriteria,
        matchesExpectation: $verdict.matchesExpectation,
        verdict: $verdict.verdict,
        failedThresholds: $verdict.failedThresholds,
        dependencies: ($names | map(. as $n | {key: $n, value: {
          p99Ms: (if $p99By == null or $p99By[$n] == null then null else $p99By[$n] * 1000 | round_to(1) end),
          failureRate: (if $callsBy == null or $failedBy == null or ($callsBy[$n] // 0) == 0 then null
                        else (($failedBy[$n] // 0) / $callsBy[$n]) | round_to(4) end),
          requestRatio: (if $receivedBy == null or $feedRequests == null or $feedRequests == 0 then null
                         else (($receivedBy[$n] // 0) / $feedRequests) | round_to(3) end)
        }}) | from_entries),
        resources: {
          tomcatBusyThreads: ($tomcat | stats),
          feedCpuUsage: ($cpu | stats),
          bulkheads: (($bulkheadActive | range_by("name"; $names)) as $a
            | ($bulkheadQueue | range_by("name"; $names)) as $q
            | $names | map({key: ., value: {activeThreads: $a[.], queueDepth: $q[.]}}) | from_entries),
          connectionPools: (($leased | range_by("httpclient"; $names)) as $l
            | ($pending | range_by("httpclient"; $names)) as $p
            | $names | map({key: ., value: {leased: $l[.], pending: $p[.]}}) | from_entries)
        },
        # 측정 구간 200 응답 중 영역이 빠지거나 stale인 비율(lib/degrade.sh). 200 응답이 없으면 "없음"
        degradeRatio: $degradeRatio,
        # 의존성마다 측정 구간에 거친 서킷 상태(lib/circuits.sh). 서킷 지표가 없는 의존성은 "없음", 질의가 실패하면 null
        circuitState: $circuitState,
        grafanaUrl: $grafana,
        prometheusFailed: ($prometheusFailed == 1),
        # 표본이 하나도 없으면(대상이 없다) 0이다
        feedScrapeRatio: (if $up == null then null
                          else [$up[]?.values[]?[1] | num | select(. != null)] as $v
                            | if ($v | length) == 0 then 0
                              else ([$v[] | select(. == 1)] | length) / ($v | length) | round_to(4) end
                          end),
        faults: [range(0; $entries | length) as $i | $entries[$i] as $e
          | if $e.at >= $durationSeconds then
              {at: $e.at, action: $e.action, target: $e.target, injectedAtSeconds: null, removedAtSeconds: null, skipped: true}
            else
              ($injects[$i].atSeconds // null) as $in
              # 거둔 시각: 같은 의존성의 다음 clear, 없으면 teardown()의 되돌리기(프록시는 POST /reset, 앱 수준은 그 서비스)
              | (if $in == null then null
                 else first($sent[] | select(.atSeconds >= $in and .action == "clear" and .target == $e.target) | .atSeconds)
                   // (if ($e.action | proxy_action)
                       then first($sent[] | select(.atSeconds >= $in and .action == "reset" and .target == "all") | .atSeconds)
                       else [$sent[] | select(.atSeconds >= $in and .action == "reset" and .target == $e.target) | .atSeconds] | last
                       end)
                   // null
                 end) as $out
              | {at: $e.at, action: $e.action, target: $e.target, injectedAtSeconds: $in, removedAtSeconds: $out, skipped: false}
            end],
        windows: $windows.windows,
        recovery: $windows.recovery
      }
  '
)" || fail "report.json을 만들지 못했다"
echo "$report" >"$folder/report.json" || fail "report.json을 쓰지 못했다: $folder/report.json"

jq -r '
  def show: if . == null then "값 없음" elif type == "boolean" then (if . then "예" else "아니요" end) else tostring end;
  def ms: if . == null then "값 없음" else "\(.)ms" end;
  def seconds: if . == null then "값 없음" else "\(.)초" end;
  def utc: . / 1000 | floor | todate;
  def row(cells): "| " + (cells | map(show) | join(" | ")) + " |";
  # label은 jq 예약어(label $name | ... break)라 함수 이름으로 쓰지 않는다
  def criterion_name: {availability: "가용성", p99: "p99", other: "그 밖의 기준"}[.];
  def expected: if type == "string" then (if . == "meet" then "SLO 충족(meet)" else "SLO 미충족(miss)" end)
                else "가용성 \(.availability) · p99 \(.p99)" end;
  [
    "# 장애 실험 리포트: \(.scenario) \(.title // "")",
    "",
    "**판정: \(.verdict)**" + (if (.failedThresholds | length) > 0 then " (넘은 기준: \(.failedThresholds | join(", ")))" else "" end),
    "",
    "기준별: " + ([.criteria | to_entries[]
                  | "\(.key | criterion_name) \(if .value.met then "충족" else "미충족" end)(기대 \(.value.expectation))"] | join(" · "))
      + (if (.mismatchedCriteria | length) > 0
         then ". 기대와 다른 기준: \(.mismatchedCriteria | map(criterion_name) | join(", "))" else "" end),
    "",
    "## 실행",
    "",
    "| 항목 | 값 |",
    "|---|---|",
    row(["시나리오", .scenario]),
    row(["커밋", .commit]),
    row(["실행 시각(UTC)", .startedAt]),
    row(["RPS", .rps]),
    row(["준비 시간", (.warmupSeconds | seconds)]),
    row(["측정 시간", (.durationSeconds | seconds)]),
    row(["측정 구간(UTC)", "\(.measure.startEpochMs | utc) ~ \(.measure.endEpochMs | utc)"]),
    row(["기대 결과", (.expectation | expected)]),
    row(["Grafana", .grafanaUrl]),
    row(["feed-service 지표 수집 비율", .feedScrapeRatio]),
    "",
    "## 피드",
    "",
    "| 항목 | 값 |",
    "|---|---|",
    row(["측정 요청 수", .requests]),
    row(["보내지 못한 요청 수", .dropped]),
    row(["p50", (.latencyMs.p50 | ms)]),
    row(["p95", (.latencyMs.p95 | ms)]),
    row(["p99", (.latencyMs.p99 | ms)]),
    row(["가용성", .availability]),
    row(["SLO 충족", .sloMet]),
    (if (.degradeRatio | type) == "object"
     then (.degradeRatio | to_entries[]
           | row([(if .key == "any" then "degrade 비율(하나라도)" else "degrade 비율 \(.key)" end), .value]))
     else row(["degrade 비율", .degradeRatio]) end),
    "",
    "## 의존성",
    "",
    "| 의존성 | 호출 p99 | 호출 실패율 | 요청 배율 | 서킷 상태 |",
    "|---|---|---|---|---|",
    (.circuitState as $circuits
      | .dependencies | to_entries[]
      | row([.key, (.value.p99Ms | ms), .value.failureRate, .value.requestRatio, ($circuits[.key]? // null)])),
    "",
    "서킷 상태는 측정 구간을 5초 간격으로 본 표본에서 바뀔 때만 이어 적었다. 5초보다 짧게 머문 상태는 빠질 수 있고, 정확한 전이는 `circuit_state_changed` 로그로 본다.",
    "",
    "## 자원 사용량 (측정 구간)",
    "",
    "| 항목 | 최댓값 | 평균 |",
    "|---|---|---|",
    row(["Tomcat 사용 중 스레드", .resources.tomcatBusyThreads.max, .resources.tomcatBusyThreads.avg]),
    row(["feed-service CPU 사용률", .resources.feedCpuUsage.max, .resources.feedCpuUsage.avg]),
    (.resources.bulkheads | to_entries[]
      | row(["벌크헤드 \(.key) 사용 중 스레드", .value.activeThreads.max, .value.activeThreads.avg]),
        row(["벌크헤드 \(.key) 대기열", .value.queueDepth.max, .value.queueDepth.avg])),
    (.resources.connectionPools | to_entries[]
      | row(["커넥션 풀 \(.key) 사용 중", .value.leased.max, .value.leased.avg]),
        row(["커넥션 풀 \(.key) 대기", .value.pending.max, .value.pending.avg])),
    (if .feedScrapeRatio != null and .feedScrapeRatio < 1
     then ["", "측정 구간 중 Prometheus가 feed-service 지표를 긁지 못한 때가 있다(수집 비율 \(.feedScrapeRatio)). 위 값은 긁힌 구간만의 값이다."][]
     else empty end),
    (if .prometheusFailed then ["", "Prometheus 질의 중 실패한 것이 있어 값 없음인 항목이 있다(표준 오류 출력 참고)."][] else empty end),
    "",
    "## 장애 시각 (측정 시작부터 잰 초)",
    "",
    (if (.faults | length) == 0 then "넣은 장애 없음"
     else ("| 시간표 | 동작 | 대상 | 넣은 시각 | 거둔 시각 |", "|---|---|---|---|---|",
           (.faults[] | if .skipped then row([.at, .action, .target, "건너뜀", "건너뜀"])
                        else row([.at, .action, .target, .injectedAtSeconds, .removedAtSeconds]) end))
     end),
    "",
    "## 10초 구간",
    "",
    "| 구간(초) | 요청 수 | 보내지 못함 | p99 | 가용성 | SLO 충족 |",
    "|---|---|---|---|---|---|",
    (.windows[] | row(["\(.startSeconds)~\(.endSeconds)", .requests, .dropped, (.p99Ms | ms), .availability, .sloMet])),
    (if .recovery == null then empty
     else ("", "## 복구 시간", "",
           "- 장애를 거둔 시각: \(.recovery.removedAtSeconds)초",
           "- 복구된 첫 구간의 시작: \(.recovery.recoveredWindowStartSeconds | seconds)",
           "- 복구 시간: \(.recovery.summary)")
     end)
  ] | .[]
' <<<"$report" >"$folder/report.md" || fail "report.md를 쓰지 못했다: $folder/report.md"

# ---------- 7. 끝 ----------

echo "$scenario: $(jq -r '.verdict' <<<"$verdict")"
echo "리포트: ${folder#"$root"/}/report.md"
exit "$verdict_status"
