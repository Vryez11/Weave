package com.weave.feed.degradation

import com.weave.feed.Degradation
import com.weave.feed.FeedAd
import com.weave.feed.FeedAuthor
import com.weave.feed.FeedLike
import com.weave.feed.FeedPost
import com.weave.feed.FeedRecommendation
import com.weave.feed.FeedResponse
import com.weave.feed.MyLike
import com.weave.feed.StaleSection
import com.weave.feed.client.ad.AdResponse
import com.weave.feed.client.like.LikeResponse
import com.weave.feed.client.post.PostResponse
import com.weave.feed.client.recommendation.RecommendationResponse
import com.weave.feed.client.user.UserResponse
import java.time.Instant

/**
 * 의존성별 결과로 피드 응답을 만든다 (add-graceful-degradation design 결정 1~3, 10).
 *
 * 순수 함수다. 하위 서비스 호출, 캐시 읽기·쓰기, 대체 로그와 지표는 `FeedAssembler`가 하고, 여기서는 받은 결과를 응답과 영역 결과
 * ([ComposedFeed.sections])로 바꾸기만 한다. 응답의 `degradation`은 영역 결과에서 만들어, 실제로 대체한 것과 어긋나지 않는다.
 *
 * 게시글이 없으면 user·like를 부르지 않으므로 `FeedAssembler`는 둘에 빈 목록의 성공을 넘긴다. 그 영역은 정상이다(design 결정 10).
 *
 * 실패한 결과는 실패의 종류와 관계없이 등급표(PRD 3장)대로 대체하고 그 영역을 `missing`으로 둔다: user는 작성자마다 `unknown`,
 * like는 모든 게시글이 `count` null과 `UNKNOWN`, recommendation·ad는 빈 목록이다.
 *
 * user가 실패하면 [cachedUsers](user 로컬 캐시에서 찾은 프로필, design 결정 7)에 있는 작성자는 그 프로필로 채운다. 모든 작성자를
 * 채웠으면 영역은 `cached`(degrade가 아니다), 한 명이라도 `unknown`이면 `missing`이다(design 결정 10). user가 성공하면 쓰지 않는다.
 */
object FeedDegradation {
    fun compose(
        posts: PostSource,
        users: Result<List<UserResponse>>,
        likes: Result<List<LikeResponse>>,
        recommendations: Result<List<RecommendationResponse>>,
        ads: Result<List<AdResponse>>,
        cachedUsers: Map<Long, UserResponse> = emptyMap(),
    ): ComposedFeed {
        val sections =
            linkedMapOf(
                POST to if (posts is PostSource.Stale) SectionOutcome.STALE else SectionOutcome.FRESH,
                USER to users.outcome(posts.posts, cachedUsers),
                LIKE to likes.outcome(),
                RECOMMENDATION to recommendations.outcome(),
                AD to ads.outcome(),
            )
        val response =
            FeedResponse(
                posts = toFeedPosts(posts.posts, users.map { it.associateBy { user -> user.id } }, cachedUsers, likes.getOrNull()),
                recommendations = recommendations.getOrDefault(emptyList()).map { FeedRecommendation(it.postId, it.title, it.reason) },
                ads = ads.getOrDefault(emptyList()).map { FeedAd(it.id, it.title, it.imageUrl, it.landingUrl) },
                degradation =
                    Degradation(
                        missing = sections.filterValues { it == SectionOutcome.MISSING }.keys.toList(),
                        stale = listOfNotNull((posts as? PostSource.Stale)?.let { StaleSection(POST, it.cachedAt) }),
                    ),
            )
        return ComposedFeed(response, sections)
    }

    /** 실패한 결과는 대체 방법과 관계없이 빠진 영역이다. */
    private fun Result<*>.outcome(): SectionOutcome = if (isSuccess) SectionOutcome.FRESH else SectionOutcome.MISSING

    /** user 실패에서 모든 작성자를 캐시로 채웠으면 `cached`다. */
    private fun Result<List<UserResponse>>.outcome(
        posts: List<PostResponse>,
        cachedUsers: Map<Long, UserResponse>,
    ): SectionOutcome =
        when {
            isSuccess -> SectionOutcome.FRESH
            posts.all { it.authorId in cachedUsers } -> SectionOutcome.CACHED
            else -> SectionOutcome.MISSING
        }

    /**
     * [users]가 실패면 작성자를 [cachedUsers]로 채우고, 거기도 없으면 `unknown`이다. [likes]가 null(like 실패)이면 좋아요는 모두
     * `UNKNOWN`이다.
     */
    private fun toFeedPosts(
        posts: List<PostResponse>,
        users: Result<Map<Long, UserResponse>>,
        cachedUsers: Map<Long, UserResponse>,
        likes: List<LikeResponse>?,
    ): List<FeedPost> {
        val usersById = users.getOrNull()
        val likesByPostId = likes?.associateBy { it.postId }
        return posts.map { post ->
            FeedPost(
                id = post.id,
                title = post.title,
                content = post.content,
                createdAt = post.createdAt,
                author =
                    if (usersById == null) {
                        cachedUsers[post.authorId]?.toFeedAuthor()
                            ?: FeedAuthor(id = post.authorId, nickname = null, profileImageUrl = null, unknown = true)
                    } else {
                        usersById[post.authorId]?.toFeedAuthor()
                    },
                like =
                    if (likesByPostId == null) {
                        FeedLike(count = null, myLike = MyLike.UNKNOWN)
                    } else {
                        val like = likesByPostId[post.id]
                        FeedLike(
                            count = like?.likeCount ?: 0,
                            myLike = if (like?.liked == true) MyLike.LIKED else MyLike.NOT_LIKED,
                        )
                    },
            )
        }
    }

    private fun UserResponse.toFeedAuthor() = FeedAuthor(id, nickname, profileImageUrl)

    private const val POST = "post"
    private const val USER = "user"
    private const val LIKE = "like"
    private const val RECOMMENDATION = "recommendation"
    private const val AD = "ad"
}

/** 피드에 담을 게시글과 그 출처 (design 결정 3). */
sealed interface PostSource {
    val posts: List<PostResponse>

    /** post-service가 이번 요청에서 준 게시글. */
    data class Fresh(
        override val posts: List<PostResponse>,
    ) : PostSource

    /** post가 실패해 stale 캐시에서 읽은 게시글. [cachedAt]은 그 게시글을 post-service에서 받은 시각이다(design 결정 4). */
    data class Stale(
        override val posts: List<PostResponse>,
        val cachedAt: Instant,
    ) : PostSource
}

/** 200 피드 응답에서 영역 하나의 결과 (design 결정 10). [tag]가 지표 `weave.feed.sections`의 `outcome` 태그다. */
enum class SectionOutcome(
    val tag: String,
) {
    /** 하위 서비스가 이번 요청에서 준 값이다. */
    FRESH("fresh"),

    /** 실패했지만 로컬 캐시로 모두 채웠다. degrade가 아니다. */
    CACHED("cached"),

    /** 오래된 대체 데이터로 채웠다. degrade다. */
    STALE("stale"),

    /** 영역이 빠졌다. degrade다. */
    MISSING("missing"),
}

/** [sections]는 의존성 이름 → 영역 결과이고 의존성 5개가 `post`, `user`, `like`, `recommendation`, `ad` 순서로 늘 있다. */
data class ComposedFeed(
    val response: FeedResponse,
    val sections: Map<String, SectionOutcome>,
)
