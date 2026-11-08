package com.weave.feed

import java.time.Instant

/** `GET /feed` 응답 (`feed-aggregation` 스펙). 하위 서비스 응답 DTO(`com.weave.feed.client`)와 섞지 않는다. */
data class FeedResponse(
    val posts: List<FeedPost>,
    val recommendations: List<FeedRecommendation>,
    val ads: List<FeedAd>,
    /** 늘 있다. 모든 하위 서비스가 정상이면 두 목록이 비어 있다 (add-graceful-degradation design 결정 2). */
    val degradation: Degradation,
)

data class FeedPost(
    val id: Long,
    val title: String,
    val content: String,
    val createdAt: Instant,
    /** user-service가 정상으로 응답했지만 프로필을 돌려주지 않으면 null이다. 프로필을 알 수 없으면 [FeedAuthor.unknown]이다. */
    val author: FeedAuthor?,
    val like: FeedLike,
)

/** 프로필을 알 수 없으면 [unknown]이 true이고 [nickname], [profileImageUrl]은 null이다. 표시 문구는 클라이언트가 정한다. */
data class FeedAuthor(
    val id: Long,
    val nickname: String?,
    val profileImageUrl: String?,
    val unknown: Boolean = false,
)

/** 좋아요를 알 수 없으면 [count]는 null(0과 구분한다), [myLike]는 [MyLike.UNKNOWN]이다. */
data class FeedLike(
    val count: Long?,
    val myLike: MyLike,
)

enum class MyLike {
    LIKED,
    NOT_LIKED,
    UNKNOWN,
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

/**
 * 빠진 영역과 오래된 데이터로 채운 영역 (`graceful-degradation` "부분 응답 표시").
 *
 * 이름은 응답 필드가 아니라 의존성 이름(`post`, `user`, `like`, `recommendation`, `ad`)이다. 로그의 `dependency`, 지표의 `section`과 같다.
 */
data class Degradation(
    val missing: List<String>,
    val stale: List<StaleSection>,
)

/** [cachedAt]은 그 데이터를 하위 서비스에서 받은 시각이다. */
data class StaleSection(
    val section: String,
    val cachedAt: Instant,
)
