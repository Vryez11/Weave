# 진행 메모: add-call-isolation

## 알아 둘 패턴
- 셸의 `ls`는 eza 별칭이라 경로 인자에서 깨진다. `command ls`를 쓴다. `grep --include=*.kt`는 zsh glob으로 깨진다. 따옴표로 감싼다.
- 테스트 기본값은 `feed-service/src/test/resources/config/application.yml`(상한·응답·연결 타임아웃 2초, 커넥션 대기 2초, 예산 5초)이다. main `application.yml`을 가리지 않고 값만 덮는다. 격리를 확인하는 시나리오는 클래스의 `@SpringBootTest(properties)`로 값을 직접 준다(design 결정 13).
- 속성 이름(1.x 테스트가 이미 쓴다. 바꾸면 테스트가 깨진다): `resilience4j.timelimiter.instances.<의존성>.timeout-duration`, `spring.http.serviceclient.<의존성>.connect-timeout`·`read-timeout`, `weave.http-client.connection-request-timeout`, `weave.http-client.groups.<의존성>.max-connections`, `weave.feed.time-budget`, `resilience4j.thread-pool-bulkhead.instances.<의존성>.core-thread-pool-size`·`max-thread-pool-size`, `resilience4j.circuitbreaker.instances.<의존성>.*`, `resilience4j.bulkhead.instances.like-toggle`.
- `IsolationSettingsTest`는 `spring.config.location=classpath:/application.yml`로 main 설정만 읽는다. 4장(설정값)에서만 통과한다. 그 전까지 실패하는 것이 정상이다.
- `FeedExperimentMetricsScenarioTest`의 벌크헤드 지표 이름은 Resilience4j Micrometer 연동의 `resilience4j_bulkhead_active_thread_count`, `resilience4j_bulkhead_queue_depth`(라벨 `name`)다. ThreadPool 벌크헤드에는 거절 카운터가 없어 `weave.bulkhead.rejected.calls`(태그 `name`, Prometheus `weave_bulkhead_rejected_calls_total`)를 직접 둔다. 대시보드 테스트가 이 이름을 기대한다.
- 실험 스크립트 형식의 기준: 기준별 판정은 `ExperimentVerdictScenarioTest` KDoc(`criteria`, `mismatchedCriteria`), 서킷 상태 이어 적기 `lib/circuits.sh`는 `ExperimentCircuitStateScenarioTest` KDoc, `report.json`(`criteria`, `resources.bulkheads`, `circuitState`)은 `ExperimentRunComposeScenarioTest` KDoc, 판정 표의 기대 열은 `ExperimentRunAllComposeScenarioTest` KDoc.
- `run.sh`는 feed-service를 `docker compose restart`로 다시 띄운다. 컨테이너 환경은 그대로라 `FEED_SPRING_APPLICATION_JSON`은 `up -d --force-recreate --no-deps feed-service`로 만들 때만 들어간다(`ComposeEnvironment.recreateFeedService`).
- Ralph 중에는 이미 커밋된 `src/test/` 파일을 훅이 막는다. 테스트 도구가 더 필요하면 새 파일에 둔다.
- 시나리오마다 값이 다르면 한 파일에 `@SpringBootTest(properties)` 클래스를 여럿 둔다(`FeedCallIsolationScenarioTest.kt`처럼). 실패 로그 찾기(`failureLines`), 동시 요청(`concurrently`), 포트(`localPort`)는 그 파일의 `private` 함수라 다른 파일에서 쓰려면 새 파일에 다시 둔다.
- 100ms 같은 짧은 상한을 주는 시나리오는 지연 없이 한 번 먼저 부른다(첫 호출의 연결 수립·클래스 로딩). 응답 타임아웃은 테스트 기본값 2초로 두면 TimeLimiter가 끊는지를 따로 볼 수 있다.
- 서킷 시나리오는 `beforeEach`에서 `CircuitBreakerRegistry`의 서킷을 모두 `reset()`해 앞 시나리오의 상태를 지운다. main의 느린 호출 경계(상한의 80%)가 섞이지 않게 테스트 속성의 `slow-call-duration-threshold`를 상한과 같게 준다. 서킷을 여는 것은 503 피드를 창 크기만큼 부른다(`FeedCircuitBreakerScenarioTest.kt`).
- `./gradlew ... -Pkotest.filter.specs=...`는 걸러지지 않고 전체가 돈다. `-Pkotest.filter.tests='*이름*'`는 다른 테스트를 skipped로 거르지만 Spring 컨텍스트는 클래스마다 다 뜬다(몇 분 걸린다). 결과는 `feed-service/build/test-results/integrationTest/TEST-*.xml`에서 본다(`grep -o 'testsuite name=... failures="[0-9]*"'`).
- Resilience4j 지표 이름은 design 결정 2의 "확인 결과" 표가 기준이다(`ResilienceAutoConfigurationTest`가 본다). 1.7 지표 테스트가 기대하는 이름과 같다.
- 테스트에서 환경 변수 형식의 이름을 주려면 `ApplicationContextInitializer`로 `SystemEnvironmentPropertySource`를 맨 앞에 붙이고, 이름을 `...-systemEnvironment`로 끝나게 둔다(Boot가 그래야 환경 변수 규칙으로 읽는다). `FeedIsolationOverrideScenarioTest.EnvironmentOverride`.
- 지표 이름(1.7 `FeedIsolationMetricsScenarioTest`가 기대한다): `resilience4j_circuitbreaker_state{name,state}`(지금 상태 값 1), `resilience4j_circuitbreaker_not_permitted_calls_total`, `resilience4j_bulkhead_active_thread_count`·`max_thread_pool_size`·`queue_depth`, `resilience4j_timelimiter_calls_total{kind="timeout"}`. 2.1에서 실제 이름이 다르면 멈춘다(테스트를 고쳐야 한다).
- 조회 격리는 `IsolatedCalls.call`(2.4) 한 곳이다. 실패 로그는 돌려준 future를 `IsolatedCalls`가 직접 끝낼 때만 남긴다(`!result.isDone`). 2.6에서 `FeedAssembler`가 마감에 그 future를 먼저 `cancel`/`completeExceptionally`하면 뒤늦은 timeout 로그가 겹치지 않는다. 시간 상한 스케줄러(`isolation-timeout`)는 `IsolatedCalls` 안에 있다.
- 서킷 기본 설정(`resilience4j.circuitbreaker.configs.default`)에 `record-failure-predicate: CircuitFailurePredicate`와 `ignore-exceptions: BulkheadFullException`이 있다. 인스턴스는 `base-config: default`로 이어진다. 2.7의 토글도 같은 `like` 서킷이라 이 판정을 그대로 쓴다.
- `io.micrometer:context-propagation`은 feed-service 컴파일 클래스패스에 없다(`libs/observability`가 `implementation`으로만 둔다). 2.5의 `TraceContextPropagator`가 `ContextSnapshotFactory`를 쓰려면 feed-service `build.gradle.kts`에 명시해야 한다(design 결정 8이 정한 수단이고 BOM이 버전을 정한다).

