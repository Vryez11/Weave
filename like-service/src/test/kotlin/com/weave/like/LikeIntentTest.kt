package com.weave.like

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random

class LikeIntentTest :
    FunSpec({
        val initial = LikeState(liked = false, version = 0)

        test("F-003 더 큰 version의 누르기는 수를 1 올린다") {
            LikeIntent.decide(initial, liked = true, version = 1) shouldBe
                LikeDecision(LikeState(liked = true, version = 1), applied = true, countDelta = 1)
        }

        test("F-003 더 큰 version의 취소는 수를 1 내린다") {
            LikeIntent.decide(LikeState(liked = true, version = 1), liked = false, version = 2) shouldBe
                LikeDecision(LikeState(liked = false, version = 2), applied = true, countDelta = -1)
        }

        test("F-003 이미 그 상태면 version만 올리고 수는 그대로다") {
            LikeIntent.decide(LikeState(liked = true, version = 1), liked = true, version = 2) shouldBe
                LikeDecision(LikeState(liked = true, version = 2), applied = true, countDelta = 0)
        }

        test("F-003 같거나 작은 version은 값이 달라도 바꾸지 않는다") {
            val current = LikeState(liked = true, version = 3)

            listOf(1L, 3L).forEach { version ->
                LikeIntent.decide(current, liked = false, version = version) shouldBe
                    LikeDecision(current, applied = false, countDelta = 0)
            }
        }

        test("F-003 임의 순서와 중복의 최종 상태는 가장 큰 version의 값이고 수의 합은 최종 상태와 같다") {
            checkAll(Arb.list(Arb.boolean(), 1..20), Arb.long()) { intents, seed ->
                val random = Random(seed)
                val requests = intents.mapIndexed { index, liked -> liked to index + 1L }
                val sent = (requests + requests.filter { random.nextBoolean() }).shuffled(random)

                var state = initial
                var count = 0L
                sent.forEach { (liked, version) ->
                    val decision = LikeIntent.decide(state, liked, version)
                    state = decision.state
                    count += decision.countDelta
                }

                state shouldBe LikeState(requests.last().first, requests.last().second)
                count shouldBe (if (state.liked) 1L else 0L)
            }
        }
    })
