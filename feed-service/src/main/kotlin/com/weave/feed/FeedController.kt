package com.weave.feed

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 클라이언트가 부르는 유일한 진입점 (`feed-aggregation` 스펙, design 결정 2).
 *
 * 요청 검증은 하위 서비스를 부르기 전에 여기서 끝낸다. 하위 서비스가 실패하면 따로 처리하지 않아 500이 된다(결정 5).
 */
@RestController
class FeedController(
    private val feedAssembler: FeedAssembler,
) {
    @GetMapping("/feed")
    fun feed(
        @RequestHeader(USER_ID_HEADER) userId: Long,
        @RequestParam(defaultValue = "$DEFAULT_SIZE") size: Int,
    ): FeedResponse {
        if (userId < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "${USER_ID_HEADER}는 양의 정수여야 한다: $userId")
        }
        if (size !in SIZE_RANGE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size는 ${SIZE_RANGE.first}~${SIZE_RANGE.last}만 받는다: $size")
        }
        return feedAssembler.assemble(userId, size)
    }

    companion object {
        private const val USER_ID_HEADER = "X-User-Id"
        private const val DEFAULT_SIZE = 20
        private val SIZE_RANGE = 1..50
    }
}
