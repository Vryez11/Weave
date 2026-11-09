package com.weave.feed

import com.weave.feed.client.like.LikeStateResponse
import com.weave.feed.client.like.SetLikeRequest
import com.weave.feed.degradation.PostsUnavailableException
import com.weave.feed.gateway.LikeGateway
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 클라이언트가 부르는 유일한 진입점 (`feed-aggregation` 스펙, design 결정 2).
 *
 * 요청 검증은 하위 서비스를 부르기 전에 여기서 끝낸다. 피드 조회의 하위 서비스 실패는 `FeedAssembler`가 등급대로 대체하고, 게시글을
 * 대체할 수 없을 때만 503이다(add-graceful-degradation design 결정 8). 좋아요 상태 변경 실패는 따로 처리하지 않아 500이 된다(결정 5). 클라이언트는 같은 `version`으로 다시 보낸다.
 */
@RestController
class FeedController(
    private val feedAssembler: FeedAssembler,
    private val likeGateway: LikeGateway,
) {
    @GetMapping("/feed")
    fun feed(
        @RequestHeader(USER_ID_HEADER) userId: Long,
        @RequestParam(defaultValue = "$DEFAULT_SIZE") size: Int,
    ): FeedResponse {
        requirePositive(USER_ID_HEADER, userId)
        if (size !in SIZE_RANGE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size는 ${SIZE_RANGE.first}~${SIZE_RANGE.last}만 받는다: $size")
        }
        return feedAssembler.assemble(userId, size)
    }

    /** like-service의 상태 지정 결과를 그대로 돌려준다. 다시 부르지 않는다(add-idempotent-like design 결정 5). */
    @PutMapping("/feed/likes/{postId}")
    fun setLike(
        @RequestHeader(USER_ID_HEADER) userId: Long,
        @PathVariable postId: Long,
        @RequestBody body: LikeBody,
    ): LikeStateResponse {
        requirePositive(USER_ID_HEADER, userId)
        requirePositive("postId", postId)
        val liked = body.liked ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "liked가 필요하다")
        val version = body.version ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "version이 필요하다")
        requirePositive("version", version)
        return likeGateway.setLike(userId, postId, SetLikeRequest(liked, version))
    }

    /** 이유는 클라이언트에 주지 않는다(대체 로그에 있다). `X-Trace-Id`는 observability 필터가 단다. */
    @ExceptionHandler(PostsUnavailableException::class)
    fun postsUnavailable(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to "posts_unavailable"))

    private fun requirePositive(
        name: String,
        value: Long,
    ) {
        if (value < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "${name}는 양의 정수여야 한다: $value")
        }
    }

    /** 빠진 필드를 0·false로 채우지 않고 400으로 돌려주려고 null을 받는다. */
    data class LikeBody(
        val liked: Boolean?,
        val version: Long?,
    )

    companion object {
        private const val USER_ID_HEADER = "X-User-Id"
        private const val DEFAULT_SIZE = 20
        private val SIZE_RANGE = 1..50
    }
}
