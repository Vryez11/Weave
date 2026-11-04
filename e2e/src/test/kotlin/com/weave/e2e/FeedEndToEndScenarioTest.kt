package com.weave.e2e

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * `feed-aggregation` 스펙의 "종단 간 동작" 시나리오 (F-006).
 *
 * 서비스 6개를 bootJar 프로세스로 띄우고 클라이언트처럼 feed-service만 부른다(design 결정 10).
 * 마지막 테스트가 프로세스를 내리고 모두 내려갔는지 검사한다. 테스트 순서는 적힌 순서다.
 */
@Tags("Integration")
class FeedEndToEndScenarioTest :
    FunSpec({
        val services = ServiceProcesses()
        val mapper = jacksonObjectMapper()
        val http = HttpClient.newHttpClient()

        beforeSpec { services.start() }
        afterSpec { services.stop() }

        fun feedRequest(path: String) =
            HttpRequest
                .newBuilder(URI.create("http://localhost:${services.port("feed-service")}$path"))
                .header("X-User-Id", "1")
                .timeout(Duration.ofSeconds(30))

        fun send(request: HttpRequest.Builder): Pair<Int, Map<String, Any?>> {
            val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
            val body = runCatching { mapper.readValue<Map<String, Any?>>(response.body()) }.getOrDefault(emptyMap())
            return response.statusCode() to body
        }

        fun getFeed() = send(feedRequest("/feed").GET())

        fun toggleLike(postId: Any?) = send(feedRequest("/feed/likes/$postId/toggle").POST(HttpRequest.BodyPublishers.noBody()))

        @Suppress("UNCHECKED_CAST")
        fun Map<String, Any?>.list(key: String) = this[key] as List<Map<String, Any?>>

        @Suppress("UNCHECKED_CAST")
        fun Map<String, Any?>.like() = this["like"] as Map<String, Any?>

        test("F-006 종단 간: 사용자 1이 피드를 받고 첫 게시글에 좋아요를 누르면 다음 피드에 반영된다") {
            val (firstStatus, firstFeed) = getFeed()
            // 실패하면 응답 본문을 함께 보여 준다. 서비스 로그는 e2e/build/e2e-logs/에 있다(CI에서는 artifact)
            withClue("첫 피드 응답: $firstFeed") { firstStatus shouldBe 200 }
            firstFeed.list("posts") shouldHaveSize 20
            firstFeed.list("recommendations").shouldNotBeEmpty()
            firstFeed.list("ads").shouldNotBeEmpty()

            val firstPost = firstFeed.list("posts").first()
            val postId = firstPost["id"]
            postId shouldNotBe null
            val countBefore = (firstPost.like()["count"] as Number).toLong()

            val (toggleStatus, toggled) = toggleLike(postId)
            withClue("토글 응답: $toggled") { toggleStatus shouldBe 200 }
            toggled["liked"] shouldBe true

            val (secondStatus, secondFeed) = getFeed()
            withClue("두 번째 피드 응답: $secondFeed") { secondStatus shouldBe 200 }
            val samePost = secondFeed.list("posts").single { it["id"] == postId }
            samePost.like()["myLike"] shouldBe "LIKED"
            (samePost.like()["count"] as Number).toLong() shouldBe countBefore + 1
        }

        test("F-006 종단 간: 띄운 서비스 프로세스 6개를 모두 내린다") {
            services.stop()

            services.started.keys shouldBe services.services.toSet()
            services.started
                .filterValues { it.isAlive }
                .keys
                .shouldBeEmpty()
        }
    })
