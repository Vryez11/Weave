package com.weave.like

import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

/** 실제 HTTP 포트로 like-service를 부르는 테스트 클라이언트. 구현 코드를 참조하지 않는다 (F-003). */
class LikeHttp(
    private val port: Int,
) {
    data class Response(
        val status: Int,
        val body: String,
    ) {
        fun json(): Map<String, Any?> = mapper.readValue(body)
    }

    fun putLike(
        postId: Any,
        userId: Any? = 1,
        liked: Boolean = true,
        version: Long = 1,
    ): Response = putBody(postId, """{"liked":$liked,"version":$version}""", userId)

    /** [body]가 null이면 본문 없이 보낸다. */
    fun putBody(
        postId: Any,
        body: String?,
        userId: Any? = 1,
    ): Response {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        return send(request("/likes/$postId", userId).header("Content-Type", "application/json").PUT(publisher))
    }

    fun postToggle(postId: Any): Response = send(request("/likes/$postId/toggle", 1).POST(HttpRequest.BodyPublishers.noBody()))

    /** 게시글 하나의 조회 결과. */
    @Suppress("UNCHECKED_CAST")
    fun like(
        postId: Long,
        userId: Any = 1,
    ): Map<String, Any?> {
        val likes = send(request("/likes?postIds=$postId", userId).GET()).json()["likes"] as List<Map<String, Any?>>
        return likes.single()
    }

    /** 불일치 검사 결과. 엔드포인트가 노출돼 있어야 한다. */
    @Suppress("UNCHECKED_CAST")
    fun mismatches(postId: Long? = null): List<Map<String, Any?>> {
        val query = postId?.let { "?postId=$it" } ?: ""
        val response = send(request("/actuator/likeconsistency$query", null).GET())
        check(response.status == 200) { "불일치 검사 응답 ${response.status}: ${response.body}" }
        return response.json()["mismatches"] as List<Map<String, Any?>>
    }

    private fun request(
        path: String,
        userId: Any?,
    ): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).apply {
            userId?.let { header("X-User-Id", it.toString()) }
        }

    private fun send(builder: HttpRequest.Builder): Response {
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), response.body())
    }

    companion object {
        private val client: HttpClient = HttpClient.newHttpClient()
        private val mapper = jacksonObjectMapper()
    }
}

/** 띄운 애플리케이션의 포트로 클라이언트를 만든다. */
fun ApplicationContext.likeHttp(): LikeHttp = LikeHttp(environment.getRequiredProperty("local.server.port").toInt())

fun ApplicationContext.jdbc(): JdbcTemplate = JdbcTemplate(getBean(DataSource::class.java))

/** 컨테이너가 JVM에 하나라 테스트끼리 데이터가 남는다. */
fun ApplicationContext.clearLikes() {
    val jdbc = jdbc()
    jdbc.update("DELETE FROM post_like")
    jdbc.update("DELETE FROM post_like_count")
}

/** `liked` 상태인 행 수. */
fun ApplicationContext.likedRows(postId: Long): Long =
    jdbc().queryForObject("SELECT COUNT(*) FROM post_like WHERE post_id = ? AND liked = TRUE", Long::class.java, postId)!!

/** [block]을 스레드 [times]개에서 한꺼번에 시작하고 결과를 모은다. */
fun <T> concurrently(
    times: Int,
    block: (Int) -> T,
): List<T> {
    val pool = Executors.newFixedThreadPool(times)
    try {
        val start = CountDownLatch(1)
        val futures =
            (0 until times).map { index ->
                pool.submit<T> {
                    start.await()
                    block(index)
                }
            }
        start.countDown()
        return futures.map { it.get() }
    } finally {
        pool.shutdown()
    }
}
