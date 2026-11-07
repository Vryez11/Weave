# Tasks

## 1. 준비와 시나리오 테스트

시나리오(WHEN/THEN)를 실패하는 테스트로 옮긴다. 구현이 없으니 바뀐 동작을 단언하는 테스트는 `integrationTest`에서 실패하고, `test`(Stop 훅)는 통과해야 한다. 테스트 이름에 `F-001`을 넣고, traceId·이벤트 로그 테스트는 `F-004`도 넣는다(design 결정 13).

**1.1~1.4는 이미 커밋된 테스트를 고치므로 Ralph 루프가 아니라 대화형(`/opsx:apply`)으로 먼저 끝낸다**(하네스 규칙 2-2의 훅). Ralph가 이 넷 중 체크되지 않은 작업을 만나면 구현하지 말고 progress.md에 적은 뒤 `<promise>BLOCKED</promise>`로 멈춘다.

- [x] 1.1 (대화형) 테스트 환경의 넉넉한 기본값과 `feed-aggregation` 델타를 테스트에 옮긴다.
  - `feed-service/src/test/resources/config/application.yml`에 호출 시간 상한·응답·연결 타임아웃 2초, 시간 예산 5초를 둔다(design 결정 13). 속성 이름은 design 결정 3·4·12를 따른다. 아직 구현이 없어 읽히지 않아도 된다.
  - `FeedAggregationScenarioTest`: "서로 기다리지 않는다"는 상한 1초·예산 2초를 속성으로 주고, "하위 서비스 하나가 느리다"는 상한 200ms에서 400ms 안에 500이 오는지로 바꾼다. 테스트 이름의 기능 ID는 `F-001`로 바꾼다.
  - `FeedCallFailureLogScenarioTest`에 "호출 시간 상한을 넘긴다"(실패 줄 하나)를 더한다.
  - 확인: `./gradlew :feed-service:integrationTest`에서 바꾼 두 시나리오와 새 시나리오만 실패하고 나머지는 통과한다. `./gradlew :feed-service:test`는 통과한다.
- [x] 1.2 (대화형) MVP 구성값을 단언하던 feed-service 테스트를 고도화 3 값으로 바꾼다.
  - `MvpTimeoutDefaultsTest`, `HttpClientPoolCapacityTest`, `TaskExecutorCapacityTest`를 `IsolationSettingsTest` 하나로 바꾼다. `spring.config.location=classpath:/application.yml`로 테스트 기본값(1.1)을 빼고 main 설정만 읽어, 의존성마다 HttpClient의 연결·응답·커넥션 대기 타임아웃과 풀 크기, 서킷·TimeLimiter·벌크헤드 레지스트리의 인스턴스 설정, 시간 예산이 design 결정 3~7의 값인지 본다. Tomcat 최대 스레드 200 단언은 그대로 옮긴다. `TaskExecutorKeepAliveTest`, `HttpClientRetryDefaultsTest`는 그대로 둔다.
  - `FeedExperimentMetricsScenarioTest`의 공유 풀 단언을 의존성별 벌크헤드 사용 중 스레드·대기열로 바꾼다(`fault-experiments` "feed-service의 실험 지표"). 지표 이름은 2.1에서 확인한 것을 쓴다고 KDoc에 적고, 여기서는 `resilience4j_bulkhead` 접두어와 `name="<의존성>"`으로 찾는다.
  - `docs/architecture.md` 4-2의 "확인 방법"에 MVP 값 테스트가 F-001에서 `IsolationSettingsTest`로 바뀌었고 MVP 값은 이 표가 기록이라고 적는다.
  - 확인: `./gradlew :feed-service:integrationTest`에서 이 두 파일의 바뀐 단언이 실패하고, `./gradlew :feed-service:test`는 통과한다.
