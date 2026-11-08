package com.weave.feed.gateway

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Retry-After 대기 설정 `weave.retry-after.*` (add-retry-policy design 결정 4, 11).
 *
 * 값과 근거는 main `application.yml`(tasks 4.4)에 있다. 없으면(설정으로 지우면) 읽지 못한 머리글에 대기하지 않고 상한도 두지 않는다([RetryAfter.parse]).
 */
@ConfigurationProperties("weave.retry-after")
data class RetryAfterProperties(
    /** `Retry-After`가 없거나 읽지 못한 429 뒤에 부르지 않을 시간. */
    val defaultHold: Duration? = null,
    /** `Retry-After`가 이보다 길어도 이만큼만 부르지 않는다. */
    val maxHold: Duration? = null,
)
