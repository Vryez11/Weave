package com.weave.post

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class PostController(
    private val postCatalog: PostCatalog,
) {
    @GetMapping("/posts")
    fun latestPosts(
        @RequestParam(defaultValue = "$DEFAULT_SIZE") size: Int,
    ): PostsResponse {
        if (size !in SIZE_RANGE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size는 ${SIZE_RANGE.first}~${SIZE_RANGE.last}만 받는다: $size")
        }
        return PostsResponse(postCatalog.latest(size))
    }

    data class PostsResponse(
        val posts: List<Post>,
    )

    companion object {
        private const val DEFAULT_SIZE = 20
        private val SIZE_RANGE = 1..50
    }
}
