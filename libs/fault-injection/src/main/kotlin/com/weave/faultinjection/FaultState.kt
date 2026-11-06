package com.weave.faultinjection

import java.util.concurrent.atomic.AtomicReference

/**
 * 지금 장애 설정. 메모리에만 있어 다시 띄우면 [defaults]다(design 결정 2).
 * 요청 스레드가 읽는 동안 엔드포인트가 바꾸므로 통째로 갈아 끼운다. 바꾸는 함수가 던지면 그대로 둔다.
 */
class FaultState(
    private val defaults: FaultConfig,
) {
    private val current = AtomicReference(defaults)

    fun current(): FaultConfig = current.get()

    fun update(change: (FaultConfig) -> FaultConfig): FaultConfig = current.updateAndGet(change)

    fun reset(): FaultConfig = defaults.also { current.set(it) }
}
