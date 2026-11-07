package com.weave.feed

import com.weave.feed.client.ad.AdResponse
import com.weave.feed.client.like.LikeResponse
import com.weave.feed.client.post.PostResponse
import com.weave.feed.client.recommendation.RecommendationResponse
import com.weave.feed.client.user.UserResponse
import com.weave.feed.degradation.PostStaleCache
import com.weave.feed.degradation.PostsUnavailableException
import com.weave.feed.gateway.AdGateway
import com.weave.feed.gateway.LikeGateway
import com.weave.feed.gateway.PostGateway
import com.weave.feed.gateway.RecommendationGateway
import com.weave.feed.gateway.UserGateway
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.TimeUnit

/**
 * post 결과 흐름 (add-graceful-degradation design 결정 3, 8, 15, F-002).
 *
 * post가 성공하면 캐시에 넘기고, 실패하면 같은 `size`의 stale 캐시로 게시글을 채워 user·like를 그 게시글로 부르며, 대체할 수 없으면
 * recommendation·ad를 기다리지 않고 [PostsUnavailableException]을 던진다. 예산은 stale 경로에도 그대로 걸린다.
 *
 * 캐시는 실제 [PostStaleCache]에 MockK Redis 템플릿을 넣는다(쓰기는 부른 스레드에서 바로 돈다). 시계는 테스트가 움직이고, post Gateway가
 * 불리는 순간 시계를 돌려 "post가 그만큼 걸려 실패했다"를 흉내 낸다.
 */
