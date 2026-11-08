package com.weave.feed

import com.weave.feed.client.ad.AdResponse
import com.weave.feed.client.like.LikeResponse
import com.weave.feed.client.post.PostResponse
import com.weave.feed.client.recommendation.RecommendationResponse
import com.weave.feed.client.user.UserResponse
import com.weave.feed.gateway.AdGateway
import com.weave.feed.gateway.LikeGateway
import com.weave.feed.gateway.PostGateway
import com.weave.feed.gateway.RecommendationGateway
import com.weave.feed.gateway.TimeBudgetExhaustedException
import com.weave.feed.gateway.UserGateway
import com.weave.observability.testing.LogCapture
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.CompletionException
import kotlin.time.Duration.Companion.seconds

/**
 * 요청 시간 예산 (add-call-isolation design 결정 4, tasks 2.6, F-001, F-002).
 *
 * 고도화 4(add-graceful-degradation)부터 예산을 넘긴 의존성과 먼저 실패한 의존성은 피드를 실패시키지 않고 등급대로 빠진다
 * (`call-isolation` 델타 "요청 시간 예산", `graceful-degradation`). 이 테스트는 post가 성공하는 경우만 다뤄 응답의
 * `degradation.missing`을 본다. post가 실패하는 흐름은 `FeedAssemblerPostFlowTest`가 본다.
 *
 * 시계는 테스트가 움직인다([MutableClock]). post Gateway가 불리는 순간 시계를 앞으로 돌려 "post가 그만큼 걸렸다"를 흉내 낸다.
 * 끝나지 않는 호출은 완료되지 않는 future로 흉내 낸다. 마감이 이미 지난 경우만 다뤄 실제로 기다리지 않는다.
 * 기다리는 시간이 남은 예산인지는 `FeedCallIsolationBudgetScenarioTest`(`Integration`)가 실제 지연으로 본다.
 */
