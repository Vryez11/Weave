package com.weave.ad

import org.springframework.stereotype.Component

/**
 * 시작할 때 design 결정 1의 식으로 만든 고정 광고를 메모리에 들고 있는다.
 * 같은 식으로 만들므로 몇 번을 다시 띄워도 데이터가 같다.
 */
@Component
class AdCatalog {
    private val ads: List<Ad> = (1L..AD_COUNT).map(::fixedAd)

    /** id 순서대로 앞에서부터 [size]개. */
    fun top(size: Int): List<Ad> = ads.take(size)

    companion object {
        const val AD_COUNT = 5L

        fun fixedAd(id: Long): Ad =
            Ad(
                id = id,
                title = "광고 $id",
                imageUrl = "https://cdn.example.com/ads/$id.png",
                landingUrl = "https://example.com/ads/$id",
            )
    }
}