- [x] 1.3 (대화형) 실험 세트의 판정·리포트 테스트를 델타대로 고친다.
  - `ExperimentVerdictScenarioTest`가 기본 `expectations.json` 대신 픽스처 기대 파일(`e2e/src/test/resources/experiments/`)을 쓰게 바꾼다. 단계가 바뀌어도 판정 로직 테스트가 흔들리지 않게 한다.
  - "기준별 기대와 같다", "기준별 기대와 다르다"를 픽스처로 더하고, "고도화 3의 기대"는 실제 `experiments/expectations.json`을 읽어 본다. 출력 JSON의 `criteria` 형식(design 결정 10)을 KDoc에 적는다.
  - 리포트의 서킷 상태 이어 적기("서킷이 열린 시나리오")를 미리 만든 Prometheus `query_range` 결과 픽스처로 새 테스트에 둔다. 스크립트 이름과 인자는 이 작업에서 정해 KDoc에 적는다.
  - 확인: `./gradlew :e2e:integrationTest`에서 새·바뀐 판정 테스트만 실패하고, `./gradlew :e2e:test`는 통과한다.
- [x] 1.4 (대화형) Compose 테스트와 종단 간 테스트를 델타대로 고친다.
  - "프록시에 지연을 넣는다", "짧게 돌린다", "시간 초과된 요청"(`docker compose pause`), "리포트 항목"(기준별 충족, 벌크헤드, 서킷 상태), "Tomcat 스레드가 모두 묶여도 긁힌다"(`FEED_SPRING_APPLICATION_JSON`으로 recommendation 격리를 넓히고, 끝나면 변수 없이 feed-service를 다시 띄운다), "대시보드를 불러온다"(서킷·벌크헤드 패널)를 고친다(design 결정 10).
  - Compose 테스트 지원 코드에 시나리오 전 준비 요청을 보내는 도우미를 두고, 첫 `GET /feed`를 단언하는 Compose 테스트가 쓰게 한다.
  - `ServiceProcesses`가 feed-service 프로세스에 1.1과 같은 넉넉한 값을 명령행 인자로 준다(design 결정 13).
  - 확인: `./gradlew :e2e:compileTestKotlin`이 통과하고 `./gradlew :e2e:test :e2e:integrationTest`가 1.3에서 실패하게 둔 것 말고는 통과한다. Compose 테스트는 5.2에서 돌린다.
- [x] 1.5 `call-isolation`의 시간 상한·예산·벌크헤드 시나리오를 `FeedCallIsolationScenarioTest`로 옮긴다.
  - 대상: "느린 의존성", "응답하지 않는 의존성", "상한 안의 지연", "의존성마다는 상한 안이지만 합치면 예산을 넘는다", "예산 안에 끝난다", "한 의존성이 상한을 다 쓴다", "다른 의존성의 몫은 그대로다".
  - WireMock 지연은 `withFixedDelay`, 무응답은 지연을 상한보다 충분히 길게 준다. 값은 시나리오마다 `@SpringBootTest(properties)`로 준다(속성 이름은 design 결정 3~5·12).
  - 확인: `./gradlew :feed-service:integrationTest`에서 이 시나리오들이 실패하고(실패 원인이 컴파일 오류가 아니다), `./gradlew :feed-service:test`는 통과한다.
- [x] 1.6 `call-isolation`의 서킷 시나리오를 `FeedCircuitBreakerScenarioTest`로 옮긴다.
  - 대상: "5xx가 이어진다", "4xx는 세지 않는다", "벌크헤드 거절은 세지 않는다", "열린 뒤에는 부르지 않는다", "복구된 의존성", "아직 아프다", "서킷이 열린다"(상태 전이 로그).
  - 서킷이 열렸는지는 스펙의 THEN대로 하위 서비스가 받은 요청 수와 로그로 판단한다. 저절로 바뀌는 상태는 `eventually`로 기다린다. 서킷은 컨텍스트에 남으므로 시나리오마다 컨텍스트를 나누거나 테스트 끝에 상태를 되돌리는 방법을 KDoc에 적는다.
  - 확인: 1.5와 같다.
