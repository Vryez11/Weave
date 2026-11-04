package com.weave.feed

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import org.springframework.test.context.DynamicPropertyRegistry
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * feed-service 시나리오 테스트가 쓰는 하위 서비스 흉내 (design 결정 9).
 *
 * WireMock 하나가 하위 서비스 다섯(post, user, like, recommendation, ad)의 경로를 모두 받는다.
 * JVM에 하나만 띄우고 테스트마다 [reset]으로 스텁과 요청 기록을 비운다.
 * 응답 형식은 각 하위 서비스 스펙(post-catalog, user-profile, post-like, recommendation, ad-serving)을 따른다.
 */
object DownstreamWireMock {
    val dependencies = listOf("post", "user", "like", "recommendation", "ad")

    val paths =
        mapOf(
            "post" to "/posts",
            "user" to "/users",
            "like" to "/likes",
            "recommendation" to "/recommendations",
            "ad" to "/ads",
        )

    val server: WireMockServer by lazy { WireMockServer(options().dynamicPort()).apply { start() } }

    private val mapper = jacksonObjectMapper()

    /**
     * 하위 서비스 다섯의 주소를 WireMock으로 준다. [closed]에 든 의존성은 아무것도 떠 있지 않은 포트를 가리킨다.
     *
     * 닫힌 포트는 1번을 쓴다. 특권 포트라 테스트 중에 다른 프로세스가 잡지 않아 연결이 늘 거부된다.
     * 빈 포트를 받아 닫은 뒤 쓰면 그사이 다른 서버(RANDOM_PORT의 Tomcat 등)가 그 포트를 잡을 수 있다.
     */
    fun registerBaseUrls(
        registry: DynamicPropertyRegistry,
        closed: Set<String> = emptySet(),
    ) {
        val closedUrl = "http://localhost:$CLOSED_PORT"
        dependencies.forEach { dependency ->
            registry.add("spring.http.serviceclient.$dependency.base-url") {
                if (dependency in closed) closedUrl else server.baseUrl()
            }
        }
    }

    fun reset() = server.resetAll()

    data class Post(
        val id: Long,
        val authorId: Long,
        val title: String = "게시글 $id",
        val content: String = "게시글 ${id}의 본문",
        val createdAt: String = "2026-01-01T00:%02d:00Z".format(id % 60),
    )

    data class LikeState(
        val postId: Long,
        val likeCount: Long,
        val liked: Boolean,
    )

    val recommendations =
        listOf(
            mapOf("postId" to 100, "title" to "게시글 100", "reason" to "많이 본 게시글"),
            mapOf("postId" to 200, "title" to "게시글 200", "reason" to "많이 본 게시글"),
        )

    val ads =
        listOf(
            mapOf(
                "id" to 1,
                "title" to "광고 1",
                "imageUrl" to "https://cdn.example.com/ads/1.png",
                "landingUrl" to "https://example.com/ads/1",
            ),
        )

    fun profile(id: Long): Map<String, Any> =
        mapOf("id" to id, "nickname" to "사용자$id", "profileImageUrl" to "https://cdn.example.com/users/$id.png")

    fun stubPosts(
        posts: List<Post>,
        delayMs: Int = 0,
    ) = stubGet(
        "post",
        mapOf(
            "posts" to
                posts.map {
                    mapOf(
                        "id" to it.id,
                        "authorId" to it.authorId,
                        "title" to it.title,
                        "content" to it.content,
                        "createdAt" to it.createdAt,
                    )
                },
        ),
        delayMs,
    )

    fun stubUsers(
        ids: List<Long>,
        delayMs: Int = 0,
    ) = stubGet("user", mapOf("users" to ids.map(::profile)), delayMs)

    fun stubLikes(
        likes: List<LikeState>,
        delayMs: Int = 0,
    ) = stubGet(
        "like",
        mapOf("likes" to likes.map { mapOf("postId" to it.postId, "likeCount" to it.likeCount, "liked" to it.liked) }),
        delayMs,
    )

