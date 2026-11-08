package com.weave.feed.degradation

import com.weave.feed.FeedAuthor
import com.weave.feed.MovableClock
import com.weave.feed.client.like.LikeResponse
import com.weave.feed.client.post.PostResponse
import com.weave.feed.client.user.UserResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.time.Instant

/**
 * user 로컬 캐시 채움(전부·일부·유효 기간)과 그 결과로 만든 user 영역 (add-graceful-degradation design 결정 7, 10, tasks 2.3).
 *
 * 유효 기간은 [MovableClock]을 옮겨 지나게 한다. 캐시의 `Ticker`가 같은 시계를 쓴다(design 결정 11).
 */
class UserProfileCacheTest :
    FunSpec({
        val createdAt = Instant.parse("2026-01-01T00:12:00Z")

        fun post(
            id: Long,
            authorId: Long,
        ) = PostResponse(id = id, authorId = authorId, title = "게시글 $id", content = "게시글 ${id}의 본문", createdAt = createdAt)

        fun user(
            id: Long,
            nickname: String = "사용자$id",
        ) = UserResponse(id = id, nickname = nickname, profileImageUrl = "https://cdn.example.com/users/$id.png")

        fun author(id: Long) = FeedAuthor(id = id, nickname = "사용자$id", profileImageUrl = "https://cdn.example.com/users/$id.png")

        fun unknownAuthor(id: Long) = FeedAuthor(id = id, nickname = null, profileImageUrl = null, unknown = true)

        fun cache(
            clock: MovableClock = MovableClock(),
            registry: SimpleMeterRegistry = SimpleMeterRegistry(),
        ) = UserProfileCache.create(Duration.ofHours(1), 100, clock, registry)

        fun SimpleMeterRegistry.gets(result: String) =
            get("cache.gets")
                .tags("cache", "user-profiles", "result", result)
                .functionCounter()
                .count()

        context("캐시") {
            test("F-002 전부: 받은 작성자를 모두 찾으면 모두 돌려주고 작성자마다 적중으로 센다") {
                val registry = SimpleMeterRegistry()
                val cache = cache(registry = registry)
                cache.putAll(listOf(user(3), user(4)))

                cache.getAll(listOf(3, 4)) shouldBe mapOf(3L to user(3), 4L to user(4))
                registry.gets("hit") shouldBe 2.0
                registry.gets("miss") shouldBe 0.0
            }

            test("F-002 일부: 받지 않은 작성자는 돌려주지 않고 누락으로 센다") {
                val registry = SimpleMeterRegistry()
                val cache = cache(registry = registry)
                cache.putAll(listOf(user(3)))

                cache.getAll(listOf(3, 4)) shouldBe mapOf(3L to user(3))
                registry.gets("hit") shouldBe 1.0
                registry.gets("miss") shouldBe 1.0
            }

            test("F-002 다시 받으면 새 프로필로 바뀐다") {
                val cache = cache()
                cache.putAll(listOf(user(3, "old")))
                cache.putAll(listOf(user(3, "new")))

                cache.getAll(listOf(3)) shouldBe mapOf(3L to user(3, "new"))
            }

            test("F-002 유효 기간: 넣은 지 유효 기간(1시간)이 지나면 찾지 못하고, 그 안이면 찾는다") {
                val clock = MovableClock()
                val cache = cache(clock = clock)
                cache.putAll(listOf(user(3)))

                clock.advance(Duration.ofMinutes(59))
                cache.getAll(listOf(3)) shouldBe mapOf(3L to user(3))

                clock.advance(Duration.ofMinutes(2))
                cache.getAll(listOf(3)).shouldBeEmpty()
            }

            test("F-002 설정이 없으면(DISABLED) 넣지도 찾지도 않는다") {
                val cache = UserProfileCache.DISABLED
                cache.putAll(listOf(user(3)))

                cache.getAll(listOf(3)).shouldBeEmpty()
            }
        }

        context("user 영역") {
            val posts = PostSource.Fresh(listOf(post(12, 4), post(10, 3), post(11, 3)))
            val likes = Result.success(emptyList<LikeResponse>())
            val empty = Result.success(emptyList<Nothing>())
            val failure = Result.failure<List<UserResponse>>(IllegalStateException("503"))

            test("F-002 user 실패에서 캐시가 작성자를 모두 채우면 캐시의 프로필이고 영역은 cached이며 missing에 user가 없다") {
                val composed =
                    FeedDegradation.compose(posts, failure, likes, empty, empty, cachedUsers = mapOf(3L to user(3), 4L to user(4)))

                composed.response.posts.map { it.author } shouldBe listOf(author(4), author(3), author(3))
                composed.sections["user"] shouldBe SectionOutcome.CACHED
                composed.response.degradation.missing shouldBe emptyList()
            }

            test("F-002 user 실패에서 캐시에 없는 작성자가 있으면 그 작성자만 unknown이고 영역은 missing이다") {
                val composed = FeedDegradation.compose(posts, failure, likes, empty, empty, cachedUsers = mapOf(3L to user(3)))

                composed.response.posts.map { it.author } shouldBe listOf(unknownAuthor(4), author(3), author(3))
                composed.sections["user"] shouldBe SectionOutcome.MISSING
                composed.response.degradation.missing shouldBe listOf("user")
            }

            test("F-002 user가 성공하면 캐시의 프로필을 쓰지 않는다") {
                val composed =
                    FeedDegradation.compose(
                        posts,
                        Result.success(listOf(user(3))),
                        likes,
                        empty,
                        empty,
                        cachedUsers = mapOf(3L to user(3, "old"), 4L to user(4)),
                    )

                composed.response.posts.map { it.author } shouldBe listOf(null, author(3), author(3))
                composed.sections["user"] shouldBe SectionOutcome.FRESH
            }
        }
    })