- [x] 1.7 `call-isolation`의 토글·traceId·지표·설정 시나리오를 옮긴다.
  - `FeedLikeToggleIsolationScenarioTest`: "like의 서킷이 열려 있다", "like-service가 응답하지 않는다".
  - `FeedBulkheadTraceScenarioTest`: "다섯 호출의 traceId", "벌크헤드 스레드의 실패 로그". `FeedBulkheadTracePropagationOffTest`: "전파가 없으면 끊긴다"(design 결정 8의 끄는 설정을 속성으로 준다).
  - `FeedIsolationMetricsScenarioTest`: "지표가 있다". `FeedIsolationOverrideScenarioTest`: "환경 변수로 상한을 바꾼다". 환경 변수 형식의 이름(예: `RESILIENCE4J_TIMELIMITER_INSTANCES_RECOMMENDATION_TIMEOUTDURATION`)이 바인딩되는지 본다. 테스트에서 환경 변수를 주는 방법(예: 시스템 환경 속성 소스를 앞에 붙인다)을 KDoc에 적는다.
  - 확인: 1.5와 같다.

## 2. 격리 구현

- [x] 2.1 Resilience4j를 더하고 Boot 4.1.2 호환을 확인한다(design 결정 2).
  - 의존성(`io.github.resilience4j:resilience4j-spring-boot4:2.4.0`)은 1.2의 `IsolationSettingsTest`가 레지스트리 타입을 써야 해서 1.2에서 따로 커밋해 더했다(72e85fc). 그때 기존 통합 테스트의 컨텍스트가 뜨는 것까지 봤다. ThreadPool 벌크헤드에는 거절 카운터 지표가 없다(`ThreadPoolBulkheadMetricNames`).
  - `ResilienceAutoConfigurationTest`(`Integration`)에서 레지스트리 네 개가 `resilience4j.*` 설정의 인스턴스를 만들고, `/actuator/prometheus`에 서킷·벌크헤드(ThreadPool, Semaphore)·TimeLimiter 지표가 `name` 라벨로 나오는지 본다. 실제 지표 이름과 벌크헤드 거절을 지표로 셀 수 있는지를 design 결정 2와 Open Questions에 적는다.
  - 하나라도 안 되면 우회하지 말고 멈춘다(progress.md에 적고 BLOCKED).
  - 확인: `./gradlew :feed-service:integrationTest -Pkotest.filter.tests='*ResilienceAutoConfiguration*'`가 통과한다.
- [x] 2.2 실패 분류를 넓힌다(design 결정 7). (대화형으로 했다. Ralph가 커밋된 `DownstreamFailureTest`를 고쳐야 해서 멈췄다. 함수 이름 `DownstreamFailure.countsAsCircuitFailure`, 예산 초과는 `TimeBudgetExhaustedException`)
  - `DownstreamFailure.of`에 `timeout`, `circuit_open`, `bulkhead_full`, `budget_exhausted`를 더하고, 서킷이 셀 실패인지 돌려주는 함수를 같은 곳에 둔다. 응답 타임아웃(`SocketTimeoutException`이 원인)은 `io_failure`가 아니라 `timeout`이다.
  - feed-service `CLAUDE.md`의 실패 원인 설명을 고친다.
  - 확인: `DownstreamFailureTest`에 design 결정 7 표의 예외마다 `cause`와 "서킷이 센다" 단언이 있고 `./gradlew :feed-service:test`가 통과한다.
- [x] 2.3 HTTP 클라이언트의 의존성별 타임아웃과 풀 크기를 설정으로 받게 한다(design 결정 3, 5). 값은 아직 넣지 않는다.
  - `spring.http.serviceclient.<그룹>.connect-timeout`, `read-timeout`이 그룹 HttpClient에 적용되는지 확인하고, `weave.http-client.connection-request-timeout`, `weave.http-client.groups.<그룹>.max-connections`를 더한다. `ConnectionPoolMetrics`의 요청 팩토리 재생성 순서를 지킨다(feed-service `CLAUDE.md`).
  - 확인: 속성을 준 `HttpClientGroupTest`류의 새 단위·통합 테스트(그룹별 연결·응답·커넥션 대기 타임아웃과 풀 크기를 `HttpClientPools`로 읽는다)가 통과하고 기존 `HttpClientGroupTest`, `HttpClientRetryDefaultsTest`가 통과한다.