## 지금 실패하는 것이 정상인 테스트 (구현 전)
- **2.4 뒤 갱신(이 목록이 위 옛 항목보다 우선한다).** feed-service `integrationTest` 실패 18개: 끊김 재현 6(아래), 2.6 예산 1(`FeedCallIsolationBudgetScenarioTest` "합치면 예산을 넘는다"), 2.7 토글 1(`FeedLikeToggleIsolationScenarioTest` "like의 서킷이 열려 있다"), 2.8 전이 로그 1(`FeedCircuitBreakerScenarioTest` "서킷이 열린다"), 4장 `IsolationSettingsTest` 9. `FeedExperimentMetricsScenarioTest`, `FeedIsolationMetricsScenarioTest`, `FeedBulkheadTracePropagationOffTest`, 1.6 서킷 시나리오(전이 로그 말고)는 이제 통과한다.
- **끊김 재현(2.4, PRD F-001 수용 기준):** 벌크헤드 스레드에 트레이스 문맥이 넘어가지 않아 실패한다. 2.5에서 통과해야 한다.
  - `FeedTraceLogScenarioTest` "F-004 피드 요청의 다섯 호출: …"(post traceparent가 다름), "F-004 post가 끝난 뒤 시작한 호출: …"(user traceparent가 다름)
  - `FeedBulkheadTraceScenarioTest` "다섯 호출의 traceId", "벌크헤드 스레드의 실패 로그"
  - `FeedCallFailureLogScenarioTest` "F-004 post 뒤에 부른 호출이 실패한다: …"(like 실패 로그에 traceId 없음)
  - `FeedCallIsolationScenarioTest` "응답하지 않는 의존성"(시간 단언은 통과하고 user timeout 줄의 traceId만 없다. user는 post 벌크헤드 스레드에서 부르므로 받아 둘 MDC가 비어 있다)
  - 요청 스레드에서 부르는 post·recommendation·ad의 실패 로그는 MDC를 받아 두어 traceId가 있다(예: "느린 의존성" 통과). 끊기는 것은 벌크헤드 스레드에서 나간 HTTP 요청의 `traceparent`와, 벌크헤드 스레드에서 시작한 user·like 호출이다.
