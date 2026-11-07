package com.weave.feed.degradation

import com.weave.feed.client.post.PostResponse
import io.lettuce.core.ClientOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * post가 실패했을 때 게시글을 채우는 stale 캐시 (add-graceful-degradation design 결정 4, 5).
 *
 * - **키·값:** `size`마다 키 하나([key])에 `{"cachedAt": ..., "posts": [...]}`(post-service 응답 항목 그대로)를 둔다. Redis TTL은
 *   [maxStaleness]와 같고, stale 판단은 값의 `cachedAt`과 [Clock]으로 한다(design 결정 11).
 * - **읽기([read]):** post가 실패했을 때 요청 스레드에서 동기로 읽는다. 기다리는 상한은 Redis 명령 타임아웃(`spring.data.redis.timeout`)이다.
 *   Redis 오류와 읽을 수 없는 값은 모두 [PostCacheRead.Error]다.
 * - **쓰기([write]):** post가 성공할 때마다 부르지만, 같은 키는 [writeInterval]에 한 번만 전용 스레드 하나([writer], 대기열
 *   [WRITE_QUEUE_CAPACITY])로 넘기고 바로 돌아간다. 대기열이 차면 버린다. Redis가 멈춰도 쓰기 스레드만 묶이고 피드는 기다리지 않는다.
 * - 읽기·쓰기 실패는 로그 줄을 남기지 않고 지표만 센다(design 결정 4, 10). post를 대체하지 못한 요청은 `FeedAssembler`가 대체 로그를 남긴다.
 */
class PostStaleCache private constructor(
    private val redis: StringRedisTemplate?,
    private val jsonMapper: ObjectMapper?,
    private val clock: Clock,
    private val maxStaleness: Duration,
    private val writeInterval: Duration,
    private val writer: ExecutorService?,
    private val metrics: DegradationMetrics,
) : AutoCloseable {
    /** 키마다 마지막으로 쓰기를 넘긴 시각. */
    private val lastSubmitted = ConcurrentHashMap<Int, Instant>()

    /** [size] 요청의 캐시를 읽는다. [maxStaleness] 안의 값이면 [PostCacheRead.Hit]이다. */
    fun read(size: Int): PostCacheRead {
        if (redis == null || jsonMapper == null) return PostCacheRead.Miss
        val cached =
            try {
                val value = redis.opsForValue().get(key(size)) ?: return PostCacheRead.Miss
                jsonMapper.readValue(value, CachedPosts::class.java)
            } catch (_: RuntimeException) {
                return PostCacheRead.Error
            }
        val age = Duration.between(cached.cachedAt, clock.instant())
        return if (age <= maxStaleness) PostCacheRead.Hit(cached.posts, cached.cachedAt) else PostCacheRead.Expired
    }

    /**
     * post-service가 [size] 요청에 준 [posts]를 지금 시각으로 남긴다. 같은 키에 [writeInterval] 안에 넘긴 쓰기가 있으면 넘기지 않는다.
     * 쓰기는 [writer]에서 하고 기다리지 않는다.
     */
    fun write(
        size: Int,
        posts: List<PostResponse>,
    ) {
        if (writer == null) return
        val now = clock.instant()
        var due = false
        lastSubmitted.compute(size) { _, last ->
            if (last == null || Duration.between(last, now) >= writeInterval) {
                due = true
                now
            } else {
                last
            }
        }
        if (!due) return
        try {
            writer.execute { store(size, CachedPosts(now, posts)) }
        } catch (_: RejectedExecutionException) {
            // 대기열이 찼다. Redis가 쓰기 간격보다 오래 응답하지 않는 것이라 버린다(design 결정 5)
            metrics.postCacheWrite(PostCacheWrite.DROPPED)
        }
    }

    private fun store(
        size: Int,
        value: CachedPosts,
    ) {
        try {
            redis?.opsForValue()?.set(key(size), jsonMapper!!.writeValueAsString(value), maxStaleness)
            metrics.postCacheWrite(PostCacheWrite.SUCCESS)
        } catch (_: RuntimeException) {
            // 쓰기 실패는 피드에 영향이 없다. 다음 정상 응답이 다시 쓴다
            metrics.postCacheWrite(PostCacheWrite.ERROR)
        }
    }

    override fun close() {
        writer?.shutdownNow()
    }

    /** 캐시 값. 시각은 응답 DTO와 같은 ISO-8601 UTC다. */
    data class CachedPosts(
        val cachedAt: Instant,
        val posts: List<PostResponse>,
    )

    companion object {
        /** 쓰기 대기열. `size` 범위(1~50)만큼이다. 쓰기 간격 때문에 정상이면 키 수보다 많이 쌓이지 않는다(design 결정 5). */
        const val WRITE_QUEUE_CAPACITY = 50

        /** 읽지도 쓰지도 않는다. post가 실패하면 늘 [PostCacheRead.Miss]다. */
        val DISABLED = PostStaleCache(null, null, Clock.systemUTC(), Duration.ZERO, Duration.ZERO, null, DegradationMetrics.DISABLED)

        fun key(size: Int): String = "weave:feed:posts:latest:v1:size:$size"

        /** 쓰기 결과는 [metrics]의 `weave.post.cache.writes`로 센다. 읽기 결과는 읽은 쪽(`FeedAssembler`)이 센다. */
        fun create(
            redis: StringRedisTemplate,
            jsonMapper: ObjectMapper,
            clock: Clock,
            maxStaleness: Duration,
            writeInterval: Duration,
            writer: ExecutorService = newWriter(),
            metrics: DegradationMetrics = DegradationMetrics.DISABLED,
        ): PostStaleCache = PostStaleCache(redis, jsonMapper, clock, maxStaleness, writeInterval, writer, metrics)

        /** 스레드 하나, 대기열 [WRITE_QUEUE_CAPACITY]. 차면 [RejectedExecutionException]으로 거절한다. */
        fun newWriter(): ExecutorService =
            ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(WRITE_QUEUE_CAPACITY)) { task ->
                Thread(task, "post-cache-writer").apply { isDaemon = true }
            }
    }
}

