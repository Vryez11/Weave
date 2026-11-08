package com.weave.feed.gateway

import java.time.Instant

/**
 * 지금 스레드에서 시작하는 하위 서비스 호출이 속한 피드 요청의 마감 (add-retry-policy design 결정 3).
 *
 * `RequestBudget.call`이 호출을 시작하는 동안([within])만 둔다. Gateway → [IsolatedCalls.call]이 그 안에서 동기로 불리므로, `call`이 시작할 때
 * [current]로 읽어 그 호출의 재시도 루프에 들고 간다. Gateway 시그니처를 바꾸지 않으려는 것이다. Gateway가 호출을 다른 스레드로 넘기게 바뀌면
 * 마감을 잃는다(그때는 마감 없이 시도 횟수만 본다).
 */
internal object RequestDeadline {
    private val deadline = ThreadLocal<Instant>()

    /** 마감이 없으면(예산 없이 부른 경우) null이다. */
    fun current(): Instant? = deadline.get()

    fun <T> within(
        at: Instant,
        block: () -> T,
    ): T {
        val previous = deadline.get()
        deadline.set(at)
        try {
            return block()
        } finally {
            if (previous == null) deadline.remove() else deadline.set(previous)
        }
    }
}