- [x] 2.4 `IsolatedCalls`로 Gateway 다섯의 조회를 감싼다(design 결정 1, 5, 7).
  - 서킷 → TimeLimiter → ThreadPool 벌크헤드 → 호출. 실패 로그는 감싼 future의 결과에서 한 번만 남긴다. Gateway는 `applicationTaskExecutor`를 더는 받지 않는다.
  - 벌크헤드 거절 카운터 `weave.bulkhead.rejected.calls`(태그 `name`)를 의존성마다 0부터 등록하고 `onCallRejected`에서 센다(design Open Questions).
  - `application.yml`에는 구조만 둔다: 인스턴스 이름 다섯, 서킷의 실패 판정 함수, 벌크헤드 대기열 0. 값은 4장에서 넣는다.
  - 이 작업 뒤 trace 테스트(`FeedTraceLogScenarioTest`의 다섯 호출·post 뒤 호출, `FeedCallFailureLogScenarioTest`의 traceId 단언, 1.7의 traceId 시나리오)는 벌크헤드 스레드에 문맥이 넘어가지 않아 실패한다. 그대로 두고 실패한 테스트 이름을 progress.md에 "끊김 재현"으로 적는다(PRD F-001 수용 기준).
  - 확인: 1.5의 상한·벌크헤드 시나리오, 1.6의 서킷 시나리오 중 토글·전이 로그를 뺀 것, 1.1의 바뀐 시나리오가 통과한다. 위 trace 테스트 말고 기존 feed-service 테스트가 통과한다.
- [x] 2.5 벌크헤드 스레드에 트레이스 문맥을 넘긴다(design 결정 8). (구현은 Ralph가 했다(9e2956e). 공유 풀의 끊김 기록 `FeedTracePropagationOffTest`는 기본 구성에서 공유 풀을 거치지 않게 돼 대화형으로 Semaphore 모드로 옮겼고, 2.9까지는 실패한다)
  - `TraceContextPropagator`를 만들고 `thread-pool-bulkhead.configs.default.context-propagators`에 등록한다.
  - `docs/architecture.md` 7-3의 표 두 개에 ThreadPool 벌크헤드 줄(전파 없이·고친 뒤·테스트)을 채운다.
  - 확인: 2.4에서 실패하던 trace 테스트와 1.7의 traceId 시나리오가 통과하고, `FeedBulkheadTracePropagationOffTest`도 통과한다(끊김을 단언한다).
- [x] 2.6 요청 시간 예산을 넣는다(design 결정 4).
  - `FeedAssembler`가 시계(`Clock`)와 `weave.feed.time-budget`을 받아 마감 시각으로 기다리고, 남은 시간이 없으면 user·like를 부르지 않으며, 마감이 지나면 끝나지 않은 의존성마다 `budget_exhausted` 로그를 남기고 취소한다.
  - 확인: 남은 시간 계산과 "부르지 않음"을 보는 `FeedAssemblerTest` 단위 테스트가 통과하고, 1.5의 예산 시나리오 두 개가 통과한다.
- [x] 2.7 좋아요 토글을 격리한다(design 결정 6).
  - `like` 서킷과 Semaphore 벌크헤드 `like-toggle`(기다리지 않음)을 거친다. 실패 로그는 지금처럼 한 줄이다.
  - 확인: 1.7의 토글 시나리오 두 개와 기존 토글 시나리오(한 번만 요청, 503이면 500)가 통과한다.
