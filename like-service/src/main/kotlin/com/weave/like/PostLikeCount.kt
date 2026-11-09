package com.weave.like

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable

/** 게시글 좋아요 수의 칸 하나. 게시글의 수는 칸을 모두 더한 값이다(add-idempotent-like design 결정 6). */
@Entity
@Table(name = "post_like_count")
class PostLikeCount(
    @EmbeddedId
    val id: PostLikeCountId,
    @Column(name = "like_count", nullable = false)
    var likeCount: Long,
)

@Embeddable
data class PostLikeCountId(
    @Column(name = "post_id")
    val postId: Long,
    @Column(name = "shard")
    val shard: Int,
) : Serializable
