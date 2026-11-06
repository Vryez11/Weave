package com.weave.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * `fault-experiments` 스펙의 "대시보드를 불러온다" 시나리오 (F-007).
 *
 * 주소와 Compose 프로젝트는 [ComposeEnvironment]의 시스템 속성으로 받는다.
 * GIVEN의 `experiments/run.sh S-01 --rps 5 --warmup 5s --duration 20s`는 테스트가 직접 돌린다.
 *
 * **대시보드 형식** (design 결정 11)
 * - uid `weave-feed`. Grafana `GET /api/search`에 나오고 `GET /api/dashboards/uid/weave-feed`로 받는다.
 * - 패널은 `dashboard.panels`에 있고, 접힌 행(row)이면 그 행의 `panels`에 있다. 질의는 패널 `targets`의 `expr`(PromQL)이다.
 * - 질의에 쓰는 Grafana 변수는 시간 변수 `$__rate_interval`, `$__interval`, `$__range`(`${...}` 꼴 포함)뿐이다.
 *   테스트는 이 변수를 `5m`으로 바꿔 Prometheus `GET /api/v1/query`로 부른다. 다른 변수(`$dependency` 등)는 두지 않는다.
 * - 장애가 없는 S-01 뒤에도 시계열이 나와야 한다. 실패율처럼 분자가 비는 질의는 비지 않게 쓴다.
 * - 리포트의 자원 항목과 SLO 항목을 보여 주는 지표 이름이 질의 어딘가에 있다([expectedMetrics]).
 */
@Tags("Compose")
class ExperimentDashboardComposeScenarioTest :
    FunSpec({
        val dashboardUid = "weave-feed"
        val timeVariable = Regex("""\$\{?__(rate_interval|interval|range)}?""")

        // 피드 가용성·p50/p95/p99, 의존성별 호출 지연·실패율, Tomcat, 공유 풀(활성·대기열), 커넥션 풀(사용 중·대기), CPU
        val expectedMetrics =
            listOf(
                "http_server_requests_seconds",
                "http_client_requests_seconds",
                "tomcat_threads_busy_threads",
                "executor_active_threads",
                "executor_queued_tasks",
                "httpcomponents_httpclient_pool_total_leased",
                "httpcomponents_httpclient_pool_total_pending",
                "process_cpu_usage",
            )

        @Suppress("UNCHECKED_CAST")
        fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

        @Suppress("UNCHECKED_CAST")
        fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()

        /** 패널 제목과 질의. 접힌 행 안의 패널도 꺼낸다. */
        fun queries(panels: List<Any?>): List<Pair<String, String>> =
            panels.map { it.asMap() }.flatMap { panel ->
                val own =
                    panel["targets"]
                        .asList()
                        .mapNotNull { it.asMap()["expr"] as? String }
                        .filter { it.isNotBlank() }
                        .map { "${panel["title"]}" to it }
                own + queries(panel["panels"].asList())
            }

        test("F-007 대시보드를 불러온다: S-01을 짧게 돌린 뒤 Grafana에 피드 실험 대시보드가 있고 패널 질의가 모두 오류 없이 시계열을 돌려준다") {
            val run =
                ComposeEnvironment.experiment(
                    "run.sh",
                    "S-01",
                    "--rps",
                    "5",
                    "--warmup",
                    "5s",
                    "--duration",
                    "20s",
                    timeout = 5.minutes,
                )
            withClue("experiments/run.sh S-01 --rps 5 --warmup 5s --duration 20s: $run") { run.exitCode shouldBe 0 }

            val grafana = ComposeEnvironment.grafanaUrl
            val search = ComposeEnvironment.get("$grafana/api/search?type=dash-db")
            withClue("Grafana 검색 응답: ${search.body()}") {
                search.statusCode() shouldBe 200
                ComposeEnvironment.readJsonList(search.body()).map { it.asMap()["uid"] } shouldContain dashboardUid
            }

            val dashboardResponse = ComposeEnvironment.get("$grafana/api/dashboards/uid/$dashboardUid")
            withClue("대시보드 응답: ${dashboardResponse.body()}") { dashboardResponse.statusCode() shouldBe 200 }
            val dashboard = ComposeEnvironment.readJson(dashboardResponse.body())["dashboard"].asMap()
            val panelQueries = queries(dashboard["panels"].asList())
            withClue("대시보드 $dashboardUid 의 패널 질의") { panelQueries.shouldNotBeEmpty() }

            val allQueries = panelQueries.joinToString("\n") { it.second }
            expectedMetrics.forEach { metric ->
                withClue("대시보드 질의에 $metric 이 있어야 한다") { allQueries shouldContain metric }
            }

            val prometheus = ComposeEnvironment.prometheusUrl
            panelQueries.forEach { (title, expr) ->
                val query = expr.replace(timeVariable, "5m")
                withClue("패널 '$title' 질의 $expr 에 시간 변수 말고 다른 변수가 없어야 한다") { query shouldNotContain "$" }

                eventually(30.seconds) {
                    val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
                    val response = ComposeEnvironment.get("$prometheus/api/v1/query?query=$encoded")
                    withClue("패널 '$title' 질의 $query 의 Prometheus 응답: ${response.body()}") {
                        response.statusCode() shouldBe 200
                        val body = ComposeEnvironment.readJson(response.body())
                        body["status"] shouldBe "success"
                        body["data"].asMap()["result"].asList().shouldNotBeEmpty()
                    }
                }
            }
        }
    })
