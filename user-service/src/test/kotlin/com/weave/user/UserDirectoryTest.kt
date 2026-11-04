package com.weave.user

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class UserDirectoryTest :
    FunSpec({
        val directory = UserDirectory()

        test("F-006 고정 사용자는 결정 1의 식으로 만든다") {
            UserDirectory.fixedProfile(1) shouldBe
                UserProfile(1, "사용자1", "https://cdn.example.com/users/1.png")
            UserDirectory.fixedProfile(50) shouldBe
                UserProfile(50, "사용자50", "https://cdn.example.com/users/50.png")
        }

        test("F-006 없는 ID는 빠진다") {
            directory.findAll(listOf(0L, 1L, 50L, 51L, 9999L)).map { it.id } shouldBe listOf(1L, 50L)
            directory.findAll(listOf(51L, 9999L)).shouldBeEmpty()
        }

        test("F-006 같은 ID를 여러 번 보내도 한 번만 온다") {
            directory.findAll(listOf(2L, 2L, 2L)) shouldBe listOf(UserDirectory.fixedProfile(2))
            directory.findAll(listOf(3L, 1L, 3L)).map { it.id } shouldBe listOf(3L, 1L)
        }

        test("F-006 사용자는 1~50의 50명이다") {
            directory.findAll((1L..100L).toList()).map { it.id } shouldBe (1L..50L).toList()
        }

        test("F-006 두 번 만들어도 프로필이 같다") {
            UserDirectory().findAll((1L..50L).toList()) shouldBe directory.findAll((1L..50L).toList())
        }
    })
