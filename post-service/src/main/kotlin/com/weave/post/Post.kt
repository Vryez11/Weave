package com.weave.post

import java.time.Instant

data class Post(
    val id: Long,
    val authorId: Long,
    val title: String,
    val content: String,
    val createdAt: Instant,
)
