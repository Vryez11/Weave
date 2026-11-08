package com.weave.feed.gateway

import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.concurrent.CompletionException

/** add-retry-policy design 결정 7: 우리 쪽이 막은 두 실패의 분류. 둘 다 서킷에 세지 않는다. */
class DownstreamFailureRetryCauseTest :
    FunSpec({
        val rateLimited = RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("ad"))
        val held = RetryAfterHoldException("ad", Instant.parse("2026-11-08T00:00:02Z"))

        test("F-005 요청 수 상한이 막은 호출: RequestNotPermitted는 rate_limited다") {
            DownstreamFailure.of(CompletionException(rateLimited)) shouldBe
                DownstreamFailure("rate_limited", RequestNotPermitted::class.java.name)
            DownstreamFailure.of(rateLimited).cause shouldBe "rate_limited"
        }

        test("F-005 Retry-After 대기로 막은 호출: RetryAfterHoldException은 retry_after다") {
            DownstreamFailure.of(CompletionException(held)) shouldBe
                DownstreamFailure("retry_after", RetryAfterHoldException::class.java.name)
            DownstreamFailure.of(held).cause shouldBe "retry_after"
        }

        test("F-005 rate_limited와 retry_after는 서킷이 실패로 세지 않는다") {
            DownstreamFailure.countsAsCircuitFailure(rateLimited) shouldBe false
            DownstreamFailure.countsAsCircuitFailure(CompletionException(rateLimited)) shouldBe false
            DownstreamFailure.countsAsCircuitFailure(held) shouldBe false
            DownstreamFailure.countsAsCircuitFailure(CompletionException(held)) shouldBe false
        }
    })
