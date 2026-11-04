package com.weave.feed.client.post

import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import java.time.Instant

/** post-service의 `post-catalog` API. */
@HttpExchange
interface PostClient {
    @GetExchange("/posts")
    fun getLatestPosts(
        @RequestParam size: Int,
    ): PostsResponse
}

data class PostsResponse(
    val posts: List<PostResponse>,
)

data class PostResponse(
    val id: Long,
    val authorId: Long,
    val title: String,
    val content: String,
    val createdAt: Instant,
)
