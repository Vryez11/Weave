package com.weave.like

import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * `/actuator/likeconsistency`. 저장된 좋아요 수와 `liked` 상태인 행 수가 다른 게시글을 돌려준다(add-idempotent-like design 결정 7).
 *
 * 값을 고치지 않는다. 한쪽에만 있는 게시글도 찾도록 게시글 ID를 양쪽에서 모은다. 전체 검사가 무거울 수 있어 [LIMIT]건으로 자른다.
 */
@Endpoint(id = "likeconsistency")
@Component
class LikeConsistencyEndpoint(
    private val jdbc: JdbcClient,
) {
    @ReadOperation
    fun check(postId: Long?): LikeConsistency {
        val mismatches =
            jdbc
                .sql(
                    """
                    SELECT ids.post_id, COALESCE(counted.total, 0) AS like_count, COALESCE(rows_.liked_rows, 0) AS liked_rows
                    FROM (
                        SELECT post_id FROM post_like_count WHERE (:postId IS NULL OR post_id = :postId)
                        UNION
                        SELECT post_id FROM post_like WHERE liked = TRUE AND (:postId IS NULL OR post_id = :postId)
                    ) ids
                    LEFT JOIN (
                        SELECT post_id, SUM(like_count) AS total FROM post_like_count
                        WHERE (:postId IS NULL OR post_id = :postId) GROUP BY post_id
                    ) counted ON counted.post_id = ids.post_id
                    LEFT JOIN (
                        SELECT post_id, COUNT(*) AS liked_rows FROM post_like
                        WHERE liked = TRUE AND (:postId IS NULL OR post_id = :postId) GROUP BY post_id
                    ) rows_ ON rows_.post_id = ids.post_id
                    WHERE COALESCE(counted.total, 0) <> COALESCE(rows_.liked_rows, 0)
                    ORDER BY ids.post_id
                    LIMIT $LIMIT
                    """.trimIndent(),
                ).param("postId", postId)
                .query { rs, _ -> LikeMismatch(rs.getLong("post_id"), rs.getLong("like_count"), rs.getLong("liked_rows")) }
                .list()
        return LikeConsistency(mismatches)
    }

    data class LikeConsistency(
        val mismatches: List<LikeMismatch>,
    )

    data class LikeMismatch(
        val postId: Long,
        val likeCount: Long,
        val likedRows: Long,
    )

    private companion object {
        const val LIMIT = 100
    }
}
