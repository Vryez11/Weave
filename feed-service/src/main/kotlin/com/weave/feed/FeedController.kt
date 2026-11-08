package com.weave.feed

import com.weave.feed.client.like.ToggleResponse
import com.weave.feed.degradation.PostsUnavailableException
import com.weave.feed.gateway.LikeGateway
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 클라이언트가 부르는 유일한 진입점 (`feed-aggregation` 스펙, design 결정 2).
 *
 * 요청 검증은 하위 서비스를 부르기 전에 여기서 끝낸다. 피드 조회의 하위 서비스 실패는 `FeedAssembler`가 등급대로 대체하고, 게시글을
 * 대체할 수 없을 때만 503이다(add-graceful-degradation design 결정 8). 토글 실패는 따로 처리하지 않아 500이 된다(결정 5).
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

    /** like-service의 토글 결과를 그대로 돌려준다. */
    @PostMapping("/feed/likes/{postId}/toggle")
    fun toggleLike(
        @RequestHeader(USER_ID_HEADER) userId: Long,
        @PathVariable postId: Long,
    ): ToggleResponse {
        requirePositive(USER_ID_HEADER, userId)
        requirePositive("postId", postId)
        return likeGateway.toggle(userId, postId)
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

    companion object {
        private const val USER_ID_HEADER = "X-User-Id"
        private const val DEFAULT_SIZE = 20
        private val SIZE_RANGE = 1..50
    }
}
