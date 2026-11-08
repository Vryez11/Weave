package com.weave.feed.degradation

import com.weave.feed.client.post.PostResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * 대체 지표의 이름·태그와 세는 때 (add-graceful-degradation design 결정 10, F-002).
 *
 * Prometheus 본문으로 보는 시나리오는 `FeedDegradationMetricsScenarioTest`다. 여기서는 [SimpleMeterRegistry]로 카운터 값을 본다.
 */
class DegradationMetricsTest :
    FunSpec({
        val dependencies = listOf("post", "user", "like", "recommendation", "ad")

        fun SimpleMeterRegistry.count(
            name: String,
            vararg tags: String,
        ): Double = get(name).tags(*tags).counter().count()

        test("F-002 만들 때 영역 결과 20개, post 캐시 읽기 4개, 쓰기 3개 시계열을 0으로 등록한다") {
            val registry = SimpleMeterRegistry()

            DegradationMetrics(registry)

            dependencies.forEach { section ->
                listOf("fresh", "cached", "stale", "missing").forEach { outcome ->
                    registry.count("weave.feed.sections", "section", section, "outcome", outcome) shouldBe 0.0
                }
            }
            listOf("hit", "expired", "miss", "error").forEach { registry.count("weave.post.cache.reads", "outcome", it) shouldBe 0.0 }
            listOf("success", "error", "dropped").forEach { registry.count("weave.post.cache.writes", "outcome", it) shouldBe 0.0 }
            registry.meters.size shouldBe 27
        }

        test("F-002 영역 결과는 의존성마다 그 결과 하나를 센다") {
            val registry = SimpleMeterRegistry()
            val metrics = DegradationMetrics(registry)

            metrics.sections(
                mapOf(
                    "post" to SectionOutcome.STALE,
                    "user" to SectionOutcome.CACHED,
                    "like" to SectionOutcome.MISSING,
                    "recommendation" to SectionOutcome.FRESH,
                    "ad" to SectionOutcome.MISSING,
                ),
            )

            registry.count("weave.feed.sections", "section", "post", "outcome", "stale") shouldBe 1.0
            registry.count("weave.feed.sections", "section", "user", "outcome", "cached") shouldBe 1.0
            registry.count("weave.feed.sections", "section", "like", "outcome", "missing") shouldBe 1.0
            registry.count("weave.feed.sections", "section", "recommendation", "outcome", "fresh") shouldBe 1.0
            registry.count("weave.feed.sections", "section", "ad", "outcome", "missing") shouldBe 1.0
            registry.find("weave.feed.sections").counters().sumOf { it.count() } shouldBe 5.0
        }

        test("F-002 post 캐시 읽기는 읽은 결과의 태그로 센다") {
            val registry = SimpleMeterRegistry()
            val metrics = DegradationMetrics(registry)

            metrics.postCacheRead(PostCacheRead.Hit(emptyList(), Instant.EPOCH))
            metrics.postCacheRead(PostCacheRead.Expired)
            metrics.postCacheRead(PostCacheRead.Miss)
            metrics.postCacheRead(PostCacheRead.Miss)
            metrics.postCacheRead(PostCacheRead.Error)

            registry.count("weave.post.cache.reads", "outcome", "hit") shouldBe 1.0
            registry.count("weave.post.cache.reads", "outcome", "expired") shouldBe 1.0
            registry.count("weave.post.cache.reads", "outcome", "miss") shouldBe 2.0
            registry.count("weave.post.cache.reads", "outcome", "error") shouldBe 1.0
        }

        context("post 캐시 쓰기") {
            val posts =
                listOf(
                    PostResponse(
                        id = 10,
                        authorId = 3,
                        title = "게시글 10",
                        content = "본문",
                        createdAt = Instant.parse("2025-12-31T00:00:00Z"),
                    ),
                )
            val values = mockk<ValueOperations<String, String>>(relaxed = true)
            val redis = mockk<StringRedisTemplate> { every { opsForValue() } returns values }

            fun cache(
                registry: SimpleMeterRegistry,
                executor: AbstractExecutorService,
            ) = PostStaleCache.create(
                redis,
                jacksonObjectMapper(),
                Clock.systemUTC(),
                Duration.ofMinutes(10),
                Duration.ofSeconds(1),
                executor,
                DegradationMetrics(registry),
            )

            test("F-002 Redis에 쓰면 success, 실패하면 error로 센다") {
                val registry = SimpleMeterRegistry()
                val cache = cache(registry, RunningExecutor())

                cache.write(20, posts)
                every { values.set(any(), any(), any<Duration>()) } throws QueryTimeoutException("timeout")
                cache.write(21, posts)

                registry.count("weave.post.cache.writes", "outcome", "success") shouldBe 1.0
                registry.count("weave.post.cache.writes", "outcome", "error") shouldBe 1.0
                registry.count("weave.post.cache.writes", "outcome", "dropped") shouldBe 0.0
            }

            test("F-002 쓰기 대기열이 차서 버리면 dropped로 센다") {
                val registry = SimpleMeterRegistry()

                cache(registry, RejectingExecutor()).write(20, posts)

                registry.count("weave.post.cache.writes", "outcome", "dropped") shouldBe 1.0
                registry.count("weave.post.cache.writes", "outcome", "success") shouldBe 0.0
            }
        }
    })

/** 넘긴 작업을 부른 스레드에서 바로 돈다. */
private class RunningExecutor : StubExecutor() {
    override fun execute(command: Runnable) = command.run()
}

/** 대기열이 찬 실행기처럼 늘 거절한다. */
private class RejectingExecutor : StubExecutor() {
    override fun execute(command: Runnable): Unit = throw RejectedExecutionException("대기열이 찼다")
}

private abstract class StubExecutor : AbstractExecutorService() {
    override fun shutdown() = Unit

    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

    override fun isShutdown() = false

    override fun isTerminated() = false

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ) = true
}
