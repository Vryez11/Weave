package com.weave.like

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class LikeController(
    private val likeService: LikeService,
) {
    @GetMapping("/likes")
    fun likes(
        @RequestHeader(USER_ID_HEADER) userId: Long,
        @RequestParam postIds: List<Long>,
    ): LikesResponse {
        requirePositive(USER_ID_HEADER, userId)
        if (postIds.size !in POST_IDS_SIZE_RANGE) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "postIds는 ${POST_IDS_SIZE_RANGE.first}~${POST_IDS_SIZE_RANGE.last}개만 받는다: ${postIds.size}",
            )
        }
        return LikesResponse(likeService.findAll(userId, postIds))
    }

    @PostMapping("/likes/{postId}/toggle")
    fun toggle(
        @RequestHeader(USER_ID_HEADER) userId: Long,
        @PathVariable postId: Long,
    ): LikeStatus {
        requirePositive(USER_ID_HEADER, userId)
        requirePositive("postId", postId)
        return likeService.toggle(userId, postId)
    }

    private fun requirePositive(
        name: String,
        value: Long,
    ) {
        if (value < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "${name}는 양의 정수여야 한다: $value")
        }
    }

    data class LikesResponse(
        val likes: List<LikeStatus>,
    )

    companion object {
        private const val USER_ID_HEADER = "X-User-Id"
        private val POST_IDS_SIZE_RANGE = 1..100
    }
}