- (이하 2.4 전 기록)
- feed-service `integrationTest`: `FeedCallFailureLogScenarioTest`의 "F-001 호출 시간 상한을 넘긴다", `FeedExperimentMetricsScenarioTest`의 벌크헤드 지표 다섯, `IsolationSettingsTest`(Tomcat 단언 말고 모두).
- e2e `integrationTest`: `ExperimentCircuitStateScenarioTest` 셋(`lib/circuits.sh` 없음), `ExperimentVerdictScenarioTest`의 기준별 판정 넷(3.1·4.7).
- `FeedSlowDependencyScenarioTest`는 구현 전에도 통과한다. Boot 그룹 설정의 `read-timeout` 200ms만으로 HttpClient가 200ms에 끊어서다.
- `FeedCallIsolationScenarioTest.kt`(1.5): "느린 의존성", "응답하지 않는 의존성"(`FeedCallIsolationScenarioTest`), "합치면 예산을 넘는다"(`FeedCallIsolationBudgetScenarioTest`), "한 의존성이 상한을 다 쓴다"(`FeedCallIsolationBulkheadScenarioTest`)가 실패한다. "상한 안의 지연", "예산 안에 끝난다", "다른 의존성의 몫은 그대로다"는 구현 전에도 통과한다(회귀를 막는 시나리오).
- 1.7: `FeedLikeToggleIsolationScenarioTest`의 "like의 서킷이 열려 있다", `FeedBulkheadTracePropagationOffTest`, `FeedIsolationMetricsScenarioTest`가 실패한다. "like-service가 응답하지 않는다"(그룹 `read-timeout`이 이미 끊는다), `FeedBulkheadTraceScenarioTest` 둘(지금은 공유 풀의 `propagate-context`가 잇는다. 2.4 뒤 실패, 2.5에서 통과), `FeedIsolationOverrideScenarioTest`(레지스트리 바인딩은 이미 된다)는 구현 전에도 통과한다.
- `FeedCircuitBreakerScenarioTest.kt`(1.6): "4xx는 세지 않는다" 말고 모두 실패한다. "5xx가 이어진다", "열린 뒤에는 부르지 않는다", "서킷이 열린다"(전이 로그, 2.8에서 통과), "복구된 의존성"·"아직 아프다"(`FeedCircuitBreakerRecoveryScenarioTest`, 반열림을 기다리다 CLOSED로 실패), "벌크헤드 거절은 세지 않는다"(`FeedCircuitBreakerBulkheadScenarioTest`).

---

## 2026-11-06 - 1.1~1.4 (대화형, `/opsx:apply`)
- 1.1: 테스트 기본값 파일, `FeedAggregationScenarioTest`("서로 기다리지 않는다"를 상한 1초·예산 2초로, "느리다"를 `FeedSlowDependencyScenarioTest`로 옮겨 200ms 상한에서 400ms 안의 500으로), `FeedCallFailureLogScenarioTest`에 "호출 시간 상한을 넘긴다".
- 1.2: Resilience4j 의존성을 따로 커밋해 더했다(72e85fc). `IsolationSettingsTest`가 레지스트리 타입을 써서 2.1보다 먼저 필요했다. MVP 값 테스트 셋을 `IsolationSettingsTest`로 바꾸고, 지표 테스트의 공유 풀 단언을 벌크헤드로 바꿨다.
- 1.3: 판정 테스트에 기준별 기대 픽스처(`expectations-stage3.json`, `summary-S-02-availability-over.json`, `summary-S-02-both-over.json`)와 "고도화 3의 기대", `ExperimentCircuitStateScenarioTest`(픽스처 `query-range-circuit-state*.json`).
- 1.4: Compose 시나리오(프록시 지연, 짧게 돌린다, 리포트 항목, 시간 초과된 요청, Tomcat, 대시보드, 판정 표)와 `ComposeEnvironment.recreateFeedService`·`warmUpFeed`, `ServiceProcesses`의 넉넉한 격리 값. Compose 테스트는 5.2에서 돈다.
- 확인: `./gradlew ktlintCheck`, `:feed-service:test`, `:e2e:test` 통과. 통합 테스트는 위 "정상인 실패"만 실패한다.
- 다음(1.5): `FeedCallIsolationScenarioTest`. Ralph 루프로 넘긴다.
---

