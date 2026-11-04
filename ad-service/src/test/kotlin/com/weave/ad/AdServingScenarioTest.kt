package com.weave.ad

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
 * `ad-serving` 스펙의 시나리오 (F-006).
 *
 * 기댓값은 design 결정 1의 식으로 계산한다. 같은 식으로 만든 값이 매번 오는지 보므로
 * "다시 띄워도 같은 데이터"를 대신한다(design 결정 9).
 */
@Tags("Integration")
@SpringBootTest
@AutoConfigureMockMvc
class AdServingScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val mapper = jacksonObjectMapper()

        suspend fun getAds(query: String = ""): Pair<Int, String> {
            val mockMvc = testContextManager().testContext.applicationContext.getBean(MockMvc::class.java)
            val response =
                mockMvc
                    .get("/ads$query")
                    .andReturn()
                    .response
            return response.status to response.contentAsString
        }

        fun adsOf(body: String): List<Map<String, Any?>> = mapper.readValue<Map<String, List<Map<String, Any?>>>>(body).getValue("ads")

        test("F-006 기본 개수로 조회: size 없이 부르면 광고 2개가 id, title, imageUrl, landingUrl과 함께 온다") {
            val (status, body) = getAds()

            status shouldBe 200
            val ads = adsOf(body)
            ads shouldBe expectedAds(2)
            ads.forAll { ad ->
                ad.keys shouldBe setOf("id", "title", "imageUrl", "landingUrl")
            }
        }

        listOf("0", "6").forEach { size ->
            test("F-006 범위를 벗어난 개수: size=$size 이면 400이 온다") {
                val (status, _) = getAds("?size=$size")

                status shouldBe 400
            }
        }

        test("F-006 다시 띄워도 같은 데이터: size=5 응답이 고정 데이터의 식과 같다") {
            val (status, body) = getAds("?size=5")

            status shouldBe 200
            adsOf(body) shouldBe expectedAds(5)
        }
    })

/** design 결정 1의 식으로 만든 광고 [size]개. id 순서대로 온다. */
private fun expectedAds(size: Int): List<Map<String, Any?>> =
    (1..size).map { id ->
        mapOf(
            "id" to id,
            "title" to "광고 $id",
            "imageUrl" to "https://cdn.example.com/ads/$id.png",
            "landingUrl" to "https://example.com/ads/$id",
        )
    }
