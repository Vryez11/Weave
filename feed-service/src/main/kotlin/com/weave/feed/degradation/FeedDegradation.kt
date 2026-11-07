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
 * 지금은 실패한 결과를 받으면 그 실패를 던진다. 등급표(PRD 3장)대로 대체하는 것은 tasks 2.2부터다.
 */
object FeedDegradation {
    fun compose(
        posts: PostSource,
        users: Result<List<UserResponse>>,
        likes: Result<List<LikeResponse>>,
        recommendations: Result<List<RecommendationResponse>>,
        ads: Result<List<AdResponse>>,
    ): ComposedFeed {
        val sections =
            linkedMapOf(
                POST to if (posts is PostSource.Stale) SectionOutcome.STALE else SectionOutcome.FRESH,
                USER to SectionOutcome.FRESH,
                LIKE to SectionOutcome.FRESH,
                RECOMMENDATION to SectionOutcome.FRESH,
                AD to SectionOutcome.FRESH,
            )
        val response =
            FeedResponse(
                posts = toFeedPosts(posts.posts, users.getOrThrow(), likes.getOrThrow()),
                recommendations = recommendations.getOrThrow().map { FeedRecommendation(it.postId, it.title, it.reason) },
                ads = ads.getOrThrow().map { FeedAd(it.id, it.title, it.imageUrl, it.landingUrl) },
                degradation =
                    Degradation(
                        missing = sections.filterValues { it == SectionOutcome.MISSING }.keys.toList(),
                        stale = listOfNotNull((posts as? PostSource.Stale)?.let { StaleSection(POST, it.cachedAt) }),
                    ),
            )
        return ComposedFeed(response, sections)
    }

    private fun toFeedPosts(
        posts: List<PostResponse>,
        users: List<UserResponse>,
        likes: List<LikeResponse>,
    ): List<FeedPost> {
        val usersById = users.associateBy { it.id }
        val likesByPostId = likes.associateBy { it.postId }
        return posts.map { post ->
            val like = likesByPostId[post.id]
            FeedPost(
                id = post.id,
                title = post.title,
                content = post.content,
                createdAt = post.createdAt,
                author = usersById[post.authorId]?.let { FeedAuthor(it.id, it.nickname, it.profileImageUrl) },
                like =
                    FeedLike(
                        count = like?.likeCount ?: 0,
                        myLike = if (like?.liked == true) MyLike.LIKED else MyLike.NOT_LIKED,
                    ),
            )
        }
    }

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
