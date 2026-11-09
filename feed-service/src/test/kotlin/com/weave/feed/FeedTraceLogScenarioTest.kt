package com.weave.feed

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import com.weave.observability.testing.LogCapture
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * `trace-logging` 스펙에서 feed-service 경로(`GET /feed`, 좋아요 상태 변경)를 쓰는 시나리오 (F-004).
 *
 * 하위 서비스는 [DownstreamWireMock]으로 흉내 내고, 하위 서비스가 받은 `traceparent`는 WireMock의 요청 기록에서 읽는다.
 * 로그는 [LogCapture]로 잡고, 요청 하나의 줄은 응답의 `X-Trace-Id`와 같은 `traceId`로 찾는다(design 결정 8).
 * `feed-aggregation` 델타의 "하위 서비스 호출 실패 로그"는 따로 둔다.
 */
@Tags("Integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedTraceLogScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val wireMock = DownstreamWireMock
        val hex32 = Regex("[0-9a-f]{32}")

        suspend fun feed(): TracedFeedHttp {
            val environment = testContextManager().testContext.applicationContext.environment
            return TracedFeedHttp(environment.getRequiredProperty("local.server.port").toInt())
        }

        fun stubAllHealthy(postDelayMs: Int = 0) {
            wireMock.stubPosts(listOf(Post(id = 10, authorId = 3), Post(id = 11, authorId = 4)), postDelayMs)
            wireMock.stubUsers(listOf(3, 4))
            wireMock.stubLikes(
                listOf(
                    LikeState(postId = 10, likeCount = 1, liked = false),
                    LikeState(postId = 11, likeCount = 0, liked = false),
                ),
            )
            wireMock.stubRecommendations()
            wireMock.stubAds()
        }

        fun LogCapture.requestLines(traceId: String): List<Map<String, Any?>> =
            jsonLines().filter { it["event"] == "http_request" && it["traceId"] == traceId }

        fun downstreamTraceIds(path: String): List<String?> = wireMock.requestsTo(path).map { it.traceparentTraceId() }

        beforeEach { wireMock.reset() }

        context("응답 헤더의 traceId") {
            test("F-004 정상 응답: GET /feed가 200이면 X-Trace-Id가 16진수 32자이고, 그 요청을 처리하며 쓴 로그의 traceId와 같다") {
                stubAllHealthy()

                LogCapture().use { capture ->
                    val response = feed().getFeed()

                    response.statusCode() shouldBe 200
                    val traceId = response.traceId()
                    traceId shouldMatch hex32
                    eventually(5.seconds) {
                        withClue("traceId가 X-Trace-Id와 같은 feed-service 로그") {
                            capture.jsonLines().filter { it["traceId"] == traceId }.shouldNotBeEmpty()
                        }
                    }
                }
            }

            test("F-004 F-002 오류 응답: 요청 검증 실패의 400과 게시글을 대체할 수 없는 503 모두 X-Trace-Id가 있다") {
                // 테스트 기본 설정의 post 캐시 저장소는 연결할 수 없는 주소라, post가 실패하면 대체할 캐시가 없어 503이다
                stubAllHealthy()
                wireMock.stubFailure("post", 503)

                val badRequest = feed().getFeed(headers = emptyMap())
                badRequest.statusCode() shouldBe 400
                badRequest.traceId() shouldMatch hex32

                val serverError = feed().getFeed()
                serverError.statusCode() shouldBe 503
                serverError.traceId() shouldMatch hex32
            }
        }

        context("들어온 trace를 잇는다") {
            test("F-004 traceparent를 받는다: 들어온 traceparent의 traceId가 X-Trace-Id로 온다") {
                stubAllHealthy()

                val response =
                    feed().getFeed(
                        headers =
                            TracedFeedHttp.USER_HEADERS +
                                ("traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"),
                    )

                response.statusCode() shouldBe 200
                response.traceId() shouldBe "4bf92f3577b34da6a3ce929d0e0e4736"
            }

            test("F-004 traceparent가 없다: traceparent 없이 두 번 부르면 두 응답의 X-Trace-Id가 서로 다르다") {
                stubAllHealthy()

                val first = feed().getFeed()
                val second = feed().getFeed()

                first.statusCode() shouldBe 200
                second.statusCode() shouldBe 200
                first.traceId() shouldNotBe second.traceId()
            }
        }

        context("하위 서비스 호출로 전파") {
            test("F-004 피드 요청의 다섯 호출: post, recommendation, ad, user, like로 간 요청 모두 traceparent의 traceId가 X-Trace-Id와 같다") {
                stubAllHealthy()

                val response = feed().getFeed()

                response.statusCode() shouldBe 200
                val traceId = response.traceId()
                wireMock.paths.forEach { (dependency, path) ->
                    withClue("$dependency 요청의 traceparent traceId") {
                        downstreamTraceIds(path) shouldBe listOf(traceId)
                    }
                }
            }

            test("F-004 post가 끝난 뒤 시작한 호출: post가 200ms 늦어도 그 뒤에 시작한 user, like 요청의 traceId가 X-Trace-Id와 같다") {
                stubAllHealthy(postDelayMs = 200)

                val response = feed().getFeed()

                response.statusCode() shouldBe 200
                val traceId = response.traceId()
                val postReceivedAt =
                    wireMock
                        .requestsTo("/posts")
                        .single()
                        .loggedDate.time
                listOf("user" to "/users", "like" to "/likes").forEach { (dependency, path) ->
                    val requests = wireMock.requestsTo(path)
                    requests shouldHaveSize 1
                    withClue("$dependency 요청이 post 응답 뒤에 왔다") {
                        (requests.single().loggedDate.time - postReceivedAt) shouldBeGreaterThanOrEqual 200L
                    }
                    withClue("$dependency 요청의 traceparent traceId") {
                        requests.single().traceparentTraceId() shouldBe traceId
                    }
                }
            }

            test("F-004 F-003 좋아요 상태 변경: like-service로 간 상태 변경 요청의 traceparent traceId가 X-Trace-Id와 같다") {
                wireMock.stubLikeState(postId = 5, liked = true, likeCount = 3)

                val response = feed().putLike(5)

                response.statusCode() shouldBe 200
                downstreamTraceIds("/likes/5") shouldBe listOf(response.traceId())
            }
        }

        context("요청 완료 로그") {
            test("F-004 F-002 실패한 요청: GET /feed가 503이면 http_request 줄에 status 503과 X-Trace-Id와 같은 traceId가 있다") {
                stubAllHealthy()
                wireMock.stubFailure("post", 503)

                LogCapture().use { capture ->
                    val response = feed().getFeed()

                    response.statusCode() shouldBe 503
                    val traceId = response.traceId()
                    eventually(5.seconds) {
                        val lines = capture.requestLines(traceId)
                        lines shouldHaveSize 1
                        lines.single()["status"] shouldBe 503
                    }
                }
            }
        }

        context("로그에 남기지 않는 것") {
            test("F-004 하위 서비스 오류 본문이 로그에 없다: recommendation이 503과 표식 본문을 주면 feed-service의 어떤 로그 줄에도 표식이 없다") {
                val marker = "downstream-error-body-${UUID.randomUUID()}"
                stubAllHealthy()
                wireMock.server.stubFor(
                    get(urlPathEqualTo("/recommendations")).willReturn(
                        aResponse().withStatus(503).withHeader("Content-Type", "text/plain").withBody(marker),
                    ),
                )

                LogCapture().use { capture ->
                    val response = feed().getFeed()

                    // recommendation은 선택 등급이라 피드는 200이다 (F-002)
                    response.statusCode() shouldBe 200
                    response.traceId()
                    // 남기지 않음은 기다려도 판정할 수 없으니, 뒤에 보낸 확인용 요청의 줄이 나올 때까지 기다린다.
                    // Tomcat이 처리되지 않은 예외를 남기는 줄은 요청 완료 로그보다 늦게 쓰이므로 그 요청의 줄로는 기다리지 않는다.
                    // 비동기 appender는 들어온 순서대로 쓰므로 확인용 요청의 줄이 나올 때까지 표식이 없으면 남기지 않은 것이다.
                    val probeTraceId = feed().getFeed(headers = emptyMap()).traceId()
                    eventually(5.seconds) {
                        capture.requestLines(probeTraceId) shouldHaveSize 1
                    }
                    capture.lines().filter { marker in it.raw }.shouldBeEmpty()
                }
            }
        }
    }) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun downstreamBaseUrls(registry: DynamicPropertyRegistry) = DownstreamWireMock.registerBaseUrls(registry)
    }
}
