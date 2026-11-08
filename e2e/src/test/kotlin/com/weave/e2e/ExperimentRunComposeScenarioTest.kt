package com.weave.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
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
 * `fault-experiments` 스펙 중 `experiments/run.sh`의 시나리오 (F-007, F-001, F-002).
 * "짧게 돌린다", "없는 시나리오", "환경이 떠 있지 않다", "앞에 남은 장애", "앞에 남은 post 캐시", "정상 종료 뒤", "중간에 멈춘다",
 * "준비 구간은 세지 않는다", "모든 요청이 실패한다", "시간 초과된 요청", "리포트 항목", "선택 의존성이 느리다"(degrade 비율),
 * "부하 도구가 실패한다", "Tomcat 스레드가 모두 묶여도 긁힌다"(feed-service의 실험 지표), "광고 요청 한도를 넘는 짧은 실행"(Retry-After 기준, F-005).
 *
 * 고도화 4(add-graceful-degradation)부터 선택 의존성이 실패해도 피드는 200이고(S-02 짧은 실행은 SLO 충족), post가 실패하면
 * post 캐시(Redis)로 대체한다. "모든 요청이 실패한다"는 Redis를 멈춰 캐시를 읽지 못하게 한 뒤 S-06을 돌린다.
 *
 * `:e2e:composeTest`가 띄운 환경에서 `run.sh`를 프로세스로 돌린다(add-fault-experiments design 결정 13).
 * 고도화 3(add-call-isolation)부터 하위 서비스 호출에 격리가 있어, MVP를 전제로 했던 "짧게 돌린다", "시간 초과된 요청",
 * "Tomcat 스레드가 모두 묶여도 긁힌다"는 스펙 델타대로 바뀌었다. 뒤의 둘은 feed-service를 다른 설정으로 다시 만든다
 * ([ComposeEnvironment.recreateFeedService]). 끝나면 기본 설정으로 되돌린다. 결과 폴더와 장애 상태를 보는 법은 [ExperimentRuns] KDoc.
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
 *   "sloMet": true, "expectation": "meet", "matchesExpectation": true,
 *   "criteria": { "availability": { "met": true, "expectation": "meet" }, "p99": { "met": true, "expectation": "meet" } },
 *   "mismatchedCriteria": [],
 *   "verdict": "SLO 충족, 기대와 같음",    // 판정 값(expectation부터 failedThresholds까지)은 lib/verdict.sh의 출력 그대로(ExperimentVerdictScenarioTest)
 *   "failedThresholds": [],
 *   "dependencies": {                    // 의존성 5개 모두
 *     "post": { "p99Ms": 48.2, "failureRate": 0.0, "requestRatio": 1.0,
 *               "requestRatioMax": 1.0,   // 요청 배율의 20초 구간 최댓값. 측정이 40초보다 짧으면 requestRatio와 같은 값(F-005)
 *               "retries": { "retried": 0, "budget_exhausted": 0, "deadline": 0, "attempts_exhausted": 0 } }, ...  // 측정 구간 재시도 결정 수
 *   },
 *   "adRejections": { "rejected": 0, "allowed": null },  // ad-service가 돌려준 429 수, 429를 받아도 되는 수(시나리오에 quota 장애가 있을 때만, F-005)
 *   "resources": {                       // 측정 구간의 최댓값·평균
 *     "tomcatBusyThreads": { "max": 16, "avg": 15.2 },
 *     "feedCpuUsage": { "max": .., "avg": .. },
 *     "bulkheads": { "post": { "activeThreads": { "max": .., "avg": .. }, "queueDepth": { "max": .., "avg": .. } }, ... },
 *     "connectionPools": { "post": { "leased": { "max": .., "avg": .. }, "pending": { "max": .., "avg": .. } }, ... }
 *   },
 *   "degradeRatio": {                    // lib/degrade.sh의 출력 그대로(ExperimentDegradeRatioScenarioTest). 200 응답이 없으면 "없음"
 *     "post": 0, "user": 0, "like": 0, "recommendation": 1, "ad": 0, "any": 1
 *   },
 *   "circuitState": {                    // 의존성마다 측정 구간에 거친 상태. lib/circuits.sh의 출력 그대로(ExperimentCircuitStateScenarioTest)
 *     "post": "CLOSED", "recommendation": "CLOSED → OPEN → HALF_OPEN → OPEN", ...
 *   },
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
 * - 결과 폴더의 `verdict-input.json`은 판정 스크립트에 넘긴 판정 입력이다(`requestRatioMax`, `adRejections`. 형식은 [ExperimentVerdictScenarioTest]).
 *   `allowed` = (ceil(측정 초 ÷ 시나리오 quota의 `windowSeconds`) + 1) × ad 벌크헤드 최대 스레드(`resilience4j_bulkhead_max_thread_pool_size{name="ad"}`)
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

        test(
            "F-002 짧게 돌린다: S-02를 5 RPS·준비 5초·측정 20초로 돌리면 results/ 아래 리포트가 생기고 측정 요청 수는 95~105, recommendation 호출 실패율은 0.9 이상, p99는 1,000ms 미만이며 SLO를 지킨다",
        ) {
            val (result, folder) = shortS02
            val report = ExperimentRuns.report(folder)
            withClue("$result\nreport.json: $report") {
                folder.resolve("report.md").exists() shouldBe true
                (report["requests"] as Number).toInt() shouldBeInRange 95..105
                // 3초 지연이 피드를 늦추지 않고 recommendation 호출의 시간 상한 실패로 드러난다
                report["dependencies"].asMap()["recommendation"].asMap()["failureRate"].asDouble() shouldBeGreaterThanOrEqualTo 0.9
                report["latencyMs"].asMap()["p99"].asDouble() shouldBeLessThan 1000.0
                // 추천만 빠지고 피드는 200이라 고도화 4의 기대(meet)대로 SLO를 지킨다. 판정 계산은 ExperimentVerdictScenarioTest가 본다
                report["verdict"] shouldBe "SLO 충족, 기대와 같음"
                result.exitCode shouldBe 0
            }
        }

        test("F-007 정상 종료 뒤: S-02 짧은 실행이 끝나면 프록시 5개가 켜져 있고 장애가 없으며, 하위 서비스 장애 설정은 기본값이고 ad 한도는 꺼져 있다") {
            shortS02
            ExperimentRuns.shouldBeClean()
        }

        test("F-002 리포트 항목: S-02 짧은 실행의 리포트에 실행 정보, 지연·가용성·기준별 판정, 의존성별 값, 자원 사용량(벌크헤드 포함), 서킷 상태, degrade 비율, Grafana 링크, 장애 시각이 있다") {
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
                report["sloMet"] shouldBe true
                // 고도화 5(F-005)부터 기대는 기준마다의 객체다
                report["expectation"] shouldBe mapOf("availability" to "meet", "p99" to "meet", "requestRatio" to "meet")
                report["matchesExpectation"] shouldBe true
                val criteria = report["criteria"].asMap()
                listOf("availability", "p99", "requestRatio").forEach { name ->
                    val criterion = criteria[name].asMap()
                    withClue("기준 $name: $criterion") {
                        (criterion["met"] is Boolean) shouldBe true
                        criterion["expectation"] shouldBe "meet"
                    }
                }

                val dependencies = report["dependencies"].asMap()
                ExperimentRuns.dependencies.keys.forEach { name ->
                    val dependency = dependencies[name].asMap()
                    withClue("의존성 $name: $dependency") {
                        dependency["p99Ms"].asDouble() shouldBeGreaterThanOrEqualTo 0.0
                        dependency["failureRate"].asDouble() shouldBeInRangeOf 0.0..1.0
                        dependency["requestRatio"].asDouble() shouldBeGreaterThan 0.0
                        dependency["requestRatioMax"].asDouble() shouldBeGreaterThan 0.0
                        val retries = dependency["retries"].asMap()
                        retries.keys shouldBe setOf("retried", "budget_exhausted", "deadline", "attempts_exhausted")
                        retries.values.forEach { it.asDouble() shouldBeGreaterThanOrEqualTo 0.0 }
                    }
                }
                val adRejections = report["adRejections"].asMap()
                withClue("adRejections: $adRejections") {
                    adRejections["rejected"].asDouble() shouldBeGreaterThanOrEqualTo 0.0
                    // S-02에는 ad 요청 한도 장애가 없다
                    adRejections["allowed"].shouldBeNull()
                }
                val verdictInput = ExperimentRuns.json(folder.resolve("verdict-input.json"))
                withClue("verdict-input.json: $verdictInput") {
                    verdictInput["requestRatioMax"].asMap().keys shouldBe ExperimentRuns.dependencies.keys
                    verdictInput["adRejections"].asMap() shouldBe adRejections
                }

                val resources = report["resources"].asMap()
                listOf("tomcatBusyThreads", "feedCpuUsage").forEach {
                    resources[it].asMap().shouldHaveMaxAndAverage(it)
                }
                val bulkheads = resources["bulkheads"].asMap()
                ExperimentRuns.dependencies.keys.forEach { name ->
                    val bulkhead = bulkheads[name].asMap()
                    bulkhead["activeThreads"].asMap().shouldHaveMaxAndAverage("$name 벌크헤드 사용 중 스레드")
                    bulkhead["queueDepth"].asMap().shouldHaveMaxAndAverage("$name 벌크헤드 대기열")
                }
                val pools = resources["connectionPools"].asMap()
                ExperimentRuns.dependencies.keys.forEach { name ->
                    val pool = pools[name].asMap()
                    pool["leased"].asMap().shouldHaveMaxAndAverage("$name 커넥션 풀 사용 중")
                    pool["pending"].asMap().shouldHaveMaxAndAverage("$name 커넥션 풀 대기")
                }

                val degradeRatio = report["degradeRatio"].asMap()
                (ExperimentRuns.dependencies.keys + "any").forEach { name ->
                    withClue("degrade 비율 $name: ${degradeRatio[name]}") { degradeRatio[name].asDouble() shouldBeInRangeOf 0.0..1.0 }
                }
                val circuits = report["circuitState"].asMap()
                ExperimentRuns.dependencies.keys.forEach { name ->
                    withClue("$name 서킷 상태: ${circuits[name]}") {
                        (circuits[name] as String) shouldMatch Regex("(CLOSED|OPEN|HALF_OPEN)( → (CLOSED|OPEN|HALF_OPEN))*")
                    }
                }

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
                markdown shouldContain "SLO 충족, 기대와 같음"
                markdown shouldContain "degrade"
                markdown shouldContain "CLOSED"
                markdown shouldContain "요청 배율 최댓값"
                markdown shouldContain "429"
            }
        }

        test(
            "F-005 광고 요청 한도를 넘는 짧은 실행: S-07을 20 RPS·측정 30초로 돌리면 ad-service 429 수는 받아도 되는 수(4 × ad 동시 호출 상한) 이하이고 Retry-After 기준을 지키며 ad degrade 비율은 0보다 크다",
        ) {
            val (result, folder) = run("S-07", "--rps", "20", "--warmup", "5s", "--duration", "30s")
            val report = ExperimentRuns.report(folder)
            withClue("$result\nreport.json: $report") {
                val adRejections = report["adRejections"].asMap()
                val allowed = adRejections["allowed"].asDouble()
                // 창 10초, 측정 30초: ceil(30 / 10) + 1 = 4개 창. ad 벌크헤드는 main 값(40)이다
                allowed shouldBe 4.0 * 40
                adRejections["rejected"].asDouble() shouldBeLessThanOrEqualTo allowed
                report["criteria"].asMap()["retryAfter"].asMap()["met"] shouldBe true
                report["degradeRatio"].asMap()["ad"].asDouble() shouldBeGreaterThan 0.0
            }
        }

        test("F-002 선택 의존성이 느리다: S-02 짧은 실행의 가용성은 1이고 recommendation degrade 비율은 1, 나머지 의존성은 0이다") {
            val (result, folder) = shortS02
            val report = ExperimentRuns.report(folder)
            withClue("$result\nreport.json: $report") {
                report["availability"].asDouble() shouldBe (1.0 plusOrMinus 1e-9)
                val degradeRatio = report["degradeRatio"].asMap()
                degradeRatio["recommendation"].asDouble() shouldBe (1.0 plusOrMinus 1e-9)
                listOf("post", "user", "like", "ad").forEach { name ->
                    withClue(name) { degradeRatio[name].asDouble() shouldBe (0.0 plusOrMinus 1e-9) }
                }
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

        test("F-002 앞에 남은 post 캐시: 실험 부하가 쓰지 않는 size=7의 post 캐시를 넣어 둬도 S-01 짧은 실행이 끝난 뒤 그 캐시가 없다") {
            val key = "weave:feed:posts:latest:v1:size:7"
            val set = ExperimentRuns.redis("SET", key, "{\"cachedAt\":\"2026-01-01T00:00:00Z\",\"posts\":[]}", "EX", "600")
            withClue("redis-cli SET: $set") { set.exitCode shouldBe 0 }

            val (result, _) = run("S-01", *ExperimentRuns.shortOptions())
            val exists = ExperimentRuns.redis("EXISTS", key)
            withClue("$result\nredis-cli EXISTS: $exists") { exists.stdout.trim() shouldBe "0" }
        }

        test("F-007 준비 구간은 세지 않는다: S-01을 준비 10초·측정 20초로 돌리면 측정 요청 수는 95~105다") {
            val (result, folder) = run("S-01", *ExperimentRuns.shortOptions(warmup = "10s"))
            val report = ExperimentRuns.report(folder)
            withClue("$result\nreport.json: $report") {
                (report["requests"] as Number).toInt() shouldBeInRange 95..105
            }
        }

        test("F-002 모든 요청이 실패한다: post 캐시 저장소(Redis)를 멈추고 post 프록시가 꺼진 S-06 짧은 실행의 가용성은 0이고 지연 p50·p95·p99가 있다") {
            // post가 꺼지면 대체할 캐시를 읽지 못해 피드가 503이다. Redis가 떠 있으면 준비 구간이 채운 캐시로 stale 200이 된다
            val stopped = ComposeEnvironment.dockerCompose("stop", "redis")
            withClue("docker compose stop redis: $stopped") { stopped.exitCode shouldBe 0 }
            try {
                val (result, folder) = run("S-06", *ExperimentRuns.shortOptions())
                val report = ExperimentRuns.report(folder)
                withClue("$result\nreport.json: $report") {
                    report["availability"].asDouble() shouldBe (0.0 plusOrMinus 1e-9)
                    val latency = report["latencyMs"].asMap()
                    listOf("p50", "p95", "p99").forEach { latency[it].asDouble() shouldBeGreaterThan 0.0 }
                }
                ExperimentRuns.shouldBeClean()
            } finally {
                val started = ComposeEnvironment.dockerCompose("start", "redis")
                withClue("docker compose start redis: $started") { started.exitCode shouldBe 0 }
            }
        }

        test("F-001 시간 초과된 요청: S-01을 20 RPS·측정 30초로 돌리며 측정 구간 5초 뒤 feed-service를 10초 멈추면 지연 p99는 4,500ms 이상이다") {
            val before = ExperimentRuns.resultFolders()
            val started = ExperimentRuns.startRun("S-01", "--rps", "20", "--warmup", "5s", "--duration", "30s")
            try {
                eventually(3.minutes) {
                    withClue("run.sh 가 아직 돌고 있어야 한다: $started") { started.process.isAlive shouldBe true }
                    val folder = ExperimentRuns.newResultFolder(before, "S-01")
                    withClue("${folder.resolve("raw.json")} 에 측정 구간 요청이 있어야 한다") {
                        ExperimentRuns.hasMeasureRequest(folder.resolve("raw.json")) shouldBe true
                    }
                }
                Thread.sleep(5_000)
                // 멈춘 동안 보낸 요청은 응답을 받지 못해 k6 요청 시간 초과(5초)로 끝난다
                val paused = ComposeEnvironment.dockerCompose("pause", "feed-service")
                withClue("docker compose pause feed-service: $paused") { paused.exitCode shouldBe 0 }
                try {
                    Thread.sleep(10_000)
                } finally {
                    val unpaused = ComposeEnvironment.dockerCompose("unpause", "feed-service")
                    withClue("docker compose unpause feed-service: $unpaused") { unpaused.exitCode shouldBe 0 }
                }
                started.await(runTimeout)
            } finally {
                if (started.process.isAlive) started.process.destroyForcibly().waitFor()
                runCatching { ComposeEnvironment.dockerCompose("unpause", "feed-service") }
            }
            val report = ExperimentRuns.report(ExperimentRuns.newResultFolder(before, "S-01"))
            withClue("report.json: $report") {
                report["latencyMs"].asMap()["p99"].asDouble() shouldBeGreaterThanOrEqualTo 4500.0
            }
        }

        test(
            "F-001 Tomcat 스레드가 모두 묶여도 긁힌다: recommendation 격리를 넓혀 S-03을 60 RPS로 돌리면 feed-service 지표 수집 비율은 1이고 Tomcat 사용 중 스레드 최댓값은 200이다",
        ) {
            // 동시 요청 60 × 5초 = 300개가 Tomcat 최대 스레드 200을 넘도록 recommendation 호출이 10초까지 기다리게 한다.
            // 서킷은 최근 호출 수와 최소 호출 수를 이 실행의 호출 수(약 1,500)보다 크게 둬 열리지 않게 한다
            ComposeEnvironment.recreateFeedService(widenedRecommendationIsolation)
            try {
                val (result, folder) = run("S-03", "--rps", "60", "--warmup", "5s", "--duration", "20s")
                val report = ExperimentRuns.report(folder)
                withClue("$result\nreport.json: $report") {
                    report["feedScrapeRatio"].asDouble() shouldBe (1.0 plusOrMinus 1e-9)
                    report["resources"].asMap()["tomcatBusyThreads"].asMap()["max"].asDouble() shouldBe 200.0
                }
                ExperimentRuns.shouldBeClean()
            } finally {
                ComposeEnvironment.recreateFeedService()
            }
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

/** "Tomcat 스레드가 모두 묶여도 긁힌다"가 feed-service에 주는 `SPRING_APPLICATION_JSON` (add-call-isolation design 결정 10). */
private val widenedRecommendationIsolation =
    """
    {
      "resilience4j.timelimiter.instances.recommendation.timeout-duration": "10s",
      "spring.http.serviceclient.recommendation.connect-timeout": "10s",
      "spring.http.serviceclient.recommendation.read-timeout": "10s",
      "weave.feed.time-budget": "10s",
      "resilience4j.thread-pool-bulkhead.instances.recommendation.core-thread-pool-size": 300,
      "resilience4j.thread-pool-bulkhead.instances.recommendation.max-thread-pool-size": 300,
      "weave.http-client.groups.recommendation.max-connections": 300,
      "resilience4j.circuitbreaker.instances.recommendation.sliding-window-size": 100000,
      "resilience4j.circuitbreaker.instances.recommendation.minimum-number-of-calls": 100000
    }
    """.trimIndent()

private infix fun Double.shouldBeInRangeOf(range: ClosedFloatingPointRange<Double>) {
    withClue("$this 가 $range 안이어야 한다") { (this in range) shouldBe true }
}

private infix fun Long.shouldBeInRangeOf(range: LongRange) {
    withClue("$this 가 $range 안이어야 한다") { (this in range) shouldBe true }
}
