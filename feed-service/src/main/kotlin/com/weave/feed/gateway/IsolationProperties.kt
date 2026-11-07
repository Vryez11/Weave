package com.weave.feed.gateway

import org.springframework.boot.context.properties.ConfigurationProperties

/** 하위 서비스 조회 격리 방식 (add-call-isolation design 결정 11). */
@ConfigurationProperties("weave.isolation")
data class IsolationProperties(
    /** 조회를 감싸는 벌크헤드. 기본은 의존성별 ThreadPool 벌크헤드다. */
    val bulkheadType: BulkheadType = BulkheadType.THREAD_POOL,
) {
    enum class BulkheadType {
        /** 의존성별 전용 스레드 풀(`resilience4j.thread-pool-bulkhead.instances.<의존성>`)에서 부른다. */
        THREAD_POOL,

        /**
         * `applicationTaskExecutor`에서 부르고 의존성별 Semaphore 벌크헤드(`resilience4j.bulkhead.instances.<의존성>`)로 동시 호출 수를
         * 묶는다. `spring.threads.virtual.enabled: true`와 함께 쓰면 가상 스레드 + 허가 수 구성이 된다(가상 스레드 비교 실험).
         */
        SEMAPHORE,
    }
}
