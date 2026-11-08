package com.weave.feed

import com.weave.feed.DownstreamWireMock.LikeState
import com.weave.feed.DownstreamWireMock.Post
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * post stale 캐시 시나리오가 쓰는 Redis (add-graceful-degradation design 결정 15).
 *
 * - **컨테이너:** Testcontainers `GenericContainer`(`redis:8.2.2`, Compose와 같은 태그)를 JVM에 하나만 띄우고 클래스 사이에 같이 쓴다.
 *   JVM이 끝날 때 Testcontainers가 치운다. 시나리오마다 [flush]로 비운다.
 * - **주소:** [registerAddress]를 `@DynamicPropertySource`에서 부른다. 테스트 기본 설정의 `localhost:1`(죽은 Redis)을 덮는다.
 * - **명령:** 컨테이너 안의 `redis-cli`로 부른다(feed-service의 연결과 따로다). `CLIENT PAUSE`를 보낸 쪽이 같이 멈추지 않는다.
 * - **키:** design 결정 4의 `weave:feed:posts:latest:v1:size:{size}`([key]). 캐시 쓰기는 비동기라([awaitKey]) 쓰기가 끝났는지는 키로 본다.
 */
object PostCacheRedis {
    private const val IMAGE = "redis:8.2.2"
    private const val PORT = 6379

    private val container: RedisContainer by lazy { RedisContainer().apply { start() } }

    fun registerAddress(registry: DynamicPropertyRegistry) {
        registry.add("spring.data.redis.host") { container.host }
        registry.add("spring.data.redis.port") { container.getMappedPort(PORT) }
    }

    fun key(size: Int): String = "weave:feed:posts:latest:v1:size:$size"

    fun flush() {
        cli("FLUSHDB")
    }

    fun exists(key: String): Boolean = cli("EXISTS", key) == "1"

    fun set(
        key: String,
        value: String,
    ) {
        cli("SET", key, value)
    }

    /** Redis가 [duration] 동안 모든 클라이언트의 명령(읽기·쓰기)을 처리하지 않는다. 바로 돌아온다. */
    fun pause(duration: Duration) {
        cli("CLIENT", "PAUSE", duration.toMillis().toString(), "ALL")
    }

    /** [key]가 생길 때까지 [timeout]만큼 기다린다. 생기면 true다. */
    fun awaitKey(
        key: String,
        timeout: Duration = Duration.ofSeconds(3),
    ): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (exists(key)) return true
            Thread.sleep(50)
        }
        return exists(key)
    }

    /**
     * feed-service의 Redis 연결과 피드 경로를 데운다. 시간을 재는 시나리오가 첫 연결(Lettuce 핸드셰이크, 클래스 로딩)과 WireMock의 첫
     * 응답을 재지 않게 한다.
     *
     * 하위 서비스를 모두 정상으로 두고 `size` 1~[WARM_UP_SIZES]로 차례로 피드를 불러, 그 크기의 캐시가 써질 때까지 기다린다. 하나라도 써지면
     * 연결이 맺어진 것이다. 크기마다 키가 달라 쓰기 간격(`weave.post-cache.write-interval`)에 걸리지 않는다. 쓰기가 하나도 안 되면 실패한다.
     * 끝나면 WireMock 스텁과 기록, 캐시를 모두 비운다.
     */
    fun warmUp(feed: FeedHttpClient) {
        val wireMock = DownstreamWireMock
        wireMock.reset()
        wireMock.stubPosts(WARM_UP_POSTS)
        wireMock.stubUsers(WARM_UP_POSTS.map { it.authorId }.distinct())
        wireMock.stubLikes(WARM_UP_POSTS.map { LikeState(postId = it.id, likeCount = 0, liked = false) })
        wireMock.stubRecommendations()
        wireMock.stubAds()

        val written =
            (1..WARM_UP_SIZES).any { size ->
                feed.getFeed("?size=$size")
                awaitKey(key(size), Duration.ofSeconds(2))
            }
        check(written) { "피드를 ${WARM_UP_SIZES}번 불러도 post 캐시 쓰기(${key(1)} 등)가 Redis에 남지 않아 연결을 데우지 못했다" }

        wireMock.reset()
        flush()
    }

    private fun cli(vararg args: String): String {
        val result = container.execInContainer("redis-cli", *args)
        check(result.exitCode == 0) { "redis-cli ${args.joinToString(" ")} 실패(${result.exitCode}): ${result.stderr}" }
        return result.stdout.trim()
    }

    private const val WARM_UP_SIZES = 5

    private val WARM_UP_POSTS = listOf(Post(id = 90, authorId = 9))

    private class RedisContainer : GenericContainer<RedisContainer>(DockerImageName.parse(IMAGE)) {
        init {
            withExposedPorts(PORT)
        }
    }
}
