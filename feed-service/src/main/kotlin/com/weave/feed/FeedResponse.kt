package com.weave.feed

import java.time.Instant

/** `GET /feed` 응답 (`feed-aggregation` 스펙). 하위 서비스 응답 DTO(`com.weave.feed.client`)와 섞지 않는다. */
data class FeedResponse(
    val posts: List<FeedPost>,
    val recommendations: List<FeedRecommendation>,
    val ads: List<FeedAd>,
)

data class FeedPost(
    val id: Long,
    val title: String,
    val content: String,
    val createdAt: Instant,
    /** user-service가 프로필을 돌려주지 않으면 null이다. */
    val author: FeedAuthor?,
    val like: FeedLike,
)

data class FeedAuthor(
    val id: Long,
    val nickname: String,
    val profileImageUrl: String,
)

data class FeedLike(
    val count: Long,
    val myLike: MyLike,
)

enum class MyLike {
    LIKED,
    NOT_LIKED,
}

data class FeedRecommendation(
    val postId: Long,
    val title: String,
    val reason: String,
)

data class FeedAd(
    val id: Long,
    val title: String,
    val imageUrl: String,
    val landingUrl: String,
)
