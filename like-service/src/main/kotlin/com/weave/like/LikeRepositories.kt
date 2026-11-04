package com.weave.like

import org.springframework.data.jpa.repository.JpaRepository

interface PostLikeRepository : JpaRepository<PostLike, Long> {
    fun findByUserIdAndPostId(
        userId: Long,
        postId: Long,
    ): PostLike?

    fun findAllByUserIdAndPostIdIn(
        userId: Long,
        postIds: Collection<Long>,
    ): List<PostLike>
}

interface PostLikeCountRepository : JpaRepository<PostLikeCount, Long>
