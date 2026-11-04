package com.weave.like

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/** 게시글의 좋아요 수. 토글이 읽고 고쳐 저장한다(design 결정 6). */
@Entity
@Table(name = "post_like_count")
class PostLikeCount(
    @Id
    @Column(name = "post_id")
    val postId: Long,
    @Column(name = "like_count", nullable = false)
    var likeCount: Long,
)