class FeedAssemblerPostFlowTest :
    FunSpec({
        val postGateway = mockk<PostGateway>()
        val userGateway = mockk<UserGateway>()
        val likeGateway = mockk<LikeGateway>()
        val recommendationGateway = mockk<RecommendationGateway>()
        val adGateway = mockk<AdGateway>()
        val redis = mockk<StringRedisTemplate>()
        val values = mockk<ValueOperations<String, String>>(relaxed = true)
        val mapper = jacksonObjectMapper()
        val clock = PostFlowClock(Instant.parse("2026-01-01T00:00:00Z"))
        val assembler =
            FeedAssembler(
                postGateway,
                userGateway,
                likeGateway,
                recommendationGateway,
                adGateway,
                timeBudget = Duration.ofMillis(250),
                clock = clock,
                postStaleCache =
                    PostStaleCache.create(redis, mapper, clock, Duration.ofMinutes(10), Duration.ofSeconds(1), PostFlowDirectExecutor()),
            )

        val freshPosts =
            listOf(PostResponse(id = 20, authorId = 7, title = "게시글 20", content = "본문", createdAt = Instant.parse("2026-01-01T00:00:00Z")))
        val cachedPosts =
            listOf(
                PostResponse(id = 10, authorId = 3, title = "게시글 10", content = "본문", createdAt = Instant.parse("2025-12-31T00:00:00Z")),
                PostResponse(id = 11, authorId = 4, title = "게시글 11", content = "본문", createdAt = Instant.parse("2025-12-31T00:00:00Z")),
            )

        /** post가 [elapsed]만큼 걸려 실패한 것처럼 시계를 돌리고 실패한 future를 준다. */
        fun postFailsAfter(elapsed: Duration) {
            every { postGateway.getLatestPosts(any()) } answers {
                clock.advance(elapsed)
                CompletableFuture.failedFuture(IllegalStateException("post 503"))
            }
        }

        /** [age] 전에 받은 [cachedPosts]가 `size=20` 캐시에 있다. */
        fun cachedBefore(age: Duration): Instant {
            val cachedAt = clock.instant() - age
            every { values.get(PostStaleCache.key(20)) } returns
                mapper.writeValueAsString(PostStaleCache.CachedPosts(cachedAt, cachedPosts))
            return cachedAt
        }

        beforeEach {
            clearAllMocks()
            clock.reset()
            every { redis.opsForValue() } returns values
            every { values.get(any()) } returns null
            every { userGateway.getUsers(any()) } answers {
                completedFuture(firstArg<List<Long>>().map { UserResponse(it, "사용자$it", "https://u/$it.png") })
            }
            every { likeGateway.getLikes(any(), any()) } answers {
                completedFuture(secondArg<List<Long>>().map { LikeResponse(it, 1, false) })
            }
            every { recommendationGateway.getRecommendations() } returns
                completedFuture(listOf(RecommendationResponse(postId = 10, title = "게시글 10", reason = "많이 본 게시글")))
            every { adGateway.getAds() } returns
                completedFuture(listOf(AdResponse(id = 1, title = "광고 1", imageUrl = "https://a/1.png", landingUrl = "https://l/1")))
        }

        test("F-002 post가 성공하면 그 게시글을 같은 size의 캐시에 넘기고 stale은 비어 있다") {
            every { postGateway.getLatestPosts(20) } returns completedFuture(freshPosts)

            val feed = assembler.assemble(userId = 1, size = 20)

            feed.posts.map { it.id } shouldBe listOf(20L)
            feed.degradation.stale.shouldBeEmpty()
            verify(exactly = 1) { values.set(PostStaleCache.key(20), any(), Duration.ofMinutes(10)) }
            verify(exactly = 0) { values.get(any()) }
        }

        test("F-002 post가 실패하고 10분 안의 캐시가 있으면 캐시의 게시글이 stale로 오고 user·like는 그 게시글로 부른다") {
            postFailsAfter(Duration.ofMillis(10))
            val cachedAt = cachedBefore(Duration.ofMinutes(5))

            val feed = assembler.assemble(userId = 1, size = 20)

            feed.posts.map { it.id } shouldBe listOf(10L, 11L)
            feed.degradation.stale shouldBe listOf(StaleSection("post", cachedAt))
            feed.degradation.missing.shouldBeEmpty()
            feed.posts.map { it.author?.nickname } shouldBe listOf("사용자3", "사용자4")
            feed.recommendations.map { it.postId } shouldBe listOf(10L)
            verify(exactly = 1) { userGateway.getUsers(listOf(3L, 4L)) }
            verify(exactly = 1) { likeGateway.getLikes(1, listOf(10L, 11L)) }
            verify(exactly = 0) { values.set(any(), any(), any<Duration>()) }
        }

        context("대체할 수 없으면 recommendation·ad를 기다리지 않고 PostsUnavailableException이다") {
            data class Case(
                val name: String,
                val reason: String,
                val given: () -> Unit,
            )

            listOf(
                Case("캐시가 없다", "cache_miss") {},
                Case("캐시가 10분을 넘었다", "cache_expired") { cachedBefore(Duration.ofMinutes(11)) },
                Case("캐시를 읽지 못했다", "cache_error") {
                    every { values.get(any()) } throws QueryTimeoutException("Redis 명령 타임아웃")
                },
            ).forEach { case ->
                test("F-002 ${case.name}: 이유는 ${case.reason}이고 user·like는 부르지 않는다") {
                    postFailsAfter(Duration.ofMillis(10))
                    case.given()
                    val recommendations = CompletableFuture<List<RecommendationResponse>>()
                    val ads = CompletableFuture<List<AdResponse>>()
                    every { recommendationGateway.getRecommendations() } returns recommendations
                    every { adGateway.getAds() } returns ads

                    val error = shouldThrow<PostsUnavailableException> { assembler.assemble(userId = 1, size = 20) }

                    error.reason shouldBe case.reason
                    // 기다렸다면 마감에 예산 초과로 끝났다
                    recommendations.isDone shouldBe false
                    ads.isDone shouldBe false
                    verify(exactly = 0) { userGateway.getUsers(any()) }
                    verify(exactly = 0) { likeGateway.getLikes(any(), any()) }
                }
            }
        }

        context("stale 경로의 예산") {
            test("F-001 F-002 post가 100ms 걸려 실패하면 남은 예산으로 user·like를 부른다") {
                postFailsAfter(Duration.ofMillis(100))
                cachedBefore(Duration.ofMinutes(1))

                val feed = assembler.assemble(userId = 1, size = 20)

                feed.degradation.missing.shouldBeEmpty()
                verify(exactly = 1) { userGateway.getUsers(listOf(3L, 4L)) }
                verify(exactly = 1) { likeGateway.getLikes(1, listOf(10L, 11L)) }
            }

            test("F-001 F-002 post가 실패했을 때 남은 예산이 없으면 캐시의 게시글은 오지만 user·like는 부르지 않고 빠진다") {
                postFailsAfter(Duration.ofMillis(250))
                cachedBefore(Duration.ofMinutes(1))

                val feed = assembler.assemble(userId = 1, size = 20)

                feed.posts.map { it.id } shouldBe listOf(10L, 11L)
                feed.degradation.stale.map { it.section } shouldBe listOf("post")
                feed.degradation.missing shouldContainAll listOf("user", "like")
                verify(exactly = 0) { userGateway.getUsers(any()) }
                verify(exactly = 0) { likeGateway.getLikes(any(), any()) }
            }
        }
    })

/** 넘긴 작업을 부른 스레드에서 바로 돈다. */
private class PostFlowDirectExecutor : AbstractExecutorService() {
    override fun execute(command: Runnable) = command.run()

    override fun shutdown() = Unit

    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

    override fun isShutdown() = false

    override fun isTerminated() = false

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ) = true
}

/** 테스트가 움직이는 시계. */
private class PostFlowClock(
    private val start: Instant,
) : Clock() {
    private var now = start

    fun reset() {
        now = start
    }

    fun advance(duration: Duration) {
        now += duration
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