- [x] 2.8 서킷 상태 전이 로그를 남긴다(design 결정 9).
  - 레지스트리의 `onEntryAdded`에서 서킷마다 전이 소비자를 건다.
  - 확인: 1.6의 "서킷이 열린다"와 1.7의 "지표가 있다", "환경 변수로 상한을 바꾼다"가 통과한다.
- [x] 2.9 Semaphore 모드를 넣는다(design 결정 11).
  - `weave.isolation.bulkhead-type: semaphore`면 `applicationTaskExecutor`에서 돌리고 Semaphore 벌크헤드로 감싼다. 기본은 `thread-pool`.
  - Semaphore 모드에서는 공유 풀의 `propagate-context`만으로 문맥을 잇는다. `IsolatedCalls`가 호출 자체에 따로 문맥을 펼치지 않는다(그러면 `FeedTracePropagationOffTest`의 끊김 기록이 성립하지 않는다).
  - 확인: `spring.threads.virtual.enabled=true`와 `semaphore`를 준 `FeedSemaphoreIsolationScenarioTest`(`Integration`)에서 피드가 200이고, 다섯 호출의 traceId가 `X-Trace-Id`와 같으며, 동시 호출 상한을 넘은 호출이 `bulkhead_full`로 끝난다. `FeedTracePropagationOffTest`(Semaphore 모드의 끊김 기록)가 통과한다. `./gradlew :feed-service:test :feed-service:integrationTest`가 1.2의 값 단언 말고는 통과한다.
- [x] 2.10 아키텍처 문서를 구현에 맞춘다.
  - 1장(장애 대응 줄: 레지스트리로 감싸는 방식), 4-2 표(고도화 3 줄), 5-2(어노테이션 대신 `IsolatedCalls`, design 결정 1), 5-4(순서가 코드 한 곳에 있다), 5-5(429는 세지 않음, 결정 7), 12·13장의 F-001 항목을 고친다. 값(4-3, 5-6)은 4장에서 채운다.
  - 확인: 문서의 클래스·속성 이름이 코드에 있다(`grep`으로 확인).

## 3. 실험 세트

- [ ] 3.1 기준별 판정을 넣는다(design 결정 10).
  - `lib/verdict.sh`가 문자열과 객체 기대를 모두 읽고 `criteria`를 출력한다. `run-all.sh` 판정 표의 "기대" 열을 객체면 `가용성 miss · p99 meet`으로 적는다. `report.md`의 판정 줄에 기준별 충족 여부를 더한다.
  - `expectations.json`은 아직 바꾸지 않는다(4.7).
  - 확인: 1.3의 판정 테스트 중 "고도화 3의 기대"를 뺀 것이 `./gradlew :e2e:integrationTest`로 통과한다.
- [ ] 3.2 리포트의 자원·서킷 항목을 바꾼다(design 결정 10).
  - 공유 풀 활성·대기열을 의존성별 벌크헤드 사용 중 스레드·대기열로, 서킷 상태 "없음"을 의존성별로 거친 상태로 바꾼다. 서킷 상태 이어 적기는 1.3에서 정한 `lib/` 스크립트로 둔다.
  - `docs/architecture.md` 9-3의 리포트 표를 고친다.
  - 확인: 1.3의 "서킷이 열린 시나리오" 테스트가 통과하고, `bash -n`이 바뀐 스크립트에서 통과한다.
- [ ] 3.3 대시보드에 서킷·벌크헤드 행을 더하고 공유 풀 패널을 지운다(design 결정 10).
  - 질의는 분자가 빌 수 있으면 0으로 채운다(아키텍처 7-4의 규칙).
  - `docs/architecture.md` 7-4 표를 고친다.
  - 확인: `jq . docker/grafana/dashboards/feed-experiments.json`이 통과하고, 패널 질의에 `applicationTaskExecutor`가 없다. Compose 확인은 5.2.
