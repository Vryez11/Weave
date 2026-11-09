#!/usr/bin/env bash
# 좋아요 정합성 시나리오를 돌린다 (F-003, F-005, add-idempotent-like design 결정 10).
#
#   experiments/run-like.sh S-10
#   experiments/run-like.sh S-11 [--strategy read-modify-write|atomic|sharded] [--rps N] [--warmup 30s] [--duration 2m] [--shards 4,8,16]
#
# - S-10은 같은 요청의 중복·동시·순서 뒤바뀜과 타임아웃 뒤 재전송을 돌려 경우마다 최종 상태를 본다.
# - S-11은 갱신 방식마다 like-service를 다시 띄워 같은 몰림 부하를 주고 처리량, p99, 잠금 대기, 수 불일치를 비교한다.
# - 옵션 기본값은 like-config.json이다. 시간은 h·m·s를 이어 쓴다(예: 20s, 5m).
# - 결과는 results/<UTC yyyyMMdd-HHmmss>-<S-10|S-11>/에 쓴다. report.json 형식은 e2e ExperimentLikeRunComposeScenarioTest KDoc이 정한다.
# - 종료 코드: 0 기대와 같음(S-11은 정합성 유지), 1 다름, 2 실행하지 못함(잘못된 인자, 환경 없음, 되돌리기 실패, k6 실패, 중단).
# - 어떻게 끝나도 장애를 모두 되돌리고, S-11은 like-service를 설정 덮어쓰기 없이 다시 띄운다.
# - Compose 프로젝트와 env 파일은 COMPOSE_PROJECT_NAME, COMPOSE_ENV_FILE로 받는다. env 파일은 읽지 않고 경로만 넘긴다.
# - 표준 출력에는 마지막 판정과 리포트 경로만 쓴다. macOS 기본 bash 3.2에서 돈다.
set -u
set -o pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"

REQUIRED_SERVICES="feed-service toxiproxy post-service user-service like-service recommendation-service ad-service mysql"
# Prometheus 수집 간격 5초의 두 배. 측정 구간 끝까지 긁힌 뒤에 질의한다
SCRAPE_MARGIN_SECONDS=10
LIKE_PROM='instance="like-service:8083"'
LIKE_RECREATE_TIMEOUT_SECONDS=180

log() { echo "run-like.sh: $*" >&2; }
fail() {
  log "$*"
  exit 2
}

usage() {
  cat >&2 <<'EOF'
사용법: experiments/run-like.sh S-10
        experiments/run-like.sh S-11 [--strategy read-modify-write|atomic|sharded] [--rps N] [--warmup 30s] [--duration 2m] [--shards 4,8,16]
  옵션 기본값은 experiments/like-config.json. 환경 변수 COMPOSE_PROJECT_NAME, COMPOSE_ENV_FILE로 Compose 프로젝트와 env 파일을 바꾼다.
EOF
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

# ---------- 인자 ----------

case "${1:-}" in
  -h | --help)
    usage
    exit 0
    ;;
  S-10 | S-11) scenario="$1" ;;
  '')
    usage
    exit 2
    ;;
  *)
    log "없는 시나리오다: $1"
    echo "있는 시나리오: S-10 S-11" >&2
    exit 2
    ;;
esac
shift

command -v jq >/dev/null 2>&1 || fail "jq가 필요하다"
command -v docker >/dev/null 2>&1 || fail "docker가 필요하다"

strategy_option=""
rps=""
warmup=""
duration=""
shards=""
while [ $# -gt 0 ]; do
  [ "$scenario" = S-11 ] || fail "S-10에는 옵션이 없다: $1"
  case "$1" in
    --strategy | --rps | --warmup | --duration | --shards)
      [ $# -ge 2 ] || fail "$1 에 값이 없다"
      case "$1" in
        --strategy) strategy_option="$2" ;;
        --rps) rps="$2" ;;
        --warmup) warmup="$2" ;;
        --duration) duration="$2" ;;
        --shards) shards="$2" ;;
      esac
      shift 2
      ;;
    *)
      usage
      fail "알 수 없는 인자: $1"
      ;;
  esac
done

