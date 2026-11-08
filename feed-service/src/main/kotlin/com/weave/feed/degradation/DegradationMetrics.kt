package com.weave.feed.degradation

import com.weave.feed.gateway.IsolatedCalls
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 대체 지표 (add-graceful-degradation design 결정 10).
 *
 * | 지표 | 태그 | 언제 센다 |
 * |---|---|---|
 * | `weave.feed.sections` | `section`(의존성 5개), `outcome`([SectionOutcome.tag]) | 200 피드 응답마다 의존성 5개 각각 하나([sections]) |
 * | `weave.post.cache.reads` | `outcome`([PostCacheRead.tag]) | post 실패로 캐시를 읽을 때마다([postCacheRead]) |
 * | `weave.post.cache.writes` | `outcome`(`success`, `error`, `dropped`) | 쓰기를 버리거나 끝낼 때마다([postCacheWrite]) |
 *
 * 만들 때 모든 시계열을 0으로 등록해, 대체가 한 번도 없었던 동안에도 대시보드와 degrade 비율의 분모가 0으로 보인다. user 캐시 적중·누락은
 * Caffeine 통계(`cache_gets_total{cache="user-profiles"}`, [UserProfileCache])다.
 */
class DegradationMetrics(
    registry: MeterRegistry,
) {
    private val sections: Map<Pair<String, SectionOutcome>, Counter> =
        IsolatedCalls.DEPENDENCIES
            .flatMap { section -> SectionOutcome.entries.map { section to it } }
            .associateWith { (section, outcome) ->
                Counter
                    .builder(SECTIONS)
                    .description("200 피드 응답의 영역 결과")
                    .tag("section", section)
                    .tag("outcome", outcome.tag)
                    .register(registry)
            }

    private val reads: Map<String, Counter> =
        PostCacheRead.TAGS.associateWith {
            Counter
                .builder(POST_CACHE_READS)
                .description("post stale 캐시 읽기 결과")
                .tag("outcome", it)
                .register(registry)
        }

    private val writes: Map<PostCacheWrite, Counter> =
        PostCacheWrite.entries.associateWith {
            Counter
                .builder(POST_CACHE_WRITES)
                .description("post stale 캐시 쓰기 결과")
                .tag("outcome", it.tag)
                .register(registry)
        }

    /** 200 응답 하나의 영역 결과([ComposedFeed.sections])를 센다. */
    fun sections(sections: Map<String, SectionOutcome>) {
        sections.forEach { (section, outcome) -> this.sections[section to outcome]?.increment() }
    }

    fun postCacheRead(read: PostCacheRead) {
        reads.getValue(read.tag).increment()
    }

    fun postCacheWrite(outcome: PostCacheWrite) {
        writes.getValue(outcome).increment()
    }

    companion object {
        const val SECTIONS = "weave.feed.sections"
        const val POST_CACHE_READS = "weave.post.cache.reads"
        const val POST_CACHE_WRITES = "weave.post.cache.writes"

        /** 아무 레지스트리에도 내보내지 않는다. `FeedAssembler`, [PostStaleCache]를 직접 만드는 단위 테스트의 기본값이다. */
        val DISABLED = DegradationMetrics(CompositeMeterRegistry())
    }
}

/** post 캐시 쓰기 결과. [tag]가 지표 `weave.post.cache.writes`의 `outcome` 태그다. */
enum class PostCacheWrite(
    val tag: String,
) {
    SUCCESS("success"),
    ERROR("error"),

    /** 쓰기 대기열이 차서 버렸다(design 결정 5). */
    DROPPED("dropped"),
}

@Configuration(proxyBeanMethods = false)
class DegradationMetricsConfig {
    @Bean
    fun degradationMetrics(registry: MeterRegistry): DegradationMetrics = DegradationMetrics(registry)
}