## 2026-11-06 - 1.5 시간 상한·예산·벌크헤드 시나리오 테스트 (Ralph)
- `call-isolation`의 시나리오 7개를 `FeedCallIsolationScenarioTest.kt`의 클래스 셋으로 옮겼다. 값이 달라 컨텍스트를 나눴다: 시간 상한(recommendation 200ms, user·post 100ms), 예산(250ms, 상한 모두 200ms), 벌크헤드(recommendation 동시 2, post·user·like 동시 10, 대기열 0, recommendation 상한 2초, 예산 3초).
- 무응답은 WireMock 지연 3초(테스트 기본 상한·응답 타임아웃 2초보다 길다). 벌크헤드 속성 이름은 `resilience4j.thread-pool-bulkhead.instances.<의존성>.core-thread-pool-size`·`max-thread-pool-size`·`queue-capacity`.
- 확인: `:feed-service:integrationTest`에서 새 시나리오 넷이 단언으로 실패하고(시간 2초 > 400ms, 200 ≠ 500, recommendation 요청 5 ≠ 2), 셋은 통과한다. 나머지 실패는 위 "정상인 실패"뿐이다. `ktlintCheck`, `compileKotlin compileTestKotlin`, `:feed-service:test` 통과.
- 바꾼 파일: `feed-service/src/test/kotlin/com/weave/feed/FeedCallIsolationScenarioTest.kt`(새 파일), `tasks.md`, `progress.md`.
- 다음(1.6): `FeedCircuitBreakerScenarioTest`. 서킷 속성은 `resilience4j.circuitbreaker.instances.<의존성>.*`(sliding-window-size, minimum-number-of-calls, failure-rate-threshold, wait-duration-in-open-state, permitted-number-of-calls-in-half-open-state, automatic-transition-from-open-to-half-open-enabled).
---

## 2026-11-06 - 1.6 서킷 시나리오 테스트 (Ralph)
- `call-isolation`의 서킷 시나리오 7개를 `FeedCircuitBreakerScenarioTest.kt`의 클래스 셋으로 옮겼다: 실패 분류·열림·전이 로그(창 10, 문턱 50%, 열림 유지 10초, recommendation·ad), 복구(열림 유지 1초, 반열림 시험 3개, 자동 반열림), 벌크헤드 거절(recommendation 동시 1, 상한 1초, 예산 2초, 나머지 벌크헤드 동시 10).
- 서킷이 열렸는지는 다음 피드가 하위 서비스까지 가는지(WireMock 요청 수)와 `circuit_open` 실패 로그로 본다. 저절로 반열림이 되는 것만 레지스트리 상태를 `eventually`로 기다린다(피드로 확인하면 시험 호출을 써 버린다). 서킷은 `beforeEach`에서 레지스트리로 `reset()`한다(KDoc).
- 확인: `:feed-service:integrationTest`에서 새 시나리오 여섯이 단언으로 실패하고 "4xx는 세지 않는다"는 통과한다. 나머지 실패는 위 "정상인 실패"뿐이다. `ktlintCheck`, `compileKotlin compileTestKotlin`, `:feed-service:test`, `openspec validate` 통과.
- 바꾼 파일: `feed-service/src/test/kotlin/com/weave/feed/FeedCircuitBreakerScenarioTest.kt`(새 파일), `tasks.md`, `progress.md`.
- 다음(1.7): 토글·traceId·지표·설정 시나리오. like 서킷을 여는 데도 같은 방법(창을 작게, 503 반복)을 쓸 수 있다. 토글 시나리오의 서킷 열기는 조회(`GET /feed`의 like 503)로 연다(같은 `like` 서킷).
---

