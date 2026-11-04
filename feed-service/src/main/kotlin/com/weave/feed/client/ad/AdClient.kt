package com.weave.feed.client.ad

import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange

/** ad-service의 `ad-serving` API. 개수는 서비스 기본값을 쓴다. */
@HttpExchange
interface AdClient {
    @GetExchange("/ads")
    fun getAds(): AdsResponse
}

data class AdsResponse(
    val ads: List<AdResponse>,
)

data class AdResponse(
    val id: Long,
    val title: String,
    val imageUrl: String,
    val landingUrl: String,
)
