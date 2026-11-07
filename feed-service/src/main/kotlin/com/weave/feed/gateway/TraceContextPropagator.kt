package com.weave.feed.gateway

import io.github.resilience4j.core.ContextPropagator
import io.micrometer.context.ContextSnapshot
import io.micrometer.context.ContextSnapshotFactory
import java.util.Optional
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * ThreadPool 벌크헤드 스레드에 호출한 스레드의 트레이스 문맥을 넘긴다 (add-call-isolation design 결정 8, 아키텍처 7-3).
 *
 * `resilience4j.thread-pool-bulkhead.configs.default.context-propagators`로 등록하고, Resilience4j가 인자 없는 생성자로 만든다.
 * [retrieve]는 벌크헤드에 일을 넣는 스레드에서 Micrometer Context Propagation 스냅샷을 뜨고, [copy]는 벌크헤드 스레드에서 그 스냅샷을
 * 스레드 로컬에 펼치며, [clear]는 일이 끝나면 펼친 것을 닫는다. 관찰(스팬)과 MDC가 함께 넘어간다. `applicationTaskExecutor`의
 * `propagate-context`(Boot의 `ContextPropagatingTaskDecorator`)와 같은 수단이다.
 *
 * [clear]는 [retrieve]의 값만 받으므로, [copy]가 연 scope는 벌크헤드 스레드의 스레드 로컬에 두었다가 닫는다.
 */
class TraceContextPropagator : ContextPropagator<ContextSnapshot> {
    override fun retrieve(): Supplier<Optional<ContextSnapshot>> = Supplier { Optional.of(traceContexts.captureAll()) }

    override fun copy(): Consumer<Optional<ContextSnapshot>> =
        Consumer { snapshot -> snapshot.ifPresent { scopes.set(it.setThreadLocals()) } }

    override fun clear(): Consumer<Optional<ContextSnapshot>> =
        Consumer {
            scopes.get()?.close()
            scopes.remove()
        }

    private companion object {
        val scopes = ThreadLocal<ContextSnapshot.Scope>()
    }
}

/** 트레이스 문맥 스냅샷을 뜨는 곳. [TraceContextPropagator]와 [IsolatedCalls]가 같이 쓴다. */
internal val traceContexts: ContextSnapshotFactory = ContextSnapshotFactory.builder().build()
