package com.weave.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.context.ContextExecutorService
import io.micrometer.context.ContextSnapshotFactory
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import io.micrometer.tracing.handler.DefaultTracingObservationHandler
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext
import io.micrometer.tracing.otel.bridge.OtelTracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool

/**
 * 직접 만든 실행기에서 traceId가 끊기는 것과, Context Propagation으로 감싸면 이어지는 것을 남긴다(design 결정 4의 4번).
 * F-001의 ThreadPool 벌크헤드처럼 Spring `TaskExecutor`가 아닌 실행기는 `spring.task.execution.propagate-context`가 닿지 않는다.
 *
 * 작업 스레드의 traceId는 MDC가 아니라 OTel 현재 컨텍스트에서 읽는다. MDC를 채우려면 `ContextStorage.addWrapper`를
 * 전역에 더해야 하는데, 이 래퍼는 OTel 컨텍스트를 처음 쓰기 전에만 먹혀 같은 JVM의 다른 테스트 순서에 기대게 된다.
 * 로그의 MDC는 Boot가 같은 OTel 컨텍스트를 따라 채우고, 그 연결은 feed-service의 시나리오 테스트가 실제 로그로 확인한다.
 */
class ExecutorTracePropagationTest :
    FunSpec({
        val previousRegistry = ObservationThreadLocalAccessor.getInstance().observationRegistry
        val registry = ObservationRegistry.create()
        val snapshots = ContextSnapshotFactory.builder().build()
        val tracer =
            OtelTracer(
                OpenTelemetrySdk
                    .builder()
                    .setTracerProvider(SdkTracerProvider.builder().build())
                    .build()
                    .getTracer("executor-test"),
                OtelCurrentTraceContext(),
            ) { }

        fun currentTraceId(): String? = tracer.currentSpan()?.context()?.traceId()

        beforeSpec {
            registry.observationConfig().observationHandler(DefaultTracingObservationHandler(tracer))
            ObservationThreadLocalAccessor.getInstance().observationRegistry = registry
        }

        afterSpec {
            ObservationThreadLocalAccessor.getInstance().observationRegistry = previousRegistry
        }

        /** 관찰 범위 안에서 [block]을 돌리고, 요청 스레드의 traceId와 [block]이 읽은 traceId를 돌려준다. */
        fun inObservation(block: () -> String?): Pair<String, String?> {
            val observation = Observation.start("feed-request", registry)
            try {
                return observation.openScope().use {
                    currentTraceId().shouldNotBeNull() to block()
                }
            } finally {
                observation.stop()
            }
        }

        fun ExecutorService.traceIdOnWorker(): String? =
            try {
                submit<String?> { currentTraceId() }.get()
            } finally {
                shutdown()
            }

        test("F-004 직접 만든 고정 스레드 풀에서는 traceId가 끊긴다") {
            val (_, onWorker) = inObservation { Executors.newFixedThreadPool(1).traceIdOnWorker() }

            onWorker.shouldBeNull()
        }

        test("F-004 CompletableFuture의 기본 풀(ForkJoinPool.commonPool)에서는 traceId가 끊긴다") {
            val (_, onWorker) = inObservation { CompletableFuture.supplyAsync { currentTraceId() }.get() }

            onWorker.shouldBeNull()
        }

        test("F-004 고정 스레드 풀을 ContextExecutorService.wrap으로 감싸면 같은 traceId가 이어진다") {
            val (onRequest, onWorker) =
                inObservation { ContextExecutorService.wrap(Executors.newFixedThreadPool(1), snapshots).traceIdOnWorker() }

            onWorker shouldBe onRequest
        }

        test("F-004 ForkJoinPool.commonPool을 ContextExecutorService.wrap으로 감싸면 같은 traceId가 이어진다") {
            val (onRequest, onWorker) =
                inObservation {
                    val executor = ContextExecutorService.wrap(ForkJoinPool.commonPool(), snapshots)
                    CompletableFuture.supplyAsync({ currentTraceId() }, executor).get()
                }

            onWorker shouldBe onRequest
        }
    })