config="$here/like-config.json"
[ -n "$rps" ] || rps="$(jq -r '.rps' "$config")" || fail "like-config.json을 읽지 못했다: $config"
[ -n "$warmup" ] || warmup="$(jq -r '.warmup' "$config")" || fail "like-config.json을 읽지 못했다: $config"
[ -n "$duration" ] || duration="$(jq -r '.duration' "$config")" || fail "like-config.json을 읽지 못했다: $config"
[ -n "$shards" ] || shards="$(jq -r '.shards | join(",")' "$config")" || fail "like-config.json을 읽지 못했다: $config"
timeout_seconds="$(to_seconds "$(jq -r '.requestTimeout' "$config")")" || fail "like-config.json의 requestTimeout이 시간이 아니다"

case "$rps" in
  '' | *[!0-9]* | 0*) fail "--rps는 1 이상의 정수여야 한다: $rps" ;;
esac
to_seconds "$warmup" >/dev/null || fail "--warmup은 h·m·s 시간이어야 한다: $warmup"
duration_seconds="$(to_seconds "$duration")" || fail "--duration은 h·m·s 시간이어야 한다: $duration"
[ "$duration_seconds" -gt 0 ] || fail "--duration은 0보다 길어야 한다: $duration"
case "$shards" in
  '' | *[!0-9,]*) fail "--shards는 쉼표로 이은 양의 정수여야 한다: $shards" ;;
esac
shard_list="${shards//,/ }"
for count in $shard_list; do
  [ "$count" -ge 1 ] || fail "--shards의 값은 1 이상이어야 한다: $count"
done

# 돌릴 방식. "<방식>:<분할 수>"를 공백으로 잇는다
plan=""
add_sharded() {
  local count
  for count in $shard_list; do plan="$plan sharded:$count"; done
}
case "$strategy_option" in
  '')
    plan="read-modify-write:1 atomic:1"
    add_sharded
    ;;
  read-modify-write | atomic) plan="$strategy_option:1" ;;
  sharded) add_sharded ;;
  *) fail "--strategy는 read-modify-write, atomic, sharded 중 하나여야 한다: $strategy_option" ;;
esac

# ---------- 환경 ----------

env_file="${COMPOSE_ENV_FILE:-$root/.env}"
cd "$root" || fail "레포 루트로 옮기지 못했다: $root"

compose() { docker compose -f "$root/compose.yaml" --env-file "$env_file" "$@"; }

up_hint() {
  local project=""
  [ -n "${COMPOSE_PROJECT_NAME:-}" ] && project=" -p $COMPOSE_PROJECT_NAME"
  echo "환경을 먼저 띄운다(레포 루트에서): ./gradlew bootJar && docker compose$project --env-file $env_file up -d --build --wait" >&2
}

health_of() {
  jq -rs --arg service "$1" 'flatten | map(select(.Service == $service)) | .[0].Health // "없음" | if . == "" then "헬스 체크 없음" else . end' <<<"$2"
}

if [ ! -f "$env_file" ]; then
  log "env 파일이 없다: $env_file (항목은 .env.example)"
  up_hint
  exit 2
