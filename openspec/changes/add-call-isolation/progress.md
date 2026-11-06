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

## 지금 실패하는 것이 정상인 테스트 (구현 전)
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
