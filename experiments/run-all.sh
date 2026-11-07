#!/usr/bin/env bash
# 장애 실험 시나리오를 차례로 돌리고 판정 표를 출력한다 (F-007, design 결정 8).
#
#   experiments/run-all.sh [--rps N] [--warmup 1m] [--duration 5m] [S-xx ...]
#   experiments/run-all.sh --list
#
# - 시나리오 ID가 없으면 scenarios/의 시나리오(S-01~S-09)를 모두 돌린다. 시나리오마다 run.sh를 부르고 옵션은 그대로 넘긴다.
# - 기대와 다르거나 실행하지 못한 시나리오가 있어도 끝까지 돌린 뒤, 표준 출력에 판정 표(마크다운)를 쓴다.
#   형식은 e2e ExperimentRunAllComposeScenarioTest KDoc이 정한다. 표준 출력에는 표만 쓰고, 진행 상황과 run.sh 출력은 표준 오류로 간다.
# - --list는 돌릴 목록을 한 줄에 하나씩 출력하고 0으로 끝난다. 환경을 확인하거나 부하를 보내지 않는다.
# - 종료 코드: 0 모두 기대와 같음, 1 기대와 다른 시나리오가 있음, 2 실행하지 못한 시나리오가 있음(잘못된 인자, 중단 포함).
#   시나리오 ID 없이 돌려 0으로 끝나는 것이 "장애 실험 세트 통과"다.
# - 중단 신호(INT, TERM)를 받으면 돌던 run.sh에 TERM을 보내 장애를 되돌리게 하고, 거기까지의 표를 쓰고 2로 끝난다.
# - Compose 프로젝트와 env 파일은 run.sh와 같이 환경 변수 COMPOSE_PROJECT_NAME, COMPOSE_ENV_FILE로 받는다.
# - macOS 기본 bash 3.2에서 돈다. 필요한 것은 run.sh와 같다(bash, jq, gzip, docker compose).
set -u
set -o pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"

log() { echo "run-all.sh: $*" >&2; }
fail() {
  log "$*"
  exit 2
}

usage() {
  cat >&2 <<'EOF'
사용법: experiments/run-all.sh [--rps N] [--warmup 1m] [--duration 5m] [S-xx ...]
       experiments/run-all.sh --list
  시나리오 ID가 없으면 experiments/scenarios/의 시나리오를 모두 돌린다. 옵션은 시나리오마다 run.sh에 넘긴다.
  환경 변수 COMPOSE_PROJECT_NAME, COMPOSE_ENV_FILE로 Compose 프로젝트와 env 파일을 바꾼다.
EOF
}