    fun stubRecommendations(delayMs: Int = 0) = stubGet("recommendation", mapOf("recommendations" to recommendations), delayMs)

    fun stubAds(delayMs: Int = 0) = stubGet("ad", mapOf("ads" to ads), delayMs)

    /**
     * 의존성 하나의 조회를 [status]로 실패시킨다. 앞서 둔 정상 스텁보다 나중에 둔 스텁이 이긴다.
     * [retryAfterSeconds]가 있으면 `Retry-After` 헤더를 붙인다(429 흉내).
     */
    fun stubFailure(
        dependency: String,
        status: Int,
        retryAfterSeconds: Int? = null,
    ) {
        val response = aResponse().withStatus(status)
        retryAfterSeconds?.let { response.withHeader("Retry-After", it.toString()) }
        server.stubFor(get(urlPathEqualTo(paths.getValue(dependency))).willReturn(response))
    }

    fun stubToggle(
        postId: Long,
        liked: Boolean,
        likeCount: Long,
    ) {
        server.stubFor(
            post(urlPathEqualTo("/likes/$postId/toggle")).willReturn(
                json(mapOf("postId" to postId, "liked" to liked, "likeCount" to likeCount)),
            ),
        )
    }

    fun stubToggleFailure(
        postId: Long,
        status: Int,
    ) {
        server.stubFor(post(urlPathEqualTo("/likes/$postId/toggle")).willReturn(aResponse().withStatus(status)))
    }

    /** 경로 하나에 온 요청 기록. 쿼리 파라미터는 비교하지 않는다. */
    fun requestsTo(path: String): List<LoggedRequest> = server.findAll(anyRequestedFor(urlPathEqualTo(path)))

    fun allRequests(): List<LoggedRequest> = server.allServeEvents.map { it.request }

    /** `ids=3,4`와 `ids=3&ids=4`를 같은 값으로 읽는다. */
    fun LoggedRequest.longListParam(name: String): List<Long> =
        queryParameter(name)
            .values()
            .flatMap { it.split(",") }
            .filter { it.isNotBlank() }
            .map { it.trim().toLong() }

    private fun stubGet(
        dependency: String,
        body: Map<String, Any>,
        delayMs: Int,
    ) {
        server.stubFor(get(urlPathEqualTo(paths.getValue(dependency))).willReturn(json(body).withFixedDelay(delayMs)))
    }

    private fun json(body: Map<String, Any>) =
        aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(mapper.writeValueAsString(body))

    private const val CLOSED_PORT = 1
}

/** RANDOM_PORT로 뜬 feed-service를 실제 HTTP로 부른다. 테스트마다 새로 만들어도 JDK HttpClient는 하나를 같이 쓴다. */
class FeedHttpClient(
    private val port: Int,
) {
    data class Response(
        val status: Int,
        val body: String,
        val elapsedMs: Long,
    ) {
        fun json(): Map<String, Any?> = responseMapper.readValue<Map<String, Any?>>(body)
    }

    private companion object {
        val responseMapper = jacksonObjectMapper()
        val client: HttpClient = HttpClient.newHttpClient()
    }

    fun getFeed(
        query: String = "",
        userId: String? = "1",
    ): Response = send(HttpRequest.newBuilder(uri("/feed$query")).GET(), userId)

    fun toggleLike(
        postId: String,
        userId: String? = "1",
    ): Response = send(HttpRequest.newBuilder(uri("/feed/likes/$postId/toggle")).POST(HttpRequest.BodyPublishers.noBody()), userId)

    private fun uri(path: String) = URI.create("http://localhost:$port$path")

    private fun send(
        builder: HttpRequest.Builder,
        userId: String?,
    ): Response {
        userId?.let { builder.header("X-User-Id", it) }
        val started = System.nanoTime()
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        return Response(response.statusCode(), response.body(), elapsedMs)
    }
}
