package com.weave.feed.degradation

import org.slf4j.LoggerFactory

/**
 * 대체 이벤트 로그 `fallback_applied` (add-graceful-degradation design 결정 9, PRD F-004).
 *
 * 대체한 의존성마다 한 요청에 한 줄이다. 실패 원인은 같은 호출의 `downstream_call_failed` 줄에 있어 다시 적지 않는다. `FeedAssembler`가
 * 요청 스레드에서 남기므로 MDC의 `traceId`가 그대로 붙는다.
 */
object FallbackLog {
    private val logger = LoggerFactory.getLogger(FallbackLog::class.java)

    /**
     * 200 응답의 영역 결과([ComposedFeed.sections])에서 대체한 의존성의 이벤트를 만든다. 응답의 `degradation`과 같은 값에서 만들어 어긋나지
     * 않는다. user는 실패했을 때만 [cacheHits]·[cacheMisses](중복 없는 작성자 수)를 단다.
     */
    fun events(
        sections: Map<String, SectionOutcome>,
        cacheHits: Int,
        cacheMisses: Int,
    ): List<FallbackEvent> =
        sections.mapNotNull { (dependency, outcome) ->
            when (outcome) {
                SectionOutcome.FRESH -> null
                SectionOutcome.STALE -> FallbackEvent(dependency, STALE_CACHE)
                SectionOutcome.CACHED -> FallbackEvent(dependency, LOCAL_CACHE, cacheCounts(cacheHits, cacheMisses))
                SectionOutcome.MISSING ->
                    when (dependency) {
                        "user" -> FallbackEvent(dependency, PARTIAL_CACHE, cacheCounts(cacheHits, cacheMisses))
                        "like" -> FallbackEvent(dependency, UNKNOWN)
                        else -> FallbackEvent(dependency, OMITTED)
                    }
            }
        }

    /** post를 대체할 수 없어 503으로 끝나는 요청의 이벤트. [reason]은 `cache_miss`, `cache_expired`, `cache_error`다. */
    fun unavailable(reason: String) = FallbackEvent("post", UNAVAILABLE, mapOf("reason" to reason))

    fun log(event: FallbackEvent) {
        val builder =
            logger
                .atInfo()
                .addKeyValue("event", "fallback_applied")
                .addKeyValue("dependency", event.dependency)
                .addKeyValue("fallback", event.fallback)
        event.fields.forEach { (key, value) -> builder.addKeyValue(key, value) }
        builder.log("{} 대체: {}", event.dependency, event.fallback)
    }

    private fun cacheCounts(
        hits: Int,
        misses: Int,
    ) = mapOf("cacheHits" to hits, "cacheMisses" to misses)

    private const val STALE_CACHE = "stale_cache"
    private const val UNAVAILABLE = "unavailable"
    private const val LOCAL_CACHE = "local_cache"
    private const val PARTIAL_CACHE = "partial_cache"
    private const val UNKNOWN = "unknown"
    private const val OMITTED = "omitted"
}

/** 대체 이벤트 하나. [fields]는 `event`·`dependency`·`fallback` 뒤에 붙는 필드다(post `reason`, user `cacheHits`·`cacheMisses`). */
data class FallbackEvent(
    val dependency: String,
    val fallback: String,
    val fields: Map<String, Any> = emptyMap(),
)
