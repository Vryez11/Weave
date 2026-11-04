package com.weave.recommendation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class RecommendationCatalogTest :
    FunSpec({
        val catalog = RecommendationCatalog()

        test("F-006 고정 추천은 결정 1의 식으로 만든다") {
            RecommendationCatalog.fixedRecommendation(1) shouldBe Recommendation(10, "게시글 10", "많이 본 게시글")
            RecommendationCatalog.fixedRecommendation(20) shouldBe Recommendation(200, "게시글 200", "많이 본 게시글")
        }

        test("F-006 추천은 k 순서대로 앞에서부터 자른다") {
            catalog.top(3).map { it.postId } shouldBe listOf(10L, 20L, 30L)
        }

        test("F-006 추천은 20개다") {
            catalog.top(1_000) shouldHaveSize 20
        }

        test("F-006 두 번 만들어도 데이터가 같다") {
            RecommendationCatalog().top(20) shouldBe catalog.top(20)
        }
    })
