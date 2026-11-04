package com.weave.user

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.extensions.spring.testContextManager
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * `user-profile` 스펙의 시나리오 (F-006).
 *
 * 기댓값은 design 결정 1의 식으로 계산한다. 같은 식으로 만든 값이 매번 오는지 보므로
 * "다시 띄워도 같은 프로필"을 대신한다(design 결정 9). 스펙이 순서를 정하지 않으므로 순서는 보지 않는다.
 */
@Tags("Integration")
@SpringBootTest
@AutoConfigureMockMvc
class UserProfileScenarioTest :
    FunSpec({
        extensions(SpringExtension())

        val mapper = jacksonObjectMapper()

        suspend fun getUsers(query: String = ""): Pair<Int, String> {
            val mockMvc = testContextManager().testContext.applicationContext.getBean(MockMvc::class.java)
            val response =
                mockMvc
                    .get("/users$query")
                    .andReturn()
                    .response
            return response.status to response.contentAsString
        }

        fun usersOf(body: String): List<Map<String, Any?>> = mapper.readValue<Map<String, List<Map<String, Any?>>>>(body).getValue("users")

        test("F-006 여러 명 조회: ids=1,2,3이면 200과 함께 ID 1, 2, 3의 프로필이 온다") {
            val (status, body) = getUsers("?ids=1,2,3")

            status shouldBe 200
            val users = usersOf(body)
            users shouldContainExactlyInAnyOrder expectedProfiles(1, 2, 3)
            users.forAll { user ->
                user.keys shouldBe setOf("id", "nickname", "profileImageUrl")
            }
        }

        test("F-006 없는 ID가 섞여 있다: ids=1,9999면 200과 함께 ID 1의 프로필만 온다") {
            val (status, body) = getUsers("?ids=1,9999")

            status shouldBe 200
            usersOf(body) shouldBe expectedProfiles(1)
        }

        test("F-006 중복 ID: ids=2,2,2면 ID 2의 프로필이 하나만 온다") {
            val (status, body) = getUsers("?ids=2,2,2")

            status shouldBe 200
            usersOf(body) shouldBe expectedProfiles(2)
        }

        test("F-006 ID가 없거나 너무 많다: ids 없이 부르면 400이 온다") {
            val (status, _) = getUsers()

            status shouldBe 400
        }

        test("F-006 ID가 없거나 너무 많다: ID 101개로 부르면 400이 온다") {
            val (status, _) = getUsers("?ids=" + (1..101).joinToString(","))

            status shouldBe 400
        }

        test("F-006 고정 범위: ids=1,50,51이면 ID 1과 50의 프로필만 온다") {
            val (status, body) = getUsers("?ids=1,50,51")

            status shouldBe 200
            usersOf(body) shouldContainExactlyInAnyOrder expectedProfiles(1, 50)
        }
    })

/** design 결정 1의 식으로 만든 프로필. */
private fun expectedProfiles(vararg ids: Int): List<Map<String, Any?>> =
    ids.map { id ->
        mapOf(
            "id" to id,
            "nickname" to "사용자$id",
            "profileImageUrl" to "https://cdn.example.com/users/$id.png",
        )
    }