## 2026-11-06 - 1.7 토글·traceId·지표·설정 시나리오 테스트 (Ralph)
- `call-isolation`의 남은 시나리오 일곱을 새 파일 다섯에 옮겼다: `FeedLikeToggleIsolationScenarioTest`(like 서킷 창 10·문턱 50%·열림 10초, like `read-timeout` 100ms. 서킷은 like 조회 503 피드 10번으로 연다), `FeedBulkheadTraceScenarioTest`(다섯 호출 traceId, like 500 실패 로그 traceId), `FeedBulkheadTracePropagationOffTest`(`resilience4j.thread-pool-bulkhead.configs.default.context-propagators=`로 비운다), `FeedIsolationMetricsScenarioTest`(서킷 상태·막은 호출·벌크헤드 사용 중/최대/대기열·타임아웃 지표, closed 값 1), `FeedIsolationOverrideScenarioTest`(파일 값 200ms를 환경 변수 형식 1000ms가 덮는다).
- 토글 무응답은 `DownstreamWireMock`에 지연 있는 토글 스텁이 없어 테스트 파일에서 `wireMock.server.stubFor(post(...).withFixedDelay(2_000))`로 둔다.
- 확인: `:feed-service:integrationTest` 113개 중 28개 실패 = 위 "정상인 실패" 25 + 1.7의 셋(단언 실패). `ktlintCheck`, `compileKotlin compileTestKotlin`, `:feed-service:test` 통과.
- 바꾼 파일: 위 테스트 파일 다섯(새 파일), `tasks.md`, `progress.md`.
- 다음(2.1): `ResilienceAutoConfigurationTest`. 1.7 지표 테스트가 기대하는 이름(위 패턴)이 실제와 같은지 함께 본다.
---

## 2026-11-06 - 2.1 Resilience4j의 Boot 4.1.2 호환 확인 (Ralph)
- `ResilienceAutoConfigurationTest`(`Integration`, 10개)를 새로 두고 모두 통과했다. 레지스트리 넷이 `instances.<이름>` 설정으로 인스턴스를 미리 만들고(`registry.find`), Prometheus에 서킷·ThreadPool·Semaphore 벌크헤드·TimeLimiter 지표가 `name` 라벨로 나오며, Boot 버전 검사 자동 설정이 시작을 막지 않는다. 값은 main과 겹치지 않는 숫자(창 7, 스레드 3, 동시 4·2, 1.5초)를 속성으로 줬다.
- 거절 지표는 Resilience4j에 없고, 대기열 0인 ThreadPool 벌크헤드가 차면 `BulkheadFullException`과 `onCallRejected` 이벤트가 오는 것을 확인했다(2.4의 `weave.bulkhead.rejected.calls` 근거).
- design 결정 2에 "확인 결과"(지표 이름 표)를, Open Questions에 확인했다는 줄을 더했다. `build.gradle.kts`의 "2.1에서 확인한다" 주석을 테스트 이름으로 바꿨다.
- 확인: 새 테스트 10개 통과, `ktlintCheck`, `:feed-service:compileTestKotlin`, `:feed-service:test`, `openspec validate --all --strict` 통과.
- 바꾼 파일: `feed-service/src/test/kotlin/com/weave/feed/ResilienceAutoConfigurationTest.kt`(새 파일), `feed-service/build.gradle.kts`, `design.md`, `tasks.md`, `progress.md`.
- 다음(2.2): `DownstreamFailure.of`에 `timeout`·`circuit_open`·`bulkhead_full`·`budget_exhausted`와 "서킷이 센다" 함수. `DownstreamFailureTest`는 이미 커밋된 테스트라 Ralph 중에는 고칠 수 없다. 새 단언은 새 파일(예: `DownstreamFailureIsolationCauseTest`)에 둔다.
---

