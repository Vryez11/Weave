package com.weave.feed.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy
import org.apache.hc.client5.http.protocol.HttpClientContext
import org.apache.hc.core5.http.message.BasicHttpResponse
import java.util.concurrent.TimeUnit

/** architecture 4-2: `HttpClientConfig`가 끄는 HttpClient 5 기본 재시도 전략의 값 (F-006). */
class HttpClientRetryDefaultsTest :
    FunSpec({
        val strategy = DefaultHttpRequestRetryStrategy.INSTANCE
        val context = HttpClientContext.create()

        test("F-006 기본 재시도는 429와 503에만 한 번 다시 보낸다") {
            (400..599).filter { strategy.retryRequest(BasicHttpResponse(it), 1, context) } shouldBe listOf(429, 503)
            strategy.retryRequest(BasicHttpResponse(503), 2, context) shouldBe false
        }

        test("F-006 기본 재시도 간격은 Retry-After가 없으면 1초다") {
            strategy.getRetryInterval(BasicHttpResponse(503), 1, context).toDuration().toMillis() shouldBe
                TimeUnit.SECONDS.toMillis(1)
        }
    })
