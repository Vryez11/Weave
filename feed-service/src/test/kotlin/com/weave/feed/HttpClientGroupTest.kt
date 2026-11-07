package com.weave.feed

import com.weave.feed.client.ad.AdClient
import com.weave.feed.client.like.LikeClient
import com.weave.feed.client.post.PostClient
import com.weave.feed.client.recommendation.RecommendationClient
import com.weave.feed.client.user.UserClient
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
import org.springframework.boot.test.context.SpringBootTest
import java.util.Collections
import java.util.IdentityHashMap

/** 하위 서비스 클라이언트 다섯이 각자의 커넥션 매니저. */
internal suspend fun connectionManagersByDependency(): Map<String, PoolingHttpClientConnectionManager> {
    val context = testContextManager().testContext.applicationContext
    return mapOf(
        "post" to PostClient::class.java,
        "user" to UserClient::class.java,
        "like" to LikeClient::class.java,
        "recommendation" to RecommendationClient::class.java,
        "ad" to AdClient::class.java,
    ).mapValues { (_, type) -> HttpClientPools.connectionManagerOf(context.getBean(type)) }
}

/** design 결정 3: HTTP 서비스 그룹마다 HttpClient와 커넥션 풀이 따로 생긴다 (F-006). */
@Tags("Integration")
@SpringBootTest
class HttpClientGroupTest :
    FunSpec({
        extensions(SpringExtension())

        test("F-006 하위 서비스 그룹 다섯이 커넥션 풀을 하나씩 따로 가진다") {
            val managers = connectionManagersByDependency().values
            val distinct = Collections.newSetFromMap(IdentityHashMap<PoolingHttpClientConnectionManager, Boolean>())
            distinct.addAll(managers)

            distinct shouldHaveSize 5
        }
    })

/**
 * `weave.http-client.max-connections-per-route`가 있으면 그룹별 값이 없는 그룹의 라우트당·전체 최대 커넥션이 그 값이고(F-006),
 * `weave.http-client.groups.<그룹>.max-connections`가 있으면 그 그룹은 그 값이다(F-001, add-call-isolation design 결정 5).
 * main 설정의 like 덮어쓰기(50)에 기대지 않게 두 값을 테스트가 정한다.
 */
@Tags("Integration")
@SpringBootTest(
    properties = [
        "weave.http-client.max-connections-per-route=7",
        "weave.http-client.groups.like.max-connections=9",
    ],
)
class HttpClientPoolSizePropertyTest :
    FunSpec({
        extensions(SpringExtension())

        test("F-006 F-001 커넥션 풀 크기 속성이 그룹별 값이 없는 그룹에 적용되고, 그룹별 값이 있는 like는 그 값이다") {
            connectionManagersByDependency().forEach { (dependency, manager) ->
                val expected = if (dependency == "like") 9 else 7
                (dependency to manager.defaultMaxPerRoute) shouldBe (dependency to expected)
                (dependency to manager.maxTotal) shouldBe (dependency to expected)
            }
        }
    })
