package com.weave.feed.gateway

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 서킷 상태가 바뀔 때마다 `circuit_state_changed` WARN 한 줄을 남긴다 (add-call-isolation design 결정 9).
 *
 * 스타터가 `instances.*` 설정으로 미리 만든 서킷에는 시작할 때 걸고, 레지스트리에 나중에 생기는 서킷은 `onEntryAdded`에서 건다.
 * 두 경로가 겹쳐도 서킷 하나에 한 번만 건다.
 *
 * 전이는 실패를 기록한 스레드에서 일어난다. [IsolatedCalls.call]은 호출한 스레드의 문맥을 펼친 채로 서킷이 결과를 기록하게 하므로
 * 그 줄에 피드 요청의 `traceId`가 있다. 열림 유지 시간이 지나 저절로 반열림이 되는 전이는 Resilience4j 스케줄러 스레드라 없다.
 */
@Component
class CircuitStateLog(
    registry: CircuitBreakerRegistry,
) {
    private val watched = ConcurrentHashMap.newKeySet<String>()

    init {
        registry.eventPublisher.onEntryAdded { watch(it.addedEntry) }
        registry.allCircuitBreakers.forEach(::watch)
    }

    private fun watch(circuitBreaker: CircuitBreaker) {
        if (!watched.add(circuitBreaker.name)) return
        circuitBreaker.eventPublisher.onStateTransition { event ->
            logger
                .atWarn()
                .addKeyValue("event", "circuit_state_changed")
                .addKeyValue("dependency", event.circuitBreakerName)
                .addKeyValue("from", event.stateTransition.fromState.name)
                .addKeyValue("to", event.stateTransition.toState.name)
                .log(
                    "{} 서킷 상태 전이: {} → {}",
                    event.circuitBreakerName,
                    event.stateTransition.fromState,
                    event.stateTransition.toState,
                )
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(CircuitStateLog::class.java)
    }
}
