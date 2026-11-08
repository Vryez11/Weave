package com.weave.feed.degradation

import com.weave.feed.client.post.PostResponse
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * post stale 캐시의 키·값 형식, stale 판단, 쓰기 간격·대기열 (add-graceful-degradation design 결정 4, 5, 15, F-002).
 *
 * Redis 템플릿은 MockK다. 쓰기는 기본으로 부른 스레드에서 바로 돌게 해([DirectExecutor]) 결과를 바로 본다. 대기열은 실제 쓰기 실행기
 * ([PostStaleCache.newWriter])로 본다. 시계는 테스트가 움직인다.
 */
class PostStaleCacheTest :
    FunSpec({
        val redis = mockk<StringRedisTemplate>()
        val values = mockk<ValueOperations<String, String>>(relaxed = true)
        val mapper = jacksonObjectMapper()
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val maxStaleness = Duration.ofMinutes(10)
        val writeInterval = Duration.ofSeconds(1)

        fun cache() = PostStaleCache.create(redis, mapper, clock, maxStaleness, writeInterval, DirectExecutor())

        val posts =
            listOf(
                PostResponse(id = 10, authorId = 3, title = "게시글 10", content = "본문", createdAt = Instant.parse("2025-12-31T00:00:00Z")),
                PostResponse(id = 11, authorId = 4, title = "게시글 11", content = "본문", createdAt = Instant.parse("2025-12-30T00:00:00Z")),
            )

        /** [cachedAt]에 받은 [posts]가 든 값. */
        fun stored(cachedAt: Instant) = mapper.writeValueAsString(PostStaleCache.CachedPosts(cachedAt, posts))

        beforeEach {
            clearAllMocks()
            clock.reset()
            every { redis.opsForValue() } returns values
        }

        context("쓰기") {
            test("F-002 키는 size별이고 값은 받은 시각과 post-service 응답 항목이며 TTL은 stale 허용 시간이다") {
                val value = slot<String>()
                every { values.set(any(), capture(value), any<Duration>()) } returns Unit

                cache().write(20, posts)

                verify(exactly = 1) { values.set("weave:feed:posts:latest:v1:size:20", any(), maxStaleness) }
                val json = mapper.readValue<Map<String, Any?>>(value.captured)
                json["cachedAt"] shouldBe "2026-01-01T00:00:00Z"
                mapper.readValue(value.captured, PostStaleCache.CachedPosts::class.java).posts shouldBe posts
            }

            test("F-002 같은 키는 쓰기 간격 안에 한 번만 쓰고, 간격이 지나면 다시 쓴다") {
                val cache = cache()

                cache.write(20, posts)
                clock.advance(Duration.ofMillis(999))
                cache.write(20, posts)
                verify(exactly = 1) { values.set(PostStaleCache.key(20), any(), any<Duration>()) }

                clock.advance(Duration.ofMillis(1))
                cache.write(20, posts)
                verify(exactly = 2) { values.set(PostStaleCache.key(20), any(), any<Duration>()) }
            }

            test("F-002 다른 키는 쓰기 간격에 걸리지 않는다") {
                val cache = cache()

                cache.write(20, posts)
                cache.write(5, posts)

                verify(exactly = 1) { values.set(PostStaleCache.key(20), any(), any<Duration>()) }
                verify(exactly = 1) { values.set(PostStaleCache.key(5), any(), any<Duration>()) }
            }

            test("F-002 쓰기 실패는 부른 쪽으로 올라가지 않는다") {
                every { values.set(any(), any(), any<Duration>()) } throws QueryTimeoutException("Redis 명령 타임아웃")

                cache().write(20, posts)
            }

            test("F-002 쓰기를 기다리지 않고, 쓰기 스레드가 묶여 대기열 50이 차면 그 뒤의 쓰기는 버린다") {
                val release = CountDownLatch(1)
                every { values.set(any(), any(), any<Duration>()) } answers { release.await(5, TimeUnit.SECONDS) }
                PostStaleCache.create(redis, mapper, clock, maxStaleness, writeInterval).use { cache ->
                    val started = System.nanoTime()
                    // 하나는 쓰기 스레드에서 막히고 50개가 대기열에 쌓이며, 마지막 하나는 버린다. 키가 달라 쓰기 간격에 걸리지 않는다
                    (1..PostStaleCache.WRITE_QUEUE_CAPACITY + 2).forEach { size -> cache.write(size, posts) }
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) shouldBeLessThan 1_000L

                    release.countDown()
                    eventually(5.seconds) {
                        verify(exactly = PostStaleCache.WRITE_QUEUE_CAPACITY + 1) { values.set(any(), any(), any<Duration>()) }
                    }
                    verify(exactly = 0) { values.set(PostStaleCache.key(PostStaleCache.WRITE_QUEUE_CAPACITY + 2), any(), any<Duration>()) }
                }
            }
        }

        context("읽기") {
            test("F-002 stale 허용 시간 안의 값이면 게시글과 받은 시각을 준다") {
                every { values.get(PostStaleCache.key(20)) } returns stored(clock.instant())
                clock.advance(maxStaleness)

                val read = cache().read(20)

                read.shouldBeInstanceOf<PostCacheRead.Hit>()
                read.posts shouldBe posts
                read.cachedAt shouldBe Instant.parse("2026-01-01T00:00:00Z")
            }

            test("F-002 stale 허용 시간이 지난 값은 쓰지 않는다(cache_expired)") {
                every { values.get(PostStaleCache.key(20)) } returns stored(clock.instant())
                clock.advance(maxStaleness + Duration.ofMillis(1))

                val read = cache().read(20)

                read shouldBe PostCacheRead.Expired
                (read as PostCacheRead.Unusable).reason shouldBe "cache_expired"
            }

            test("F-002 값이 없으면 cache_miss다") {
                every { values.get(any()) } returns null

                val read = cache().read(20)

                read shouldBe PostCacheRead.Miss
                (read as PostCacheRead.Unusable).reason shouldBe "cache_miss"
            }

            test("F-002 Redis 오류와 읽을 수 없는 값은 cache_error다") {
                every { values.get(PostStaleCache.key(20)) } throws QueryTimeoutException("Redis 명령 타임아웃")
                every { values.get(PostStaleCache.key(5)) } returns "{\"posts\": 1}"

                val cache = cache()

                cache.read(20) shouldBe PostCacheRead.Error
                cache.read(5) shouldBe PostCacheRead.Error
                PostCacheRead.Error.reason shouldBe "cache_error"
            }

            test("F-002 Compose 테스트가 redis-cli로 넣는 형식(초 단위 cachedAt, 빈 게시글)을 읽는다") {
                every { values.get(PostStaleCache.key(7)) } returns "{\"cachedAt\":\"2026-01-01T00:00:00Z\",\"posts\":[]}"

                val read = cache().read(7)

                read.shouldBeInstanceOf<PostCacheRead.Hit>()
                read.posts shouldBe emptyList()
            }
        }

        test("F-002 설정이 없으면(DISABLED) 쓰지 않고 읽으면 cache_miss다") {
            PostStaleCache.DISABLED.write(20, posts)

            PostStaleCache.DISABLED.read(20) shouldBe PostCacheRead.Miss
            verify(exactly = 0) { redis.opsForValue() }
        }
    })

/** 넘긴 작업을 부른 스레드에서 바로 돈다. */
private class DirectExecutor : AbstractExecutorService() {
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
private class MutableClock(
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
