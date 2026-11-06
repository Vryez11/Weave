package com.weave.e2e

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeBetween
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * `fault-experiments` 스펙의 "복구 시간", "복구하지 못함" 시나리오 (F-007).
 *
 * 미리 만든 k6 원본 파일로 10초 구간 계산 스크립트만 돌린다. Docker와 Compose 환경은 쓰지 않는다(design 결정 7, 13).
 *
 * **명령:** `experiments/lib/windows.sh <k6 원본 파일> <측정 시간(초)>`
 * - k6 원본 파일: `k6 run --out json`의 결과를 gzip한 파일(run.sh가 끝나면 gzip한다). 한 줄에 JSON 하나다.
 *   `"type":"Point"`인 줄 중 피드 요청의 `feed_latency`(k6/feed.js가 요청 앞뒤 경과 시간으로 남긴다. `status` 태그가 있다),
 *   장애 요청(`kind: fault`)의 `http_req_duration`, `dropped_iterations`만 읽고 나머지 줄은 건너뛴다.
 *   피드 요청의 `http_req_duration`은 읽지 않는다. 시간 초과된 요청이 0으로 남을 수 있다(add-fault-experiments design 결정 5).
 *   픽스처에는 k6처럼 두 점이 다 있고, 시간 초과된 요청(`status` 0)의 `http_req_duration`은 0이다.
 *   점의 `data.time`은 RFC 3339(UTC, 소수 자릿수가 줄마다 다르고 없을 수도 있다), `data.value`는 밀리초다.
 * - 보낸 시각: 요청은 `data.time - data.value`(k6는 요청이 끝난 시각에 점을 찍는다), 보내지 못한 요청은 `data.time`
 * - 측정 시작: `scenario`가 `measure`인 요청 중 가장 이른 보낸 시각. 시각은 모두 이것부터 잰 초로 적는다
 * - SLO는 `experiments/slo.json`(`{"availability": 0.999, "p99Ms": 300}`, PRD 2장)에서 읽는다.
 *
 * **출력:** 표준 출력에 JSON 하나, 종료 코드 0. 원본 파일이 없거나 측정 요청이 없으면 2로 끝나고 표준 출력에 쓰지 않는다.
 * ```
 * {
 *   "measureStartEpochMs": 1793926810000,
 *   "windows": [
 *     { "startSeconds": 0, "endSeconds": 10, "requests": 10, "dropped": 0, "p99Ms": 5001.4, "availability": 0, "sloMet": false },
 *     ...
 *   ],
 *   "faults": [ { "atSeconds": 0.006, "action": "timeout", "target": "recommendation", "status": "200" }, ... ],
 *   "recovery": { "removedAtSeconds": 120.004, "recoveredWindowStartSeconds": 140, "recoverySeconds": 20, "summary": "20초" }
 * }
 * ```
 * - `windows`: 측정 시간을 10초씩 나눈 구간(`측정 시간 / 10`을 올림한 개수). 요청과 보내지 못한 요청은 보낸 시각으로 구간에 넣는다.
 *   `scenario`가 `measure`인 것만 센다(준비 구간, 장애 요청, `setup()`·`teardown()` 요청은 빠진다).
 *   - `requests`: 보낸 요청 수, `dropped`: 보내지 못한 요청 수
 *   - `p99Ms`: 구간 요청 지연(실패 포함)의 99번째 백분위. 요청이 없으면 `null`
 *   - `availability`: `status`가 `200`인 요청 수 ÷ (`requests` + `dropped`). 분모가 0이면 `null`
 *   - `sloMet`: `p99Ms` ≤ SLO p99이고 `availability` ≥ SLO 가용성. 어느 하나가 `null`이면 `false`
 * - `faults`: `kind`가 `fault`인 요청마다 보낸 시각(`atSeconds`, 측정 시작 전이면 음수)과 태그 `action`, `target`, `status`. 시각 순서다.
 *   `setup()`·`teardown()`의 되돌리기는 `action`이 `reset`이다.
 * - `recovery`: `action`이 `clear`인 장애 요청이 없으면 `null`. 있으면 마지막 `clear`를 거둔 시각으로 보고,
 *   끝이 그 시각보다 뒤인 구간 중 SLO를 지키기 시작해 마지막 구간까지 이어진 첫 구간을 찾는다.
 *   - `removedAtSeconds`: 거둔 시각, `recoveredWindowStartSeconds`: 그 구간의 시작(없으면 `null`)
 *   - `recoverySeconds`: `max(0, 구간 시작 - 거둔 시각)`을 반올림한 정수 초(없으면 `null`)
 *   - `summary`: 리포트에 싣는 문구. `"<recoverySeconds>초"` 또는 `"측정 구간 안에 복구하지 못함"`
 *
 * **픽스처** (`raw-S-08-*.json`): S-08을 1 RPS, 준비 10초(2026-11-06T01:00:00Z부터), 측정 300초로 돌린 모양이다.
 * 측정 요청은 구간마다 10개이고, 첫 요청을 01:00:10Z에 보냈다. 장애 요청은 0.006초에 `timeout`, 120.004초에 `clear`다.
 * - `recovered`: 0~119초에 보낸 요청은 5초 시간 초과(`status` 0), 120~139초는 200이지만 700ms 이상,
 *   140초부터는 200·60ms 미만. 53.5초에 보내지 못한 요청이 하나 있다
 * - `not-recovered`: 120~149초가 느린 200, 150~289초가 정상, 290초부터는 빠른 500이다
 */
