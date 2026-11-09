package com.weave.like

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface PostLikeRepository : JpaRepository<PostLike, Long> {
    fun findByUserIdAndPostId(
        userId: Long,
        postId: Long,
    ): PostLike?

    fun findAllByUserIdAndPostIdInAndLikedTrue(
        userId: Long,
        postIds: Collection<Long>,
    ): List<PostLike>

    /**
     * 행을 만들거나 있는 행에 X 잠금을 잡는다. `INSERT IGNORE`는 중복일 때 공유 잠금이라 뒤이은 갱신에서 교착한다
     * (add-idempotent-like design 결정 4).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
        INSERT INTO post_like (user_id, post_id, liked, version, created_at)
        VALUES (:userId, :postId, FALSE, 0, :createdAt)
        ON DUPLICATE KEY UPDATE version = version
        """,
        nativeQuery = true,
    )
    fun lockOrCreate(
        @Param("userId") userId: Long,
        @Param("postId") postId: Long,
        @Param("createdAt") createdAt: Instant,
    )

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PostLike p SET p.liked = :liked, p.version = :version WHERE p.userId = :userId AND p.postId = :postId")
    fun updateState(
        @Param("userId") userId: Long,
        @Param("postId") postId: Long,
        @Param("liked") liked: Boolean,
        @Param("version") version: Long,
    )
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

    /** 칸에 [delta]를 더한다. 칸이 없으면 [delta]로 만든다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
        INSERT INTO post_like_count (post_id, shard, like_count)
        VALUES (:postId, :shard, :delta)
        ON DUPLICATE KEY UPDATE like_count = like_count + :delta
        """,
        nativeQuery = true,
    )
    fun addAtomically(
        @Param("postId") postId: Long,
        @Param("shard") shard: Int,
        @Param("delta") delta: Long,
    )
}

interface PostCount {
    val postId: Long
    val likeCount: Long
}