list_scenarios() {
  local file
  for file in "$here"/scenarios/*.json; do
    [ -f "$file" ] && basename "$file" .json
  done
}

# ---------- 인자 ----------

list_only=0
options=()
ids=""
while [ $# -gt 0 ]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --list)
      list_only=1
      shift
      ;;
    --rps | --warmup | --duration)
      [ $# -ge 2 ] || fail "$1 에 값이 없다"
      options+=("$1" "$2")
      shift 2
      ;;
    -*)
      usage
      fail "알 수 없는 인자: $1"
      ;;
    *)
      case "$1" in
        *[!A-Za-z0-9_-]*) known=0 ;;
        *) known=1 ;;
      esac
      if [ "$known" = 0 ] || [ ! -f "$here/scenarios/$1.json" ]; then
        log "없는 시나리오다: $1"
        echo "있는 시나리오:" >&2
        list_scenarios >&2
        exit 2
      fi
      ids="$ids $1"
      shift
      ;;
  esac
done

[ -n "$ids" ] || ids="$(list_scenarios | tr '\n' ' ')"
# 공백으로 나눈 목록. ID에는 공백이 없다(위에서 확인한다)
set -- $ids
[ $# -gt 0 ] || fail "돌릴 시나리오가 없다: $here/scenarios/"

if [ "$list_only" = 1 ]; then
  for id in "$@"; do
    echo "$id"
  done
  exit 0
fi

command -v jq >/dev/null 2>&1 || fail "jq가 필요하다"

# ---------- 시나리오마다 run.sh ----------

total=$#
rows=""
worst=0
child=""
interrupted=0
out="$(mktemp "${TMPDIR:-/tmp}/weave-run-all.XXXXXX")" || fail "임시 파일을 만들지 못했다"
trap 'rm -f "$out"' EXIT

# 신호는 돌던 run.sh에 넘긴다. 비대화형 bash의 백그라운드 자식은 SIGINT를 무시하므로 TERM으로 보낸다
on_signal() {
  log "중단 신호를 받았다. 돌던 시나리오의 장애를 되돌린 뒤 멈춘다"
  interrupted=1
  [ -n "$child" ] && kill -TERM "$child" 2>/dev/null
}
trap on_signal INT TERM

# 판정 표의 기대 열. 기준별 기대(객체)는 "가용성 miss · p99 meet"으로 적는다
expectation_text='if type == "object" then "가용성 \(.availability) · p99 \(.p99)" else . end'

expectation_of() {
  jq -r --arg id "$1" ".scenarios[\$id] // \"-\" | $expectation_text" "$here/expectations.json" 2>/dev/null || echo "-"
}

add_row() { rows="$rows| $1 | $2 | $3 | $4 | $5 |"$'\n'; }

index=0
for id in "$@"; do
  index=$((index + 1))
  if [ "$interrupted" = 1 ]; then
    break
  fi
  log "[$index/$total] $id"

  # 백그라운드로 돌려 wait가 신호에 바로 깨게 한다. wait가 신호로 끝나면 run.sh가 되돌리기를 마칠 때까지 다시 기다린다
  "$here/run.sh" "$id" ${options[@]+"${options[@]}"} >"$out" &
  child=$!
  wait "$child"
  status=$?
  while kill -0 "$child" 2>/dev/null; do
    wait "$child"
    status=$?
  done
  child=""
  [ "$interrupted" = 1 ] && status=2

  # run.sh 표준 출력(판정, 리포트 경로)은 진행 상황으로 보여 주고, 리포트 줄에서 결과 폴더를 읽는다
  report=""
  while IFS= read -r line; do
    echo "  $line" >&2
    case "$line" in
      "리포트: "*/report.md)
        report="${line#리포트: }"
        report="${report%/report.md}"
        ;;
    esac
  done <"$out"

  fields=""
  if [ "$status" -le 1 ] && [ -n "$report" ] && [ -f "$root/$report/report.json" ]; then
    fields="$(jq -r "[(if .sloMet then \"충족\" else \"미충족\" end), (.expectation | $expectation_text),
                       (if .matchesExpectation then \"기대와 같음\" else \"기대와 다름\" end)] | join(\"|\")" \
      "$root/$report/report.json" 2>/dev/null)" || fields=""
  fi

  if [ -n "$fields" ]; then
    slo="${fields%%|*}"
    rest="${fields#*|}"
    add_row "$id" "$slo" "${rest%%|*}" "${rest#*|}" "$report"
    case "${rest#*|}" in
      "기대와 같음") ;;
      *) [ "$worst" -ge 1 ] || worst=1 ;;
    esac
  else
    log "$id 를 실행하지 못했다(run.sh 종료 코드 $status). 위의 run.sh 출력을 본다"
    add_row "$id" "-" "$(expectation_of "$id")" "실행하지 못함" "-"
    worst=2
  fi
done

if [ "$interrupted" = 1 ]; then
  worst=2
  log "중단되어 $total개 중 일부만 돌렸다"
fi

echo "| 시나리오 | SLO | 기대 | 판정 | 리포트 |"
echo "|---|---|---|---|---|"
printf '%s' "$rows"

case "$worst" in
  0) log "모든 시나리오가 기대와 같다" ;;
  1) log "기대와 다른 시나리오가 있다" ;;
  *) log "실행하지 못한 시나리오가 있다" ;;
esac
exit "$worst"
