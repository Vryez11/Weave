package com.weave.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

/**
 * `fault-experiments` 스펙 중 `experiments/run.sh`의 시나리오 (F-007).
 * "짧게 돌린다", "없는 시나리오", "환경이 떠 있지 않다", "앞에 남은 장애", "정상 종료 뒤", "중간에 멈춘다",
 * "준비 구간은 세지 않는다", "모든 요청이 실패한다", "시간 초과된 요청", "리포트 항목", "부하 도구가 실패한다",
 * "Tomcat 스레드가 모두 묶여도 긁힌다"(feed-service의 실험 지표).
 *
 * `:e2e:composeTest`가 띄운 환경에서 `run.sh`를 프로세스로 돌린다(design 결정 13). 결과 폴더와 장애 상태를 보는 법은 [ExperimentRuns] KDoc.
 *
 * **명령:** `experiments/run.sh <S-xx> [--rps N] [--warmup 1m] [--duration 5m]` (design 결정 6)
 * - 종료 코드: 0 기대와 같음, 1 기대와 다름, 2 실행하지 못함(없는 시나리오, 환경 없음, 되돌리기 실패, k6가 요약을 남기지 못함)
 * - 있는 시나리오는 `experiments/scenarios/<ID>.json` 파일이 있는 것이다. 파일의 내용은 k6가 읽는다
 *   ("부하 도구가 실패한다"는 문법 오류가 있는 `S-97.json`을 잠시 둬서 k6를 실패시킨다. 테스트가 미리 장애를 넣어 두므로, run.sh가 되돌리기 전에 내용을 걸러 끝나면 남은 장애 때문에 실패한다)
 * - 없는 시나리오: 있는 시나리오 ID 목록을 출력하고(표준 출력이나 표준 오류) 2로 끝난다. 아무것도 바꾸지 않는다
 * - 환경 없음: 띄우는 명령(`docker compose ... up ...`)을 출력하고 2로 끝난다. k6를 돌리지 않고 결과 폴더도 만들지 않는다
 *
 * **리포트 `report.json`** (design 결정 7. `report.md`는 같은 값을 사람이 읽게 쓴다)
 * ```
 * {
 *   "scenario": "S-02",
 *   "commit": "2e9d935",                 // git rev-parse --short HEAD, 작업 트리가 더러우면 "-dirty"를 붙인다
 *   "startedAt": "2026-11-06T01:00:00Z", // 실행 시각(UTC, ISO-8601)
 *   "rps": 5, "warmupSeconds": 5, "durationSeconds": 20,
 *   "measure": { "startEpochMs": 1793926810000, "endEpochMs": 1793926830000 },
 *   "requests": 100,                     // measure의 http_reqs
 *   "dropped": 0,                        // measure의 dropped_iterations
 *   "latencyMs": { "p50": 3012.1, "p95": 3020.4, "p99": 3031.0 },  // measure의 feed_latency(요청 앞뒤 경과 시간). 실패한 요청 포함
 *   "availability": 0.0,                 // 200 수 ÷ (requests + dropped)
 *   "sloMet": false, "expectation": "miss", "matchesExpectation": true,
 *   "verdict": "SLO 미충족, 기대와 같음",  // lib/verdict.sh의 출력 그대로(ExperimentVerdictScenarioTest)
 *   "failedThresholds": ["feed_latency{scenario:measure}: p(99)<300"],
 *   "dependencies": {                    // 의존성 5개 모두
 *     "post": { "p99Ms": 48.2, "failureRate": 0.0, "requestRatio": 1.0 }, ...
 *   },
 *   "resources": {                       // 측정 구간의 최댓값·평균
 *     "tomcatBusyThreads": { "max": 16, "avg": 15.2 },
 *     "sharedPoolActiveThreads": { "max": .., "avg": .. },
 *     "sharedPoolQueuedTasks": { "max": .., "avg": .. },
 *     "feedCpuUsage": { "max": .., "avg": .. },
 *     "connectionPools": { "post": { "leased": { "max": .., "avg": .. }, "pending": { "max": .., "avg": .. } }, ... }
 *   },
 *   "degradeRatio": "없음",              // 이후 단계(F-002)가 값을 채운다
 *   "circuitState": "없음",              // 이후 단계(F-001)가 값을 채운다
 *   "grafanaUrl": "http://localhost:3000/d/weave-feed?from=1793926810000&to=1793926830000",
 *   "feedScrapeRatio": 1.0,              // 측정 구간 up{feed-service} 표본 중 1인 비율. 질의가 실패하면 null
 *   "faults": [                          // 시나리오 JSON의 장애 중 clear가 아닌 항목마다
 *     { "at": 0, "action": "latency", "target": "recommendation",
 *       "injectedAtSeconds": 0.012, "removedAtSeconds": 20.03, "skipped": false }
 *   ],
 *   "windows": [ ... ],                  // lib/windows.sh의 windows
 *   "recovery": null                     // lib/windows.sh의 recovery
 * }
 * ```
 * - `faults`의 시각은 측정 시작부터 잰 초(`lib/windows.sh`의 `faults`와 같은 기준)다. 거둔 시각은 같은 `target`의 다음 `clear`,
 *   없으면 `teardown()`의 되돌리기(`action: reset`)다. 측정 시간을 줄여 돌려 측정 구간 밖에 놓인 항목은 `skipped: true`이고
 *   두 시각이 `null`이다(`report.md`에는 "건너뜀").
 * - 측정 구간에 값이 없는 지표도 키는 둔다. 이 테스트의 짧은 실행에서는 모두 숫자다.
 */
