package com.weave.ad

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class AdController(
    private val adCatalog: AdCatalog,
) {
    @GetMapping("/ads")
    fun ads(
        @RequestParam(defaultValue = "$DEFAULT_SIZE") size: Int,
    ): AdsResponse {
        if (size !in SIZE_RANGE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size는 ${SIZE_RANGE.first}~${SIZE_RANGE.last}만 받는다: $size")
        }
        return AdsResponse(adCatalog.top(size))
    }

    data class AdsResponse(
        val ads: List<Ad>,
    )

    companion object {
        private const val DEFAULT_SIZE = 2
        private val SIZE_RANGE = 1..5
    }
}