- [ ] 3.4 compose.yaml에서 feed-service에 `SPRING_APPLICATION_JSON: ${FEED_SPRING_APPLICATION_JSON:-{}}`를 넘긴다(design 결정 10, 11).
  - `docs/architecture.md` 9-1과 11장, `CLAUDE.md` 명령 표의 실험 줄에 덮어쓰는 방법을 적는다.
  - 확인: `docker compose config`가 통과하고, `FEED_SPRING_APPLICATION_JSON='{"a":1}' docker compose config`의 feed-service 환경에 그 값이 있다.

## 4. 설정값

**Ralph 루프는 이 묶음을 하지 않는다.** 여기에 오면 progress.md에 "3번 묶음까지 끝남, 4번 묶음은 사람과 함께 `/opsx:apply`로 진행"이라고 적고 `<promise>BLOCKED</promise>`로 멈춘다. Compose 측정은 반복 세션의 명령 시간 제한(10분)을 넘을 수 있고 노트북 상태를 사람이 맞춰야 한다. 값을 넣는 작업은 하나씩 커밋하고 본문에 근거를 적는다(커밋 컨벤션).

- [ ] 4.1 평시 꼬리 지연과 토글 지연을 잰다(design 결정 3, 6).
  - 이 change의 2장 전 구성(`develop`)으로 Compose를 띄워 `experiments/run.sh S-01`(400 RPS)을 돌리고, 측정 구간의 `http_client_requests_seconds_bucket`에서 의존성별로 89.48ms·178.96ms 버킷과 그 80% 근처 버킷(71.58ms·143.17ms)을 넘는 비율을 구한다.
  - 같은 환경에서 feed-service의 토글을 1,000번 보내 p99를 잰다.
  - 상한을 넘는 비율이 0.01%를 넘는 의존성이 있거나 토글 p99가 50ms를 넘으면 값을 넣지 않고 멈춘다.
  - 결과를 design 결정 3·6과 `docs/architecture.md` 4-3에 적는다.
  - 확인: design과 아키텍처에 결과 폴더 이름과 비율이 있다.
- [ ] 4.2 호출 시간 상한과 HTTP 타임아웃 값을 넣는다(design 결정 3). 확인: `IsolationSettingsTest`의 타임아웃 단언이 통과한다.
- [ ] 4.3 요청 시간 예산 250ms를 넣는다(design 결정 4). 확인: `IsolationSettingsTest`의 예산 단언이 통과한다.
- [ ] 4.4 벌크헤드 40, 커넥션 풀 40(like 50)을 넣고 공유 풀 설정(`spring.task.execution.pool.core-size`)을 지운다(design 결정 5). 확인: `IsolationSettingsTest`의 벌크헤드·풀 단언이 통과한다.
- [ ] 4.5 토글 Semaphore 상한 10을 넣는다(design 결정 6). 확인: `IsolationSettingsTest`의 토글 단언이 통과한다.
- [ ] 4.6 서킷 설정값을 넣는다(design 결정 7).
  - `docs/architecture.md` 5-6에 값과 근거를 표로 적고, PRD 10장 "남은 것"의 S-08 복구 시간 목표를 "정한 것"(20초, design 결정 10)으로 옮긴다.
  - 확인: `IsolationSettingsTest`가 모두 통과하고 `./gradlew ktlintCheck build`가 1.3의 "고도화 3의 기대" 말고는 통과한다.
- [ ] 4.7 평시를 다시 재고 고도화 3의 기대를 적는다.
  - `./gradlew bootJar` 뒤 Compose를 다시 띄워 `experiments/run.sh S-01`이 0으로 끝나는지, 리포트의 서킷 상태가 모두 `CLOSED`인지, 측정 구간 타임아웃 수(`resilience4j_timelimiter_calls_total{kind="timeout"}` 등)를 본다. 결과를 design 결정 3에 적는다.
  - `experiments/expectations.json`을 고도화 3으로 바꾼다(design 결정 10). 설정값 커밋과 섞지 않는다.
  - 확인: S-01이 0으로 끝나고 `./gradlew ktlintCheck build`가 통과한다.