fi
states="$(compose ps --format json)" || {
  log "docker compose ps가 실패했다"
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

# ---------- trap ----------

k6_pid=""
k6_container=""
k6_runs=0
like_overridden=0
cpu_pids=""

reset_faults() {
  log "장애를 되돌린다(Toxiproxy, 하위 서비스 5개, ad 요청 한도)"
  compose run --rm -T k6 run --quiet --summary-mode=disabled k6/reset.js >&2
}

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

stop_cpu_sampling() {
  local pid
  for pid in $cpu_pids; do
    kill "$pid" 2>/dev/null
    wait "$pid" 2>/dev/null
  done
  cpu_pids=""
}

# 갱신 방식을 덮어쓴 like-service를 기본 설정으로 다시 띄운다
restore_like() {
  [ "$like_overridden" = 1 ] || return 0
  log "like-service를 기본 설정으로 다시 띄운다"
  LIKE_SPRING_APPLICATION_JSON='{}' compose up -d --force-recreate --no-deps --wait \
    --wait-timeout "$LIKE_RECREATE_TIMEOUT_SECONDS" like-service >&2 || return 1
  like_overridden=0
}

cleanup() {
  local status=$?
  trap '' INT TERM
  stop_k6
  stop_cpu_sampling
  if ! restore_like; then
    log "like-service를 되돌리지 못했다. docker compose logs like-service를 본다"
    status=2
  fi
  if ! reset_faults; then
    log "장애를 되돌리지 못했다. Toxiproxy(127.0.0.1:8474)와 하위 서비스 /actuator/faults를 확인한다"
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

reset_faults || fail "장애를 되돌리지 못했다"

# ---------- 결과 폴더 ----------

stamp="$(date -u '+%Y%m%d-%H%M%S|%Y-%m-%dT%H:%M:%SZ')"
folder_name="${stamp%%|*}-$scenario"
started_at="${stamp#*|}"
folder="$here/results/$folder_name"
mkdir -p "$here/results" || fail "결과 폴더를 만들지 못했다: $here/results"
mkdir "$folder" || fail "결과 폴더를 만들지 못했다(이미 있다?): $folder"

commit="$(git -C "$root" rev-parse --short HEAD 2>/dev/null)" || commit="unknown"
if [ -n "$(git -C "$root" status --porcelain 2>/dev/null)" ]; then
  commit="$commit-dirty"
fi

# run_k6 <스크립트> <k6 인자...>. 백그라운드로 돌려 wait가 신호에 바로 깨게 한다. docker를 바로 띄워 $!가 docker 프로세스가 되게 한다
run_k6() {
  local script="$1" status
  shift
  k6_runs=$((k6_runs + 1))
  k6_container="weave-k6-like-$$-$k6_runs"
  docker compose -f "$root/compose.yaml" --env-file "$env_file" \
    run --rm -T --name "$k6_container" --user "$(id -u):$(id -g)" \
    k6 run --quiet "$@" "$script" >&2 &
  k6_pid=$!
  wait "$k6_pid"
  status=$?
  k6_pid=""
  [ "$status" -eq 0 ] || fail "k6가 실패했다(종료 코드 $status). 위의 k6 출력을 본다"
}

relative_report="${folder#"$root"/}/report.md"

# ---------- S-10 ----------

run_s10() {
  local cases="$folder/cases.json" verdict status=0
  log "S-10: 중복, 동시, 순서 뒤바뀜, 타임아웃 뒤 재전송"
  run_k6 k6/like-s10.js -e "OUTPUT=results/$folder_name/cases.json"
  [ -f "$cases" ] || fail "k6 결과가 없다: $cases"

  if jq -e '.cases | length == 4 and all(.passed)' "$cases" >/dev/null; then
    verdict="모두 기대와 같음"
  else
    verdict="기대와 다름"
    status=1
  fi

  jq --arg commit "$commit" --arg startedAt "$started_at" --arg verdict "$verdict" \
    '{scenario: "S-10", commit: $commit, startedAt: $startedAt, verdict: $verdict, cases: .cases}' \
    "$cases" >"$folder/report.json" || fail "report.json을 쓰지 못했다"

  {
    echo "# S-10 좋아요 정합성"
    echo
    echo "- 커밋: $commit"
    echo "- 시작: $started_at"
    echo "- 판정: $verdict"
    echo
    echo "| 경우 | 통과 | 기대 | 실제 | 불일치 | 참고 |"
    echo "|---|---|---|---|---|---|"
    jq -r '.cases[] | "| \(.name) | \(.passed) | \(.expected | tojson) | \(.actual | tojson) | \(.mismatches) | \(if .name == "timeout-resend" then "첫 응답 \(.firstStatus), 재전송 전 반영 \(.appliedBeforeResend), 재전송 응답 \(.resendStatus)" else "응답 \(.statuses | tojson)" end) |"' "$cases"
  } >"$folder/report.md" || fail "report.md를 쓰지 못했다"

  echo "S-10: $verdict"
  echo "리포트: $relative_report"
  return "$status"
}

# ---------- S-11 ----------

# MySQL 전역 카운터: "<행 잠금 대기 횟수> <행 잠금 대기 시간 ms> <교착 수>". 계정은 컨테이너 환경에서 쓴다
lock_stats() {
  # shellcheck disable=SC2016
  compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B -e "SELECT (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = '"'"'Innodb_row_lock_waits'"'"'), (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = '"'"'Innodb_row_lock_time'"'"'), (SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME = '"'"'lock_deadlocks'"'"')"'
}

# like-service 안에서 업무 API를 부른다. 호스트에 포트가 없다
like_get() { compose exec -T like-service curl -fsS -H 'X-User-Id: 1' "http://localhost:8083$1"; }

sample_cpu() {
  local container="$1" out="$2"
  while :; do
    docker stats --no-stream --format '{{.CPUPerc}}' "$container" >>"$out" 2>/dev/null
    sleep 1
  done
}

# 잰 CPU 사용률의 최댓값(코어 수). 없으면 null
max_cores() {
  jq -Rsc 'split("\n") | map(select(length > 0) | rtrimstr("%") | tonumber) | if length == 0 then null else (max / 100) end' "$1"
}

# 측정 구간의 HikariCP 대기 최댓값. 질의하지 못하면 null
hikari_pending_max() {
  local query body
  query="max_over_time(hikaricp_connections_pending{$LIKE_PROM}[$((duration_seconds + SCRAPE_MARGIN_SECONDS))s])"
  if body="$(compose exec -T prometheus wget -qO- "http://localhost:9090/api/v1/query?query=$(jq -rn --arg q "$query" '$q | @uri')" 2>/dev/null)"; then
    jq -c '.data.result[0].value[1] // null | if . == null then null else tonumber end' <<<"$body"
  else
    echo null
  fi
}

# 방식 하나를 돌려 결과 한 줄(JSON)을 runs.jsonl에 덧붙인다
run_strategy() {
  local strategy="$1" count="$2" json dir name base warm_post hot_post
  local waits_before time_before dead_before waits_after time_after dead_after
  name="$strategy-$count"
  dir="$folder/$name"
  mkdir "$dir" || fail "결과 폴더를 만들지 못했다: $dir"

  if [ "$strategy" = sharded ]; then
    json="{\"weave.like.counter.strategy\":\"$strategy\",\"weave.like.counter.shards\":$count}"
  else
    json="{\"weave.like.counter.strategy\":\"$strategy\"}"
  fi
  log "$name: like-service를 다시 띄운다"
  like_overridden=1
  LIKE_SPRING_APPLICATION_JSON="$json" compose up -d --force-recreate --no-deps --wait \
    --wait-timeout "$LIKE_RECREATE_TIMEOUT_SECONDS" like-service >&2 || fail "like-service를 다시 띄우지 못했다: $json"

  # 실행마다 새 게시글을 쓴다. 앞 실행의 행과 수가 섞이지 않는다
  base=$(($(date +%s) * 1000))
  warm_post="$base"
  hot_post=$((base + 1))

  log "$name: 준비 $warmup"
  run_k6 k6/like-s11.js -e "POST_ID=$warm_post" -e "RPS=$rps" -e "DURATION=$warmup" -e "TIMEOUT_SECONDS=$timeout_seconds"

  read -r waits_before time_before dead_before <<<"$(lock_stats)" || fail "MySQL 잠금 카운터를 읽지 못했다"
  [ -n "${dead_before:-}" ] || fail "MySQL 잠금 카운터를 읽지 못했다"

  sample_cpu "$(compose ps -q like-service)" "$dir/cpu-like.txt" &
  cpu_pids="$!"
  sample_cpu "$(compose ps -q mysql)" "$dir/cpu-mysql.txt" &
  cpu_pids="$cpu_pids $!"

  log "$name: 측정 $rps RPS, $duration"
  run_k6 k6/like-s11.js -e "POST_ID=$hot_post" -e "RPS=$rps" -e "DURATION=$duration" -e "TIMEOUT_SECONDS=$timeout_seconds" \
    --summary-export "results/$folder_name/$name/summary.json"

  stop_cpu_sampling
  read -r waits_after time_after dead_after <<<"$(lock_stats)" || fail "MySQL 잠금 카운터를 읽지 못했다"
  [ -n "${dead_after:-}" ] || fail "MySQL 잠금 카운터를 읽지 못했다"
  [ -f "$dir/summary.json" ] || fail "k6 요약이 없다: $dir/summary.json"

  local like_body consistency like_count liked_rows hikari
  like_body="$(like_get "/likes?postIds=$hot_post")" || fail "좋아요 수를 읽지 못했다"
  consistency="$(like_get "/actuator/likeconsistency?postId=$hot_post")" || fail "불일치 검사를 읽지 못했다"
  like_count="$(jq -r '.likes[0].likeCount' <<<"$like_body")"
  liked_rows="$(jq -r --argjson count "$like_count" '.mismatches[0].likedRows // $count' <<<"$consistency")"

  log "$name: Prometheus가 측정 구간 끝까지 긁기를 ${SCRAPE_MARGIN_SECONDS}초 기다린다"
  sleep "$SCRAPE_MARGIN_SECONDS"
  hikari="$(hikari_pending_max)"

  jq -c \
    --arg strategy "$strategy" \
    --argjson shards "$count" \
    --argjson seconds "$duration_seconds" \
    --argjson waits "$((waits_after - waits_before))" \
    --argjson lockMs "$((time_after - time_before))" \
    --argjson deadlocks "$((dead_after - dead_before))" \
    --argjson likeCount "$like_count" \
    --argjson likedRows "$liked_rows" \
    --argjson hikari "$hikari" \
    --argjson likeCpu "$(max_cores "$dir/cpu-like.txt")" \
    --argjson mysqlCpu "$(max_cores "$dir/cpu-mysql.txt")" \
    '.metrics as $m | {
      strategy: $strategy, shards: $shards,
      requests: ($m.http_reqs.count // 0),
      failed: ($m.like_failed.count // 0),
      dropped: ($m.dropped_iterations.count // 0),
      throughputRps: ((($m.http_reqs.count // 0) - ($m.like_failed.count // 0)) / $seconds),
      p50Ms: $m.like_latency["p(50)"],
      p99Ms: $m.like_latency["p(99)"],
      rowLockWaits: $waits, rowLockTimeMs: $lockMs, deadlocks: $deadlocks,
      likeCount: $likeCount, likedRows: $likedRows,
      mismatch: (if $likeCount > $likedRows then $likeCount - $likedRows else $likedRows - $likeCount end),
      hikariPendingMax: $hikari, likeCpuMax: $likeCpu, mysqlCpuMax: $mysqlCpu
    }' "$dir/summary.json" >>"$folder/runs.jsonl" || fail "$name 결과를 모으지 못했다"
}

run_s11() {
  local item verdict_output verdict status=0
  : >"$folder/runs.jsonl"
  for item in $plan; do
    run_strategy "${item%%:*}" "${item##*:}"
  done

  jq -s '{runs: .}' "$folder/runs.jsonl" >"$folder/runs.json" || fail "방식별 결과를 모으지 못했다"
  verdict_output="$("$here/lib/like-verdict.sh" "$folder/runs.json")"
  status=$?
  [ "$status" -le 1 ] || fail "판정하지 못했다(lib/like-verdict.sh)"
  verdict="$(jq -r '.verdict' <<<"$verdict_output")"

  jq -n \
    --arg commit "$commit" --arg startedAt "$started_at" --argjson rps "$rps" --arg warmup "$warmup" --arg duration "$duration" \
    --argjson verdict "$verdict_output" --slurpfile runs "$folder/runs.json" \
    '{scenario: "S-11", commit: $commit, startedAt: $startedAt, rps: $rps, warmup: $warmup, duration: $duration}
      + $verdict + {runs: $runs[0].runs}' >"$folder/report.json" || fail "report.json을 쓰지 못했다"

  {
    echo "# S-11 인기 글 몰림"
    echo
    echo "- 커밋: $commit"
    echo "- 시작: $started_at"
    echo "- 부하: ${rps} RPS, 준비 $warmup, 측정 $duration"
    echo "- 판정: $verdict"
    jq -r 'if (.notReproduced | length) > 0 then "- 재현되지 않음: \(.notReproduced | join(", "))" else empty end' "$folder/report.json"
    jq -r '.brokenRuns[] | "- 깨진 방식: \(.strategy) (분할 \(.shards)): \(.reasons | join(", "))"' "$folder/report.json"
    echo
    echo "| 방식 | 분할 | 요청 | 실패 | 못 보냄 | 처리량(RPS) | p50(ms) | p99(ms) | 행 잠금 대기 | 대기 시간(ms) | 교착 | 수 | 행 | 불일치 | Hikari 대기 최대 | like CPU 최대 | MySQL CPU 최대 |"
    echo "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
    jq -r '.runs[] | "| \(.strategy) | \(.shards) | \(.requests) | \(.failed) | \(.dropped) | \(.throughputRps) | \(.p50Ms) | \(.p99Ms) | \(.rowLockWaits) | \(.rowLockTimeMs) | \(.deadlocks) | \(.likeCount) | \(.likedRows) | \(.mismatch) | \(.hikariPendingMax) | \(.likeCpuMax) | \(.mysqlCpuMax) |"' "$folder/report.json"
  } >"$folder/report.md" || fail "report.md를 쓰지 못했다"

  echo "S-11: $verdict"
  echo "리포트: $relative_report"
  return "$status"
}

if [ "$scenario" = S-10 ]; then
  run_s10
else
  run_s11
fi
exit $?
