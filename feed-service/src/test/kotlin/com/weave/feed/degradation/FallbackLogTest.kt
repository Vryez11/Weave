package com.weave.feed.degradation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * 영역 결과에서 대체 이벤트를 고른다 (add-graceful-degradation design 결정 9, tasks 2.5).
 *
 * 로그 줄 자체(MDC `traceId`, 필드)는 `FeedFallbackLogScenarioTest`가 본다. 여기서는 스펙 "대체 이벤트 로그"의 `fallback` 값이 등급마다
 * 맞는지 본다.
 */
class FallbackLogTest :
    FunSpec({
        fun sections(vararg changed: Pair<String, SectionOutcome>): Map<String, SectionOutcome> =
            linkedMapOf(
                "post" to SectionOutcome.FRESH,
                "user" to SectionOutcome.FRESH,
                "like" to SectionOutcome.FRESH,
                "recommendation" to SectionOutcome.FRESH,
                "ad" to SectionOutcome.FRESH,
            ).apply { putAll(changed) }

        test("F-002 F-004 모든 영역이 정상이면 이벤트가 없다") {
            FallbackLog.events(sections(), cacheHits = 0, cacheMisses = 0).shouldBeEmpty()
        }

        test("F-002 F-004 대체한 의존성마다 등급대로 fallback 값을 하나씩 고른다") {
            val events =
                FallbackLog.events(
                    sections(
                        "post" to SectionOutcome.STALE,
                        "user" to SectionOutcome.MISSING,
                        "like" to SectionOutcome.MISSING,
                        "recommendation" to SectionOutcome.MISSING,
                        "ad" to SectionOutcome.MISSING,
                    ),
                    cacheHits = 1,
                    cacheMisses = 1,
                )

            events shouldBe
                listOf(
                    FallbackEvent("post", "stale_cache"),
                    FallbackEvent("user", "partial_cache", mapOf("cacheHits" to 1, "cacheMisses" to 1)),
                    FallbackEvent("like", "unknown"),
                    FallbackEvent("recommendation", "omitted"),
                    FallbackEvent("ad", "omitted"),
                )
        }

        test("F-002 F-004 user를 로컬 캐시로 모두 채웠으면 local_cache이고 적중·누락 수를 단다") {
            FallbackLog.events(sections("user" to SectionOutcome.CACHED), cacheHits = 2, cacheMisses = 0) shouldBe
                listOf(FallbackEvent("user", "local_cache", mapOf("cacheHits" to 2, "cacheMisses" to 0)))
        }

        test("F-002 F-004 user 캐시에 작성자가 하나도 없어도 partial_cache다") {
            FallbackLog.events(sections("user" to SectionOutcome.MISSING), cacheHits = 0, cacheMisses = 2) shouldBe
                listOf(FallbackEvent("user", "partial_cache", mapOf("cacheHits" to 0, "cacheMisses" to 2)))
        }

        test("F-002 F-004 post를 대체할 수 없으면 unavailable에 reason을 단다") {
            FallbackLog.unavailable("cache_miss") shouldBe FallbackEvent("post", "unavailable", mapOf("reason" to "cache_miss"))
        }
    })
