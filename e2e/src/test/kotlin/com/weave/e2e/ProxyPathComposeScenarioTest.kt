package com.weave.e2e

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * `fault-experiments` 스펙의 "하위 서비스 호출은 프록시를 거친다" 시나리오 (F-007, F-001).
 *
 * 고도화 3부터 recommendation 호출에 시간 상한(200ms)이 있어, 프록시에 넣은 3초 지연은 피드를 늦추지 않고 시간 상한 실패로 드러난다.
 * 테스트마다 앞에서 [ComposeEnvironment.warmUpFeed]로 첫 호출의 지연을 치운다.
 *
 * 주소와 Compose 프로젝트는 [ComposeEnvironment]의 시스템 속성으로 받는다.
 *
 * **Toxiproxy 구성** (design 결정 1): 프록시 이름은 의존성 이름 `post`, `user`, `like`, `recommendation`, `ad`이고
 * (시나리오 JSON의 `target`과 같다), feed-service는 Compose 네트워크 별칭 `<이름>-proxy:1808x`로 부른다.
 * 테스트는 관리 API를 직접 부른다.
 * - 지연: `POST /proxies/<이름>/toxics` `{"name":..,"type":"latency","stream":"downstream","attributes":{"latency":ms}}`,
 *   거두기는 `DELETE /proxies/<이름>/toxics/<톡식 이름>`
 * - 끄기·켜기: `POST /proxies/<이름>` `{"enabled":false}` / `{"enabled":true}`
 * - 되돌리기: `POST /reset`(모든 프록시를 켜고 톡식을 지운다). 테스트마다 앞뒤로 부른다
 *
 * 하위 서비스 호출 실패 로그(`downstream_call_failed`, feed-aggregation 스펙)는 `docker compose logs feed-service`의
 * JSON 줄에서 피드 응답의 `X-Trace-Id`로 찾는다.
 */
@Tags("Compose")
class ProxyPathComposeScenarioTest :
    FunSpec({
        val latencyToxic = "weave_test_latency"

        fun toxiproxy(
            method: String,
            path: String,
            body: Any? = null,
        ): HttpResponse<String> {
            val response = ComposeEnvironment.send(method, "${ComposeEnvironment.toxiproxyUrl}$path", body)
            withClue("Toxiproxy $method $path 응답: ${response.body()}") { (response.statusCode() in 200..299) shouldBe true }
            return response
        }

        fun feed(): HttpResponse<String> =
            ComposeEnvironment.get(
                "${ComposeEnvironment.feedUrl}/feed",
                headers = mapOf("X-User-Id" to "1"),
                timeout = 60.seconds,
            )

        fun HttpResponse<String>.traceId(): String =
            withClue("응답의 X-Trace-Id 헤더") {
                headers().firstValue("X-Trace-Id").orElse(null).shouldNotBeNull()
            }

        /** feed-service 컨테이너 로그 중 [traceId]의 `downstream_call_failed` 줄. */
        fun downstreamFailures(traceId: String): List<Map<String, Any?>> {
            val logs = ComposeEnvironment.dockerCompose("logs", "--no-color", "--no-log-prefix", "feed-service")
            withClue("docker compose logs feed-service: $logs") { logs.exitCode shouldBe 0 }
            return logs.stdout
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("{") }
                .mapNotNull { line -> runCatching { ComposeEnvironment.readJson(line) }.getOrNull() }
                .filter { it["traceId"] == traceId && it["event"] == "downstream_call_failed" }
                .toList()
        }

        beforeTest {
            toxiproxy("POST", "/reset")
            ComposeEnvironment.warmUpFeed()
        }
        afterTest { toxiproxy("POST", "/reset") }

        test(
            "F-007 프록시에 지연을 넣는다: recommendation 프록시에 응답 지연 3,000ms를 넣으면 피드가 1,000ms 안에 오고 recommendation timeout 실패 로그가 있으며, 거둔 뒤에는 1,000ms 안에 200이다",
        ) {
            toxiproxy(
                "POST",
                "/proxies/recommendation/toxics",
                mapOf(
                    "name" to latencyToxic,
                    "type" to "latency",
                    "stream" to "downstream",
                    "attributes" to mapOf("latency" to 3000),
                ),
            )

            val (delayed, delayedTime) = measureTimedValue { feed() }
            withClue("지연을 넣은 피드 응답(${delayed.statusCode()}, ${delayedTime.inWholeMilliseconds}ms): ${delayed.body()}") {
                delayedTime shouldBeLessThan 1000.milliseconds
            }
            // 빨리 온 것이 프록시를 거치지 않아서가 아니라 지연이 시간 상한에 걸려서임을 실패 로그로 본다
            val traceId = delayed.traceId()
            eventually(30.seconds) {
                val failures = downstreamFailures(traceId)
                withClue("traceId $traceId 의 downstream_call_failed 줄") {
                    failures.filter { it["dependency"] == "recommendation" && it["cause"] == "timeout" }.shouldNotBeEmpty()
                }
            }

            toxiproxy("DELETE", "/proxies/recommendation/toxics/$latencyToxic")

            val (recovered, recoveredTime) = measureTimedValue { feed() }
            withClue("지연을 거둔 뒤 피드 응답(${recoveredTime.inWholeMilliseconds}ms): ${recovered.body()}") {
                recovered.statusCode() shouldBe 200
                recoveredTime shouldBeLessThan 1000.milliseconds
            }
        }

        test("F-007 한 의존성의 프록시를 끊는다: user 프록시를 끄면 하위 서비스 호출 실패 로그가 user 줄만 있고, 다시 켜면 피드가 200이다") {
            toxiproxy("POST", "/proxies/user", mapOf("enabled" to false))

            val failed = feed()
            val traceId = failed.traceId()

            eventually(30.seconds) {
                val failures = downstreamFailures(traceId)
                withClue("traceId $traceId 의 downstream_call_failed 줄 (피드 응답 ${failed.statusCode()}: ${failed.body()})") {
                    failures.shouldNotBeEmpty()
                    failures.filter { it["dependency"] != "user" }.shouldBeEmpty()
                }
            }

            toxiproxy("POST", "/proxies/user", mapOf("enabled" to true))

            val recovered = feed()
            withClue("user 프록시를 다시 켠 뒤 피드 응답: ${recovered.body()}") {
                recovered.statusCode() shouldBe 200
            }
        }
    })