## 2026-11-06 - 2.2 실패 분류 넓히기 (Ralph, 멈춤)
- 구현하지 않고 멈췄다. 바꾼 코드 없음.
- 이유: 이미 커밋된 테스트를 고쳐야 통과한다. `feed-service/src/test/kotlin/com/weave/feed/gateway/DownstreamFailureTest.kt`의 "F-004 그 밖의 ResourceAccessException은 io_failure다"(42~48행)가 `ResourceAccessException("I/O error", SocketTimeoutException("Read timed out"))`를 `io_failure`로 단언한다. design 결정 7의 표와 tasks 2.2는 바로 이 경우(원인이 `SocketTimeoutException`인 응답 타임아웃)를 `timeout`으로 바꾸라고 한다. 구현하면 이 단언이 깨지고, Ralph 중에는 훅이 이 파일 수정을 막는다. 2.2의 확인 방법도 `DownstreamFailureTest`에 단언을 더하는 것이라 같은 파일을 고쳐야 한다.
- 사람이 할 일(대화형 `/opsx:apply` 제안): `DownstreamFailureTest`의 그 테스트에서 `SocketTimeoutException` 예를 타임아웃이 아닌 I/O 오류(예: `IOException("Connection reset")`)로 바꾸고, 같은 파일에 design 결정 7 표의 예외마다 `cause`와 "서킷이 센다" 단언(응답 타임아웃·TimeLimiter `TimeoutException` → `timeout`, `CallNotPermittedException` → `circuit_open`, `BulkheadFullException` → `bulkhead_full`, 4xx·429·벌크헤드 거절은 세지 않음 등)을 더한 뒤 커밋한다. 테스트 쪽을 고치고 나면 2.2 구현은 Ralph가 이어 갈 수 있다. `budget_exhausted`는 예외가 아니라 `FeedAssembler`가 마감에서 남기는 값(결정 4)이라, 그 값을 어떤 입력으로 단언할지(전용 예외 타입을 둘지)도 테스트를 쓸 때 정해 두면 좋다.
- `DownstreamFailureConnectCauseTest`의 `ConnectionRequestTimeoutException` → `io_failure` 단언은 결정 7과 맞는다. `SocketTimeoutException` 판정이 이 예외를 잡지 않게만 하면 된다(이 예외는 `SocketTimeoutException`의 하위 타입이 아니라 `InterruptedIOException`의 하위 타입이다. 구현할 때 다시 확인한다).
- 바꾼 파일: `progress.md`.
---

## 2026-11-06 - 2.2 실패 분류와 막힘 풀기 (대화형)
- `DownstreamFailureTest`의 `io_failure` 예를 `IOException("Connection reset")`으로 바꾸고(응답 타임아웃은 `timeout`), design 결정 7 표의 예외마다 `cause`와 "서킷이 센다" 단언을 더했다.
- `DownstreamFailure.of`에 `circuit_open`(`CallNotPermittedException`), `bulkhead_full`(`BulkheadFullException`), `budget_exhausted`(새 예외 `TimeBudgetExhaustedException(dependency)`), `timeout`(`TimeoutException`, 원인이 `SocketTimeoutException`인 `ResourceAccessException`)을 더하고 `countsAsCircuitFailure(error)`를 뒀다. 2.4의 서킷 `record-failure-predicate`가 이 함수를 쓴다.
- 앞으로 막힐 것을 미리 풀었다: `FeedAssemblerTest`가 `FeedAssembler(..., timeBudget = Duration, clock = Clock)`으로 만든다. 그래서 `FeedAssembler` 생성자에 `@Value("${weave.feed.time-budget:#{null}}") timeBudget: Duration?`과 `clock: Clock = Clock.systemUTC()`를 지금 더했다(아직 쓰지 않는다). 2.6은 이 이름을 그대로 쓴다. 값이 4.3 전에는 없을 수 있어 nullable이다. 4.3 뒤에 non-null로 바꿔도 테스트는 그대로 컴파일된다.
- "F-001 호출 시간 상한을 넘긴다"(`FeedCallFailureLogScenarioTest`)는 이제 통과한다(그룹 `read-timeout` 200ms가 `SocketTimeoutException` → `timeout`으로 한 줄).
- 확인: `:feed-service:test` 통과. `:feed-service:integrationTest` 129개 중 27개 실패, 모두 위 "정상인 실패" 목록(2.3 이후 구현을 기다린다).
- 다음(2.3): HTTP 클라이언트의 그룹별 타임아웃·풀 크기 속성. Ralph로 넘긴다.
---


