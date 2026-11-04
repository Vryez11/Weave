package com.weave.like

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** 사용자 한 명이 게시글 하나를 누른 기록. `(user_id, post_id)`는 유니크다(design 결정 6). */
@Entity
@Table(name = "post_like")
class PostLike(
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "post_id", nullable = false)
    val postId: Long,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
