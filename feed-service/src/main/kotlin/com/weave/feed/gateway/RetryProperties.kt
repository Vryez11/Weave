package com.weave.feed.gateway

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Clock
import java.time.Duration

/**
 * 재시도 설정 `weave.retry.*` (add-retry-policy design 결정 5, 11). 시도 횟수와 간격은 Resilience4j 스타터의 `resilience4j.retry.*`다.
 *
 * 값과 근거는 main `application.yml`(tasks 4.2)에 있다. 셋 중 하나라도 없으면(설정으로 지우면) 예산을 보지 않는다([Budget.newBudget]).
 */
@ConfigurationProperties("weave.retry")
data class RetryProperties(
    val budget: Budget = Budget(),
) {
    data class Budget(
        /** 창 안의 원래 호출 수에 곱해 허용할 재시도 수. */
        val ratio: Double? = null,
        /** 트래픽이 적을 때도 창마다 허용할 재시도 수. */
        val minRetries: Int? = null,
        /** 원래 호출과 재시도를 세는 최근 시간. */
        val window: Duration? = null,
    ) {
        /** 의존성 하나의 예산을 새로 만든다. 값이 하나라도 없으면 null이고, 그때는 예산 없이 다시 부른다. */
        fun newBudget(clock: Clock): RetryBudget? {
            val ratio = ratio ?: return null
            val minRetries = minRetries ?: return null
            val window = window ?: return null
            return RetryBudget(ratio, minRetries, window, clock)
        }
    }
}
