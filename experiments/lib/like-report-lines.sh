#!/usr/bin/env bash
# S-11 report.md의 판정 줄을 쓴다. 인자와 출력 형식은 e2e ExperimentLikeVerdictScenarioTest KDoc이 기준이다.
set -euo pipefail

input=$1
jq -r 'if (.notReproduced | length) > 0 then "- 재현되지 않음: \(.notReproduced | join(", "))" else empty end' "$input"
jq -r '.brokenRuns[] | "- 깨진 방식: \(.strategy) (분할 \(.shards)): \(.reasons | join(", "))"' "$input"
