package com.weave.like

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PostLikeRepository : JpaRepository<PostLike, Long> {
    fun findByUserIdAndPostId(
        userId: Long,
        postId: Long,
    ): PostLike?

    fun findAllByUserIdAndPostIdInAndLikedTrue(
        userId: Long,
        postIds: Collection<Long>,
    ): List<PostLike>
}

interface PostLikeCountRepository : JpaRepository<PostLikeCount, PostLikeCountId> {
    /** 게시글마다 칸을 모두 더한 수. 칸이 하나도 없는 게시글은 결과에 없다. */
    @Query(
        """
        SELECT c.id.postId AS postId, SUM(c.likeCount) AS likeCount
        FROM PostLikeCount c
        WHERE c.id.postId IN :postIds
        GROUP BY c.id.postId
        """,
    )
    fun sumByPostIds(
        @Param("postIds") postIds: Collection<Long>,
    ): List<PostCount>
}

interface PostCount {
    val postId: Long
    val likeCount: Long
}
