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

## 지금 실패하는 것이 정상인 테스트 (구현 전)
- feed-service `integrationTest`: `FeedCallFailureLogScenarioTest`의 "F-001 호출 시간 상한을 넘긴다", `FeedExperimentMetricsScenarioTest`의 벌크헤드 지표 다섯, `IsolationSettingsTest`(Tomcat 단언 말고 모두).
- e2e `integrationTest`: `ExperimentCircuitStateScenarioTest` 셋(`lib/circuits.sh` 없음), `ExperimentVerdictScenarioTest`의 기준별 판정 넷(3.1·4.7).
- `FeedSlowDependencyScenarioTest`는 구현 전에도 통과한다. Boot 그룹 설정의 `read-timeout` 200ms만으로 HttpClient가 200ms에 끊어서다.

---

## 2026-11-06 - 1.1~1.4 (대화형, `/opsx:apply`)
- 1.1: 테스트 기본값 파일, `FeedAggregationScenarioTest`("서로 기다리지 않는다"를 상한 1초·예산 2초로, "느리다"를 `FeedSlowDependencyScenarioTest`로 옮겨 200ms 상한에서 400ms 안의 500으로), `FeedCallFailureLogScenarioTest`에 "호출 시간 상한을 넘긴다".
- 1.2: Resilience4j 의존성을 따로 커밋해 더했다(72e85fc). `IsolationSettingsTest`가 레지스트리 타입을 써서 2.1보다 먼저 필요했다. MVP 값 테스트 셋을 `IsolationSettingsTest`로 바꾸고, 지표 테스트의 공유 풀 단언을 벌크헤드로 바꿨다.
- 1.3: 판정 테스트에 기준별 기대 픽스처(`expectations-stage3.json`, `summary-S-02-availability-over.json`, `summary-S-02-both-over.json`)와 "고도화 3의 기대", `ExperimentCircuitStateScenarioTest`(픽스처 `query-range-circuit-state*.json`).
- 1.4: Compose 시나리오(프록시 지연, 짧게 돌린다, 리포트 항목, 시간 초과된 요청, Tomcat, 대시보드, 판정 표)와 `ComposeEnvironment.recreateFeedService`·`warmUpFeed`, `ServiceProcesses`의 넉넉한 격리 값. Compose 테스트는 5.2에서 돈다.
- 확인: `./gradlew ktlintCheck`, `:feed-service:test`, `:e2e:test` 통과. 통합 테스트는 위 "정상인 실패"만 실패한다.
- 다음(1.5): `FeedCallIsolationScenarioTest`. Ralph 루프로 넘긴다.
---
