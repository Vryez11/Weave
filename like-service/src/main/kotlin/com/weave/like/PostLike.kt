package com.weave.like

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 사용자 한 명의 게시글 하나에 대한 좋아요 상태. `(user_id, post_id)`는 유니크다.
 *
 * 취소해도 행을 지우지 않고 `liked = false`로 남긴다. 반영한 가장 큰 `version`을 기억해야 늦게 온 요청을 가려낼 수 있다
 * (add-idempotent-like design 결정 3).
 */
@Entity
@Table(name = "post_like")
class PostLike(
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "post_id", nullable = false)
    val postId: Long,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "liked", nullable = false)
    val liked: Boolean = true,
    @Column(name = "version", nullable = false)
    val version: Long = 0,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
