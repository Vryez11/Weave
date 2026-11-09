package com.weave.feed.client.like

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PutExchange

/** like-service의 `post-like` API. 사용자는 `X-User-Id` 헤더로 넘긴다. */
@HttpExchange
interface LikeClient {
    @GetExchange("/likes")
    fun getLikes(
        @RequestHeader("X-User-Id") userId: Long,
        @RequestParam postIds: List<Long>,
    ): LikesResponse

    @PutExchange("/likes/{postId}")
    fun setLike(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable postId: Long,
        @RequestBody request: SetLikeRequest,
    ): LikeStateResponse
}

data class LikesResponse(
    val likes: List<LikeResponse>,
)

data class LikeResponse(
    val postId: Long,
    val likeCount: Long,
    val liked: Boolean,
)

data class SetLikeRequest(
    val liked: Boolean,
    val version: Long,
)

data class LikeStateResponse(
    val postId: Long,
    val liked: Boolean,
    val likeCount: Long,
)
