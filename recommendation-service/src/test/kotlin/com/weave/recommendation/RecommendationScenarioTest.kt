package com.weave.recommendation

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.inspectors.forAll
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * `recommendation` 스펙의 시나리오 (F-006).
 *
 * 기댓값은 design 결정 1의 식으로 계산한다. 같은 식으로 만든 값이 매번 오는지 보므로
 * "다시 띄워도 같은 데이터"를 대신한다(design 결정 9).
 */
@Tags("Integration")
@SpringBootTest
@AutoConfigureMockMvc
class RecommendationScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val mapper = jacksonObjectMapper()

        suspend fun getRecommendations(
            query: String = "",
            userId: String? = null,
        ): Pair<Int, String> {
            val mockMvc = testContextManager().testContext.applicationContext.getBean(MockMvc::class.java)
            val response =
                mockMvc
                    .get("/recommendations$query") {
                        if (userId != null) header("X-User-Id", userId)
                    }.andReturn()
                    .response
            return response.status to response.contentAsString
        }

        fun recommendationsOf(body: String): List<Map<String, Any?>> =
            mapper.readValue<Map<String, List<Map<String, Any?>>>>(body).getValue("recommendations")

        test("F-006 기본 개수로 조회: size 없이 부르면 추천 5개가 postId, title, reason과 함께 온다") {
            val (status, body) = getRecommendations()

            status shouldBe 200
            val recommendations = recommendationsOf(body)
            recommendations shouldBe expectedRecommendations(5)
            recommendations.forAll { recommendation ->
                recommendation.keys shouldBe setOf("postId", "title", "reason")
            }
        }

        test("F-006 사용자와 관계없다: X-User-Id 1, 2, 헤더 없음의 세 응답이 같다") {
            val withUser1 = getRecommendations(userId = "1")
            val withUser2 = getRecommendations(userId = "2")
            val withoutUser = getRecommendations()

            withUser1.first shouldBe 200
            withUser2.first shouldBe 200
            withoutUser.first shouldBe 200
            recommendationsOf(withUser1.second) shouldBe recommendationsOf(withoutUser.second)
            recommendationsOf(withUser2.second) shouldBe recommendationsOf(withoutUser.second)
        }

        listOf("0", "21").forEach { size ->
            test("F-006 범위를 벗어난 개수: size=$size 이면 400이 온다") {
                val (status, _) = getRecommendations("?size=$size")

                status shouldBe 400
            }
        }

        test("F-006 다시 띄워도 같은 데이터: size=20 응답이 고정 데이터의 식과 같다") {
            val (status, body) = getRecommendations("?size=20")

            status shouldBe 200
            recommendationsOf(body) shouldBe expectedRecommendations(20)
        }
    })

/** design 결정 1의 식으로 만든 추천 [size]개. k = 1부터 차례로 postId = 10k다. */
private fun expectedRecommendations(size: Int): List<Map<String, Any?>> =
    (1..size).map { k ->
        val postId = 10 * k
        mapOf(
            "postId" to postId,
            "title" to "게시글 $postId",
            "reason" to "많이 본 게시글",
        )
    }