@Tags("Integration")
class ExperimentWindowsScenarioTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val measureStartEpochMs = 1_793_926_810_000L

        fun windows(fixture: String): Map<String, Any?> {
            val result = ExperimentScripts.run("lib/windows.sh", ExperimentScripts.gzippedFixture(fixture).toString(), "300")
            return withClue(result) {
                result.exitCode shouldBe 0
                mapper.readValue<Map<String, Any?>>(result.stdout)
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun Map<String, Any?>.list(key: String) = this[key] as List<Map<String, Any?>>

        @Suppress("UNCHECKED_CAST")
        fun Map<String, Any?>.obj(key: String) = this[key] as Map<String, Any?>?

        fun Any?.asDouble() = (this as Number).toDouble()

        test("F-007 복구 시간: 120초에 거둔 S-08이 140초 구간부터 끝까지 SLO를 지키면 복구 시간은 20초다") {
            val output = windows("raw-S-08-recovered.json")
            withClue(output) {
                (output["measureStartEpochMs"] as Number).toLong().shouldBeBetween(measureStartEpochMs - 1, measureStartEpochMs + 1)

                val windows = output.list("windows")
                windows shouldHaveSize 30
                windows.map { it["startSeconds"] } shouldContainExactly (0 until 300 step 10).toList()
                windows.map { it["endSeconds"] } shouldContainExactly (10..300 step 10).toList()
                // 준비 구간, 장애 요청, setup()·teardown() 요청은 세지 않는다
                windows.map { it["requests"] } shouldContainExactly List(30) { 10 }
                windows.map { it["dropped"] } shouldContainExactly List(30) { if (it == 5) 1 else 0 }
                windows[5]["availability"].asDouble() shouldBe (0.0 plusOrMinus 1e-9)
                windows[14]["availability"].asDouble() shouldBe (1.0 plusOrMinus 1e-9)
                // 0~130초 구간은 장애와 그 여파로 SLO를 못 지키고, 140초 구간부터 끝까지 지킨다
                windows.map { it["sloMet"] } shouldContainExactly List(30) { it >= 14 }

                val faults = output.list("faults").filter { it["action"] != "reset" }
                faults.map { "${it["action"]} ${it["target"]}" } shouldContainExactly
                    listOf("timeout recommendation", "clear recommendation")
                faults[0]["atSeconds"].asDouble() shouldBe (0.006 plusOrMinus 0.01)
                faults[1]["atSeconds"].asDouble() shouldBe (120.004 plusOrMinus 0.01)

                val recovery = output.obj("recovery").shouldNotBeNull()
                recovery["removedAtSeconds"].asDouble() shouldBe (120.004 plusOrMinus 0.01)
                recovery["recoveredWindowStartSeconds"] shouldBe 140
                recovery["recoverySeconds"] shouldBe 20
                recovery["summary"] shouldBe "20초"
            }
        }

        test("F-007 복구하지 못함: 거둔 뒤 마지막 10초 구간이 SLO를 못 지키면 복구 시간은 '측정 구간 안에 복구하지 못함'이다") {
            val output = windows("raw-S-08-not-recovered.json")
            withClue(output) {
                val windows = output.list("windows")
                windows shouldHaveSize 30
                // 150~280초 구간은 지켰지만 마지막 구간이 못 지켜 끝까지 이어지지 않는다
                windows.map { it["sloMet"] } shouldContainExactly List(30) { it in 15..28 }

                val recovery = output.obj("recovery").shouldNotBeNull()
                recovery["removedAtSeconds"].asDouble() shouldBe (120.004 plusOrMinus 0.01)
                recovery["recoveredWindowStartSeconds"].shouldBeNull()
                recovery["recoverySeconds"].shouldBeNull()
                recovery["summary"] shouldBe "측정 구간 안에 복구하지 못함"
            }
        }
    })