## 2026-11-06 - 2.3 HTTP 클라이언트의 그룹별 타임아웃·풀 크기 속성 (Ralph)
- `HttpClientProperties`(`@ConfigurationProperties("weave.http-client")`: `max-connections-per-route`, `connection-request-timeout`, `groups.<그룹>.max-connections`)를 새로 두고 `HttpClientConfig`가 켠다. `@Value` 읽기를 이 클래스로 바꿨다. 값은 넣지 않았다(main `application.yml` 그대로).
- 커넥션 대기 상한은 `clientHttpRequestFactoryBuilder` Bean의 `withDefaultRequestConfigCustomizer`로 모든 그룹에 넣는다. 그룹별 풀 크기는 `ConnectionPoolMetrics`가 그룹마다 Bean 빌더에 `withMaxConnections`(HttpClientConfig.kt의 확장 함수)를 더해 덮는다. Bean 반환 타입을 `HttpComponentsClientHttpRequestFactoryBuilder`로 좁혔다.
- Boot 그룹 설정의 `connect-timeout`·`read-timeout`은 이미 그룹 HttpClient에 들어간다는 것을 테스트로 확인했다(그룹마다 다른 값이 섞이지 않는다).
- 확인: 새 `HttpClientBuilderSettingsTest`(단위 3) 통과, 새 `HttpClientGroupSettingsTest`(`Integration` 3), 기존 `HttpClientGroupTest`·`HttpClientPoolSizePropertyTest`·`HttpClientRetryDefaultsTest` 통과. `:feed-service:integrationTest` 실패 27개는 위 "정상인 실패"와 같다. `ktlintCheck`, `:feed-service:test`, `openspec validate` 통과.
- 바꾼 파일: `feed-service/src/main/kotlin/com/weave/feed/client/HttpClientProperties.kt`(새 파일), `HttpClientConfig.kt`, `ConnectionPoolMetrics.kt`, 테스트 두 파일(새 파일), `feed-service/CLAUDE.md`, `tasks.md`, `progress.md`.
- 다음(2.4): `IsolatedCalls`. 2.4 뒤 trace 테스트가 끊김으로 실패하는 것은 정상이고, 이름을 "끊김 재현"으로 적는다.
---

## 2026-11-06 21:28 - 2.4 `IsolatedCalls`로 Gateway 다섯의 조회를 감싼다 (Ralph)
- `IsolatedCalls`(`com.weave.feed.gateway`)를 새로 뒀다. 서킷 `executeCompletionStage` → TimeLimiter `executeCompletionStage(자체 스케줄러)` → ThreadPool 벌크헤드 `executeSupplier` 순서로 감싸고, 결과 future에서 실패 로그를 한 번 남긴다(부른 스레드의 MDC로). 의존성 다섯의 인스턴스를 시작할 때 꺼내 두고, `weave.bulkhead.rejected.calls{name}`을 0부터 등록해 `onCallRejected`에서 센다. 서킷 판정 `CircuitFailurePredicate`(→ `DownstreamFailure.countsAsCircuitFailure`)도 같은 파일이다.
- Gateway 다섯이 `applicationTaskExecutor` 대신 `IsolatedCalls`를 받는다. 토글은 그대로(2.7).
- `application.yml`에 구조만 넣었다: 서킷 `configs.default`(실패 판정 함수, `ignore-exceptions: BulkheadFullException`), TimeLimiter `configs.default.cancel-running-future: true`, 벌크헤드 `configs.default.queue-capacity: 0`, 세 모듈 모두 `instances.<의존성>.base-config: default`. 값은 Resilience4j 기본값 그대로다(4장). 벌크헤드 거절을 성공으로 세지 않는 이유를 design 결정 7에 한 줄 더했다.
- 확인: `:feed-service:integrationTest` 실패 18개가 위 "2.4 뒤 갱신" 목록과 같다(두 번 돌려 같았다). 1.5의 상한·벌크헤드 시나리오("응답하지 않는 의존성"은 traceId만 끊김 재현), 1.6 서킷 시나리오(전이 로그 말고), 1.1의 바뀐 시나리오(`FeedAggregationScenarioTest` 23개, `FeedSlowDependencyScenarioTest`, "호출 시간 상한을 넘긴다") 통과. `ktlintCheck`, `compileKotlin compileTestKotlin`, `:feed-service:test`, `openspec validate` 통과. e2e는 돌리지 않았다(Docker 필요, 이 작업의 확인 항목이 아니다).
- 바꾼 파일: `feed-service/src/main/kotlin/com/weave/feed/gateway/IsolatedCalls.kt`(새 파일), `Gateways.kt`, `feed-service/src/main/resources/application.yml`, `feed-service/CLAUDE.md`, `design.md`, `tasks.md`, `progress.md`.
- 다음(2.5): `TraceContextPropagator`. 위 "끊김 재현" 6개가 통과해야 한다. context-propagation 의존성 명시가 필요하다(패턴 참고). 시간 상한 실패 로그는 이미 MDC를 받아 두므로 스케줄러 쪽은 건드리지 않아도 된다.
---