@Tags("Compose")
class ExperimentRunComposeScenarioTest :
    FunSpec({
        val runTimeout = 5.minutes

        fun run(
            scenario: String,
            vararg options: String,
        ): Pair<ScriptResult, Path> {
            val before = ExperimentRuns.resultFolders()
            val result = ComposeEnvironment.experiment("run.sh", scenario, *options, timeout = runTimeout)
            val folder =
                withClue("run.sh $scenario ${options.joinToString(" ")}: $result") { ExperimentRuns.newResultFolder(before, scenario) }
            return result to folder
        }

        fun Any?.asDouble(): Double = withClue("숫자여야 한다: $this") { (this as Number).toDouble() }

        @Suppress("UNCHECKED_CAST")
        fun Any?.asMap(): Map<String, Any?> = withClue("객체여야 한다: $this") { (this as Map<String, Any?>?).shouldNotBeNull() }

        @Suppress("UNCHECKED_CAST")
        fun Any?.asList(): List<Any?> = withClue("배열이어야 한다: $this") { (this as List<Any?>?).shouldNotBeNull() }

        fun Map<String, Any?>.shouldHaveMaxAndAverage(name: String) {
            withClue("$name: $this") {
                this["max"].asDouble() shouldBeGreaterThanOrEqualTo 0.0
                this["avg"].asDouble() shouldBeGreaterThanOrEqualTo 0.0
                this["avg"].asDouble() shouldBeLessThanOrEqualTo this["max"].asDouble() + 1e-9
            }
        }

        // S-02 짧은 실행 하나를 "짧게 돌린다", "정상 종료 뒤", "리포트 항목"이 함께 본다. 이 순서로 바로 이어서 돈다
        val shortS02 by lazy { run("S-02", *ExperimentRuns.shortOptions()) }

        afterSpec { ExperimentRuns.resetQuietly() }

        test("F-007 짧게 돌린다: S-02를 5 RPS·준비 5초·측정 20초로 돌리면 results/ 아래 리포트가 생기고 측정 요청 수는 95~105, p50은 3,000ms 이상이다") {
            val (result, folder) = shortS02
            val report = ExperimentRuns.report(folder)
            withClue("$result\nreport.json: $report") {
                folder.resolve("report.md").exists() shouldBe true
                (report["requests"] as Number).toInt() shouldBeInRange 95..105
                report["latencyMs"].asMap()["p50"].asDouble() shouldBeGreaterThanOrEqualTo 3000.0
                // "기대대로 SLO를 못 지킨다"의 WHEN과 같은 명령이다. 판정 계산은 ExperimentVerdictScenarioTest가 본다
                report["verdict"] shouldBe "SLO 미충족, 기대와 같음"
                result.exitCode shouldBe 0
            }
        }

        test("F-007 정상 종료 뒤: S-02 짧은 실행이 끝나면 프록시 5개가 켜져 있고 장애가 없으며, 하위 서비스 장애 설정은 기본값이고 ad 한도는 꺼져 있다") {
            shortS02
            ExperimentRuns.shouldBeClean()
        }

        test("F-007 리포트 항목: S-02 짧은 실행의 리포트에 실행 정보, 지연·가용성·판정, 의존성별 값, 자원 사용량, 없음 항목, Grafana 링크, 장애 시각이 있다") {
            val (_, folder) = shortS02
            val report = ExperimentRuns.report(folder)
            withClue("report.json: $report") {
                report["scenario"] shouldBe "S-02"
                (report["commit"] as String) shouldMatch Regex("[0-9a-f]{7,40}(-dirty)?")
                Instant.parse(report["startedAt"] as String).shouldNotBeNull()
                report["rps"].asDouble() shouldBe 5.0
                report["warmupSeconds"].asDouble() shouldBe 5.0
                report["durationSeconds"].asDouble() shouldBe 20.0
                report["requests"].asDouble() shouldBeGreaterThan 0.0
                report["dropped"].asDouble() shouldBe 0.0
                val latency = report["latencyMs"].asMap()
                listOf("p50", "p95", "p99").forEach { latency[it].asDouble() shouldBeGreaterThan 0.0 }
                report["availability"].asDouble() shouldBeInRangeOf 0.0..1.0
                report["sloMet"] shouldBe false
                report["expectation"] shouldBe "miss"
                report["matchesExpectation"] shouldBe true

                val dependencies = report["dependencies"].asMap()
                ExperimentRuns.dependencies.keys.forEach { name ->
                    val dependency = dependencies[name].asMap()
                    withClue("의존성 $name: $dependency") {
                        dependency["p99Ms"].asDouble() shouldBeGreaterThanOrEqualTo 0.0
                        dependency["failureRate"].asDouble() shouldBeInRangeOf 0.0..1.0
                        dependency["requestRatio"].asDouble() shouldBeGreaterThan 0.0
                    }
                }

                val resources = report["resources"].asMap()
                listOf("tomcatBusyThreads", "sharedPoolActiveThreads", "sharedPoolQueuedTasks", "feedCpuUsage").forEach {
                    resources[it].asMap().shouldHaveMaxAndAverage(it)
                }
                val pools = resources["connectionPools"].asMap()
                ExperimentRuns.dependencies.keys.forEach { name ->
                    val pool = pools[name].asMap()
                    pool["leased"].asMap().shouldHaveMaxAndAverage("$name 커넥션 풀 사용 중")
                    pool["pending"].asMap().shouldHaveMaxAndAverage("$name 커넥션 풀 대기")
                }

                report["degradeRatio"] shouldBe "없음"
                report["circuitState"] shouldBe "없음"

                val measure = report["measure"].asMap()
                val start = (measure["startEpochMs"] as Number).toLong()
                val end = (measure["endEpochMs"] as Number).toLong()
                (end - start) shouldBeInRangeOf 19_000L..21_000L
                val grafanaUrl = report["grafanaUrl"] as String
                grafanaUrl shouldContain "/d/weave-feed"
                grafanaUrl shouldContain "from=$start"
                grafanaUrl shouldContain "to=$end"

                val faults = report["faults"].asList().map { it.asMap() }
                faults shouldHaveSize 1
                val fault = faults.single()
                fault["action"] shouldBe "latency"
                fault["target"] shouldBe "recommendation"
                fault["skipped"] shouldBe false
                val injected = fault["injectedAtSeconds"].asDouble()
                val removed = fault["removedAtSeconds"].asDouble()
                injected shouldBeInRangeOf -1.0..2.0
                removed shouldBeGreaterThanOrEqualTo 19.0

                report["windows"].asList() shouldHaveSize 2
                report["recovery"].shouldBeNull()
                report["feedScrapeRatio"].asDouble() shouldBeInRangeOf 0.0..1.0
            }
            withClue("report.md") {
                val markdown = folder.resolve("report.md").readText()
                markdown shouldContain "S-02"
                markdown shouldContain "SLO 미충족, 기대와 같음"
                markdown shouldContain "없음"
            }
        }

        test("F-007 없는 시나리오: run.sh S-99는 2로 끝나고 있는 시나리오 ID를 출력하며, 프록시와 하위 서비스의 장애 설정을 바꾸지 않는다") {
            try {
                ExperimentRuns.injectLeftoverFaults()
                val before = ExperimentRuns.faultState()
                val folders = ExperimentRuns.resultFolders()

                val result = ComposeEnvironment.experiment("run.sh", "S-99", timeout = 1.minutes)
                withClue(result) {
                    result.exitCode shouldBe 2
                    val output = result.stdout + result.stderr
                    ExperimentRuns.scenarioIds.forEach { output shouldContain it }
                    ExperimentRuns.resultFolders() shouldBe folders
                }
                ExperimentRuns.faultState() shouldBe before
            } finally {
                ExperimentRuns.resetQuietly()
            }
        }

        test("F-007 환경이 떠 있지 않다: 없는 Compose 프로젝트로 run.sh S-01을 돌리면 부하를 보내지 않고 2로 끝나며 환경을 띄우는 명령을 알려 준다") {
            val missingProject = "weave-no-such-env"
            try {
                val folders = ExperimentRuns.resultFolders()
                val result =
                    ComposeEnvironment.experiment(
                        "run.sh",
                        "S-01",
                        timeout = 2.minutes,
                        environment = mapOf("COMPOSE_PROJECT_NAME" to missingProject),
                    )
                withClue(result) {
                    result.exitCode shouldBe 2
                    val output = result.stdout + result.stderr
                    output shouldContain "docker compose"
                    output shouldContain "up"
                    ExperimentRuns.resultFolders() shouldBe folders
                }
                val leftovers = ExperimentRuns.dockerComposeProject(missingProject, "ps", "-a", "-q")
                withClue("프로젝트 $missingProject 에 컨테이너(k6 등)가 생기지 않아야 한다: $leftovers") {
                    leftovers.exitCode shouldBe 0
                    leftovers.stdout
                        .lines()
                        .filter { it.isNotBlank() }
                        .shouldBeEmpty()
                }
            } finally {
                ExperimentRuns.dockerComposeProject(missingProject, "down", "-v", "--remove-orphans")
            }
        }

        test("F-007 앞에 남은 장애: post 프록시 지연 1,000ms와 like 에러율 1을 넣어 둬도 S-01 짧은 실행의 가용성은 1이고 feed-service가 다시 떠 있다") {
            try {
                ExperimentRuns.injectLeftoverFaults()
                val startedBefore = ExperimentRuns.feedStartedAt()

                val (result, folder) = run("S-01", *ExperimentRuns.shortOptions())
                val report = ExperimentRuns.report(folder)
                withClue("$result\nreport.json: $report") {
                    report["availability"].asDouble() shouldBe (1.0 plusOrMinus 1e-9)
                }
                val startedAfter = ExperimentRuns.feedStartedAt()
                withClue("feed-service 시작 시각: 전 $startedBefore, 뒤 $startedAfter") { startedAfter shouldBeGreaterThan startedBefore }
            } finally {
                ExperimentRuns.resetQuietly()
            }
        }

        test("F-007 준비 구간은 세지 않는다: S-01을 준비 10초·측정 20초로 돌리면 측정 요청 수는 95~105다") {
            val (result, folder) = run("S-01", *ExperimentRuns.shortOptions(warmup = "10s"))
            val report = ExperimentRuns.report(folder)
            withClue("$result\nreport.json: $report") {
                (report["requests"] as Number).toInt() shouldBeInRange 95..105
            }
        }

        test("F-007 모든 요청이 실패한다: post 프록시가 꺼진 S-06 짧은 실행의 가용성은 0이고 지연 p50·p95·p99가 있다") {
            val (result, folder) = run("S-06", *ExperimentRuns.shortOptions())
            val report = ExperimentRuns.report(folder)
            withClue("$result\nreport.json: $report") {
                report["availability"].asDouble() shouldBe (0.0 plusOrMinus 1e-9)
                val latency = report["latencyMs"].asMap()
                listOf("p50", "p95", "p99").forEach { latency[it].asDouble() shouldBeGreaterThan 0.0 }
            }
            ExperimentRuns.shouldBeClean()
        }

        // S-03 60 RPS 실행 하나를 "시간 초과된 요청"과 "Tomcat 스레드가 모두 묶여도 긁힌다"가 함께 본다.
        // 동시 요청 60 × 5초 = 300개가 Tomcat 최대 스레드 200을 넘는다
        val saturatedS03 by lazy { run("S-03", "--rps", "60", "--warmup", "5s", "--duration", "20s") }

        test("F-007 시간 초과된 요청: recommendation이 응답하지 않는 S-03을 60 RPS로 돌리면 지연 p50은 4,500ms 이상이다") {
            val (result, folder) = saturatedS03
            val report = ExperimentRuns.report(folder)
            withClue("$result\nreport.json: $report") {
                report["latencyMs"].asMap()["p50"].asDouble() shouldBeGreaterThanOrEqualTo 4500.0
            }
        }

        test("F-007 Tomcat 스레드가 모두 묶여도 긁힌다: S-03 60 RPS 실행의 feed-service 지표 수집 비율은 1이고 Tomcat 사용 중 스레드 최댓값은 200이다") {
            val (result, folder) = saturatedS03
            val report = ExperimentRuns.report(folder)
            withClue("$result\nreport.json: $report") {
                report["feedScrapeRatio"].asDouble() shouldBe (1.0 plusOrMinus 1e-9)
                report["resources"].asMap()["tomcatBusyThreads"].asMap()["max"].asDouble() shouldBe 200.0
            }
            ExperimentRuns.shouldBeClean()
        }

        test("F-007 중간에 멈춘다: S-05를 측정 60초로 돌리다 측정 구간이 시작된 뒤 SIGTERM을 보내면, 끝난 뒤 프록시와 하위 서비스, ad 한도가 기본 상태다") {
            val before = ExperimentRuns.resultFolders()
            val started = ExperimentRuns.startRun("S-05", *ExperimentRuns.shortOptions(duration = "60s"))
            try {
                eventually(3.minutes) {
                    withClue("run.sh 가 아직 돌고 있어야 한다: $started") { started.process.isAlive shouldBe true }
                    val folder = ExperimentRuns.newResultFolder(before, "S-05")
                    withClue("${folder.resolve("raw.json")} 에 측정 구간 요청이 있어야 한다") {
                        ExperimentRuns.hasMeasureRequest(folder.resolve("raw.json")) shouldBe true
                    }
                }
                started.process.destroy()
                started.await(3.minutes)
            } finally {
                if (started.process.isAlive) started.process.destroyForcibly().waitFor()
            }
            ExperimentRuns.shouldBeClean()
        }

        test("F-007 부하 도구가 실패한다: 시나리오 JSON에 문법 오류가 있어 k6가 결과를 남기지 못하면 run.sh는 2로 끝나고 장애를 거둔 상태다") {
            val broken = ExperimentScripts.dir.resolve("scenarios/S-97.json")
            try {
                broken.writeText("""{"id": "S-97", "title": "문법 오류", "faults": [""")
                ExperimentRuns.injectLeftoverFaults()

                val result = ComposeEnvironment.experiment("run.sh", "S-97", *ExperimentRuns.shortOptions(), timeout = runTimeout)
                withClue(result) { result.exitCode shouldBe 2 }
                ExperimentRuns.shouldBeClean()
            } finally {
                broken.deleteIfExists()
                ExperimentRuns.resetQuietly()
            }
        }
    })

private infix fun Double.shouldBeInRangeOf(range: ClosedFloatingPointRange<Double>) {
    withClue("$this 가 $range 안이어야 한다") { (this in range) shouldBe true }
}

private infix fun Long.shouldBeInRangeOf(range: LongRange) {
    withClue("$this 가 $range 안이어야 한다") { (this in range) shouldBe true }
}
