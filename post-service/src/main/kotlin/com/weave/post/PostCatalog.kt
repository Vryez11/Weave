package com.weave.post

import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 시작할 때 design 결정 1의 식으로 만든 고정 게시글을 메모리에 들고 있는다.
 * 같은 식으로 만들므로 몇 번을 다시 띄워도 데이터가 같다.
 */
@Component
class PostCatalog {
    private val latestFirst: List<Post> =
        (1L..POST_COUNT)
            .map(::fixedPost)
            .sortedWith(compareByDescending<Post> { it.createdAt }.thenByDescending { it.id })

    /** 작성 시각 최신순으로 [size]개. 시각이 같으면 id가 큰 것이 앞에 온다. */
    fun latest(size: Int): List<Post> = latestFirst.take(size)

    companion object {
        const val POST_COUNT = 200L
        const val USER_COUNT = 50L
        private val BASE_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")

        fun fixedPost(id: Long): Post =
            Post(
                id = id,
                authorId = (id - 1) % USER_COUNT + 1,
                title = "게시글 $id",
                content = "게시글 ${id}의 본문",
                createdAt = BASE_TIME.plus(Duration.ofMinutes(id)),
            )
    }
}
