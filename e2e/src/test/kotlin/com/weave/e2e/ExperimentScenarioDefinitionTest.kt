package com.weave.e2e

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * `fault-experiments` 스펙의 "시나리오마다의 장애"와 "기본 목록" 시나리오 (F-007).
 *
 * 실험 세트 파일을 읽거나 스크립트를 돌리기만 하고 Compose 환경은 띄우지 않는다(design 결정 13).
 *
 * **시나리오 정의 형식** (`experiments/scenarios/S-xx.json`, design 결정 9)
 * ```
 * {
 *   "id": "S-02",
 *   "title": "추천 서비스 3초 지연",
 *   "prd": "PRD 4장의 문장",
 *   "faults": [ { "at": 0, "action": "latency", "target": "recommendation", "latencyMs": 3000 } ]
 * }
 * ```
 * - `at`: 측정 구간 시작부터 잰 초. "내내"인 장애는 `at: 0`에 넣고 거두는 항목을 두지 않는다(측정이 끝나면 `teardown()`이 거둔다).
 * - `target`: 의존성 이름 `post`, `user`, `like`, `recommendation`, `ad`
 * - `action`과 값
 *   - `latency`: 프록시 응답 지연. `latencyMs`
 *   - `timeout`: 프록시 무응답(Toxiproxy `timeout` 톡식, `timeout: 0`)
 *   - `down`: 프록시 끄기(연결 거부)
 *   - `errorRate`: 앱 수준 에러율(`POST /actuator/faults`). `errorRate`
 *   - `quota`: ad 요청 한도(`POST /actuator/quota`). `windowSeconds`, `limitRatio`.
 *     한도 = 실험 RPS × `windowSeconds` × `limitRatio`(정수로 내림)
 *   - `clear`: 그 의존성에 넣은 장애를 모두 거둔다(프록시 톡식을 지우고 켬, 앱 장애 설정 기본값, ad 한도 끔)
 *
 * **기본 목록:** `experiments/run-all.sh --list`는 시나리오 ID 없이 돌릴 때의 목록을 한 줄에 하나씩 출력하고 0으로 끝난다.
 * 환경을 확인하거나 부하를 보내지 않는다.
 */
@Tags("Integration")
class ExperimentScenarioDefinitionTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val valueKeys = listOf("latencyMs", "errorRate", "windowSeconds", "limitRatio")

        fun number(value: Any?): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

        /** 장애 하나를 `"<at>s <action> <target> key=value..."`로 적는다. 숫자는 `1`과 `1.0`을 같게 본다. */
        fun describe(fault: Map<String, Any?>): String {
            val values = valueKeys.filter { it in fault }.map { "$it=${number(fault[it])}" }
            return (listOf("${number(fault["at"])}s", "${fault["action"]}", "${fault["target"]}") + values).joinToString(" ")
        }

        // 같은 시각의 항목은 순서를 보지 않는다
        val byTime = compareBy<String>({ BigDecimal(it.substringBefore("s ")) }, { it })

        val expectedFaults =
            mapOf(
                "S-01" to emptyList(),
                "S-02" to listOf("0s latency recommendation latencyMs=3000"),
                "S-03" to listOf("0s timeout recommendation"),
                "S-04" to listOf("0s down user"),
                "S-05" to listOf("0s errorRate like errorRate=0.5"),
                "S-06" to listOf("0s down post"),
                "S-07" to listOf("0s quota ad windowSeconds=10 limitRatio=0.5"),
                "S-08" to listOf("0s timeout recommendation", "120s clear recommendation"),
                "S-09" to
                    listOf(60, 150, 240).flatMap { at ->
                        listOf(
                            "${at}s errorRate like errorRate=1",
                            "${at}s errorRate user errorRate=1",
                            "${at + 10}s clear like",
                            "${at + 10}s clear user",
                        )
                    },
            )

        test("F-007 시나리오마다의 장애: S-01~S-09만 있고 시나리오마다 PRD 4장의 장애를 정해진 시각에 넣고 거둔다") {
            val scenarioDir = ExperimentScripts.dir.resolve("scenarios")
            withClue("$scenarioDir 폴더가 있어야 한다") { scenarioDir.isDirectory() shouldBe true }
            val files = scenarioDir.listDirectoryEntries("S-*.json").sortedBy { it.name }
            withClue("$scenarioDir 의 시나리오 파일 (S-10, S-11은 F-003에서 만든다)") {
                files.map { it.name } shouldContainExactly expectedFaults.keys.map { "$it.json" }
            }

            files.forEach { file ->
                val scenario = mapper.readValue<Map<String, Any?>>(file.readText())
                val id = file.name.removeSuffix(".json")
                withClue("${file.name} 의 id") { scenario["id"] shouldBe id }

                @Suppress("UNCHECKED_CAST")
                val faults = scenario["faults"] as List<Map<String, Any?>>
                withClue("${file.name} 의 장애 시간표") {
                    faults.map(::describe).sortedWith(byTime) shouldContainExactly expectedFaults.getValue(id).sortedWith(byTime)
                }
            }
        }

        test("F-007 기본 목록: 시나리오 ID 없이 돌릴 목록은 S-01부터 S-09까지 아홉 개다") {
            val result = ExperimentScripts.run("run-all.sh", "--list")
            withClue(result) {
                result.exitCode shouldBe 0
                result.stdout.lines().filter { it.isNotBlank() } shouldContainExactly (1..9).map { "S-0$it" }
            }
        }
    })