/** post 캐시 읽기 결과. [tag]가 지표 `weave.post.cache.reads`의 `outcome` 태그다(design 결정 10). */
sealed class PostCacheRead(
    val tag: String,
) {
    /** stale 허용 시간 안의 게시글. [cachedAt]은 post-service에서 받은 시각이다. */
    class Hit(
        val posts: List<PostResponse>,
        val cachedAt: Instant,
    ) : PostCacheRead("hit")

    /** 대체할 수 없다. [reason]이 대체 로그 `fallback_applied`의 `reason`이다(design 결정 9). */
    sealed class Unusable(
        tag: String,
        val reason: String,
    ) : PostCacheRead(tag)

    data object Expired : Unusable("expired", "cache_expired")

    data object Miss : Unusable("miss", "cache_miss")

    data object Error : Unusable("error", "cache_error")

    companion object {
        /** 모든 [tag]. 지표는 시작할 때 이 값마다 0으로 등록한다. */
        val TAGS = listOf("hit", Expired.tag, Miss.tag, Error.tag)
    }
}

/** post가 실패했고 stale 캐시로도 대체할 수 없다. `FeedController`가 503으로 바꾼다(design 결정 8). */
class PostsUnavailableException(
    val reason: String,
) : RuntimeException("post를 대체할 수 없다: $reason")

/**
 * post stale 캐시 설정 `weave.post-cache.*` (design 결정 4, 5, 14).
 *
 * 값은 근거와 함께 tasks 4.3에서 main `application.yml`에 넣는다. 그때까지 둘 중 하나라도 없으면 캐시를 쓰지 않는다([PostStaleCache.DISABLED]).
 */
@ConfigurationProperties("weave.post-cache")
data class PostCacheProperties(
    /** post가 실패했을 때 대체에 쓸 수 있는 캐시의 나이 상한. Redis TTL도 같다. */
    val maxStaleness: Duration? = null,
    /** 같은 키에 쓰기를 넘기는 최소 간격. */
    val writeInterval: Duration? = null,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PostCacheProperties::class)
class PostStaleCacheConfig {
    @Bean
    fun postStaleCache(
        properties: PostCacheProperties,
        redis: StringRedisTemplate,
        jsonMapper: ObjectMapper,
        clock: Clock,
        metrics: DegradationMetrics,
    ): PostStaleCache {
        val maxStaleness = properties.maxStaleness ?: return PostStaleCache.DISABLED
        val writeInterval = properties.writeInterval ?: return PostStaleCache.DISABLED
        return PostStaleCache.create(redis, jsonMapper, clock, maxStaleness, writeInterval, metrics = metrics)
    }

    /**
     * 연결이 끊긴 것을 아는 동안의 명령은 대기열에 쌓지 않고 바로 실패한다(design 결정 4). 기본값은 재연결까지 쌓아 두었다가 명령 타임아웃이
     * 지나서야 실패한다.
     */
    @Bean
    fun rejectCommandsWhenDisconnected(): LettuceClientOptionsBuilderCustomizer =
        LettuceClientOptionsBuilderCustomizer { it.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS) }
}