class FeedAssemblerBudgetTest :
    FunSpec({
        val postGateway = mockk<PostGateway>()
        val userGateway = mockk<UserGateway>()
        val likeGateway = mockk<LikeGateway>()
        val recommendationGateway = mockk<RecommendationGateway>()
        val adGateway = mockk<AdGateway>()
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val assembler =
            FeedAssembler(
                postGateway,
                userGateway,
                likeGateway,
                recommendationGateway,
                adGateway,
                timeBudget = Duration.ofMillis(250),
                clock = clock,
            )

        val posts =
            listOf(
                PostResponse(id = 10, authorId = 3, title = "게시글 10", content = "본문", createdAt = Instant.parse("2026-01-01T00:00:00Z")),
            )

        /** post가 [elapsed]만큼 걸려 끝난 것처럼 시계를 돌리고 게시글을 준다. */
        fun postTakes(elapsed: Duration) {
            every { postGateway.getLatestPosts(any()) } answers {
                clock.advance(elapsed)
                completedFuture(posts)
            }
        }

        // Spring 없이 도는 단위 테스트라 로그가 JSON이 아니라 logback 기본 형식(`key="value"`)이다
        fun LogCapture.budgetExhausted(): List<String> =
            lines()
                .map { it.raw }
                .filter { "event=\"downstream_call_failed\"" in it && "cause=\"budget_exhausted\"" in it }
                .mapNotNull { Regex("dependency=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }

        beforeEach {
            clearAllMocks()
            every { userGateway.getUsers(any()) } returns completedFuture(listOf(UserResponse(3, "사용자3", "https://u/3.png")))
            every { likeGateway.getLikes(any(), any()) } returns completedFuture(listOf(LikeResponse(10, 1, false)))
            every { recommendationGateway.getRecommendations() } returns
                completedFuture(listOf(RecommendationResponse(postId = 10, title = "게시글 10", reason = "많이 본 게시글")))
            every { adGateway.getAds() } returns
                completedFuture(listOf(AdResponse(id = 1, title = "광고 1", imageUrl = "https://a/1.png", landingUrl = "https://l/1")))
        }

        test("F-001 남은 시간: 예산 250ms에서 100ms가 지나면 150ms가 남고, 마감을 넘기면 0이다") {
            val budget = RequestBudget(clock.instant() + Duration.ofMillis(250), clock)

            clock.advance(Duration.ofMillis(100))
            budget.remaining() shouldBe Duration.ofMillis(150)

            clock.advance(Duration.ofMillis(200))
            budget.remaining() shouldBe Duration.ZERO
        }

        test("F-001 F-002 post가 끝났을 때 남은 예산이 없으면 user와 like를 부르지 않고, 둘 다 budget_exhausted로 빠진다") {
            postTakes(Duration.ofMillis(250))

            LogCapture().use { capture ->
                val feed = assembler.assemble(userId = 1, size = 20)

                feed.posts.map { it.id } shouldBe listOf(10L)
                feed.degradation.missing shouldContainAll listOf("user", "like")
                feed.posts
                    .single()
                    .author
                    ?.unknown shouldBe true
                feed.posts
                    .single()
                    .like.myLike shouldBe MyLike.UNKNOWN
                verify(exactly = 0) { userGateway.getUsers(any()) }
                verify(exactly = 0) { likeGateway.getLikes(any(), any()) }
                // post 다음에 부르는 recommendation·ad도 남은 시간이 없어 부르지 않는다. 이 테스트는 user·like만 본다
                eventually(5.seconds) { capture.budgetExhausted() shouldContainAll listOf("user", "like") }
            }
        }

        test("F-001 post가 끝났을 때 예산이 남아 있으면 user와 like를 부르고 피드를 만든다") {
            postTakes(Duration.ofMillis(249))

            val feed = assembler.assemble(userId = 1, size = 20)

            feed.posts.map { it.id } shouldBe listOf(10L)
            verify(exactly = 1) { userGateway.getUsers(listOf(3L)) }
            verify(exactly = 1) { likeGateway.getLikes(1, listOf(10L)) }
        }

        test("F-001 F-002 마감이 지나면 끝나지 않은 의존성만 budget_exhausted 실패 로그를 남기고 그 future를 끝내며, 그 의존성만 빠진다") {
            // post 100ms 뒤 user·like·recommendation을 부르고, 마지막에 부르는 ad가 나머지 150ms를 쓴다
            postTakes(Duration.ofMillis(100))
            val users = CompletableFuture<List<UserResponse>>()
            val recommendations = CompletableFuture<List<RecommendationResponse>>()
            every { userGateway.getUsers(any()) } returns users
            every { recommendationGateway.getRecommendations() } returns recommendations
            every { adGateway.getAds() } answers {
                clock.advance(Duration.ofMillis(150))
                completedFuture(emptyList())
            }

            LogCapture().use { capture ->
                val feed = assembler.assemble(userId = 1, size = 20)

                feed.degradation.missing shouldContainExactlyInAnyOrder listOf("user", "recommendation")
                eventually(5.seconds) { capture.budgetExhausted() shouldContainExactlyInAnyOrder listOf("user", "recommendation") }
                shouldThrow<CompletionException> { users.join() }.cause.shouldBeInstanceOf<TimeBudgetExhaustedException>()
                shouldThrow<CompletionException> { recommendations.join() }.cause.shouldBeInstanceOf<TimeBudgetExhaustedException>()
            }
        }

        test("F-001 F-002 예산 안에 먼저 실패한 의존성이 있으면 budget_exhausted가 아니라 그 의존성만 빠진다") {
            postTakes(Duration.ofMillis(10))
            val failure = IllegalStateException("ad 실패")
            every { adGateway.getAds() } returns CompletableFuture.failedFuture(failure)

            LogCapture().use { capture ->
                val feed = assembler.assemble(userId = 1, size = 20)

                feed.degradation.missing shouldBe listOf("ad")
                capture.budgetExhausted().shouldBeEmpty()
            }
        }
    })

/** 테스트가 움직이는 시계. */
private class MutableClock(
    private var now: Instant,
) : Clock() {
    fun advance(duration: Duration) {
        now += duration
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
