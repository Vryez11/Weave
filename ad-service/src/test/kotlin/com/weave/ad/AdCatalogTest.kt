package com.weave.ad

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class AdCatalogTest :
    FunSpec({
        val catalog = AdCatalog()

        test("F-006 고정 광고는 결정 1의 식으로 만든다") {
            AdCatalog.fixedAd(1) shouldBe
                Ad(1, "광고 1", "https://cdn.example.com/ads/1.png", "https://example.com/ads/1")
        }

        test("F-006 광고는 id 순서대로 앞에서부터 자른다") {
            catalog.top(2).map { it.id } shouldBe listOf(1L, 2L)
        }

        test("F-006 광고는 5개다") {
            catalog.top(1_000) shouldHaveSize 5
        }

        test("F-006 두 번 만들어도 데이터가 같다") {
            AdCatalog().top(5) shouldBe catalog.top(5)
        }
    })