## 5. 실험과 기록

**Ralph 루프는 이 묶음을 하지 않는다**(4장과 같은 이유, 합쳐 약 1시간 반).

- [ ] 5.1 장애 실험 세트를 돌려 고도화 3 결과를 기록한다.
  - `experiments/run-all.sh`를 기본값으로 돌린다. 기대와 다른 시나리오가 있으면 기대를 고치지 말고 원인을 찾는다. 기대 자체가 틀렸다고 판단되면 멈추고 사람에게 묻는다.
  - `docs/experiments/call-isolation.md`에 기준선과 같은 형식의 표를 만들고, 기준선과 나란히 p99·가용성·Tomcat·벌크헤드·커넥션 풀·CPU·서킷 상태를 비교한다. S-02·S-03에서 post·user·like 호출 p99가 기준선 S-01과 비슷한지(격리 효과), S-08 복구 시간과 목표 20초를 적는다. 잡음 범위는 기준선 문서를 따른다.
  - 확인: `experiments/run-all.sh`가 0으로 끝나고, 문서의 표가 그 실행의 리포트 값과 같다.
- [ ] 5.2 Compose 테스트를 돌린다. 확인: `./gradlew :e2e:composeTest`가 통과한다(1.4에서 고친 시나리오 포함).
- [ ] 5.3 가상 스레드 비교를 한다(design 결정 11).
  - 같은 날 같은 상태에서 S-02를 기본 구성과 `FEED_SPRING_APPLICATION_JSON`(가상 스레드 + Semaphore)으로 한 번씩 돌린다.
  - `docs/experiments/call-isolation.md`에 비교 절을 더한다. 기본 구성을 바꿀지는 결론만 적고 바꾸지 않는다.
  - 확인: 문서에 두 결과 폴더 이름과 비교 항목이 있다.
- [ ] 5.4 PRD와 아키텍처를 마무리한다.
  - PRD F-001 수용 기준을 체크하고, F-004의 "벌크헤드 스레드 풀"과 "서킷·벌크헤드 이벤트" 부분의 진행을 적는다(fallback·재시도는 F-002, F-005에 남는다). 10장 "남은 것"에서 이 change가 정한 것(타임아웃, 문턱, 열림 유지, 벌크헤드 크기)을 옮긴다.
  - `docs/architecture.md` 맨 위 상태 줄과 13장을 고친다.
  - 확인: PRD와 아키텍처에 F-001의 남은 항목이 없다.

## 6. 검토와 change 완료 조건

이 묶음은 Ralph 루프가 하지 않는다([하네스 규칙](../../../conventions/harness.md) 3-3, 4장).

- [ ] 6.1 구현에 참여하지 않은 리뷰어가 하네스 규칙 3-3의 검토 항목으로 change 문서와 diff를 본다.
  - 3번은 Gateway의 조회와 토글이 모두 `IsolatedCalls`나 서킷을 거치는지, 4번(재시도)은 재시도가 없는지로 본다. 5번은 design 결정 3~7의 값마다 측정이나 계산이 있는지, 6번은 2.5의 테스트로 본다.
  - 사람이 Grafana에서 S-03 실행 하나의 측정 구간을 보고, 리포트의 서킷 상태·벌크헤드 최댓값과 그래프가 맞는지 확인한다.
  - 확인: 막는 지적이 없다.
- [ ] 6.2 하네스 규칙 4장의 change 완료 조건을 확인한다.
  - 장애 실험 세트 결과는 5.1이다. PR 설명에 판정 표와 기준선 비교 요약을 적는다.
  - 확인: 체크리스트가 모두 채워진다.

## Workflow follow-up

- `/opsx:archive`로 스펙을 `openspec/specs/`에 합친다.
- `develop`으로 PR을 열고 CI가 통과하면 merge한다.
- 고도화 3 릴리스: `develop` → `main` 릴리스 PR, merge 뒤 `v0.4.0` 태그.
