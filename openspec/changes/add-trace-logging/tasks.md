# Tasks

## 1. 준비와 시나리오 테스트

시나리오 테스트는 HTTP 수준의 블랙박스 테스트로 쓴다(design 결정 8). 구현이 없으니 `integrationTest`에서는 실패하고, `test`(Stop 훅)는 통과해야 한다. 테스트 이름에 `F-004`를 넣는다.

- [ ] 1.1 테스트를 쓰기 위한 준비를 한다.
  - `libs/observability`에 `java-test-fixtures` 플러그인과 테스트 의존성(design 결정 9)을 더한다.
  - test fixtures에 `LogCapture`를 만든다. `System.out`을 바꿔 끼워 출력을 모으고, 줄마다 JSON으로 파싱한 목록과 원문을 돌려준다. JSON이 아닌 줄은 원문으로만 남긴다.
  - `weave.spring-service`가 서비스 테스트에 `testFixtures(project(":libs:observability"))`를 넣게 한다.
  - `buildSrc`의 `test` 태스크 태그 식을 `!Integration & !Compose`로 바꾼다(design 결정 10).
  - 확인: `./gradlew ktlintCheck build`가 통과한다. `LogCapture`가 `println`으로 쓴 JSON 한 줄을 파싱하는 단위 테스트가 test fixtures 옆에 있고 통과한다.
- [ ] 1.2 `trace-logging`의 "JSON 로그 형식" 시나리오 두 개를 `libs/observability` 테스트로 옮긴다.
  - 테스트 소스에 요청을 받아 로그를 쓰는 엔드포인트와 예외를 담아 로그를 쓰는 엔드포인트가 있는 작은 웹 애플리케이션을 둔다.
  - 확인: `./gradlew :libs:observability:integrationTest`가 새 테스트에서 실패하고, `./gradlew :libs:observability:test`는 통과한다.
- [ ] 1.3 `trace-logging`의 "요청 완료 로그" 중 "요청 하나에 한 줄", "헬스 체크"를 post-service 테스트로 옮긴다. 확인: `./gradlew :post-service:integrationTest`가 새 테스트에서 실패하고, `./gradlew :post-service:test`는 통과한다.
- [ ] 1.4 `trace-logging` 시나리오 중 feed-service 경로를 쓰는 것을 feed-service 테스트로 옮긴다.
  - 응답 헤더(정상, 오류), 들어온 trace 잇기(둘), 하위 서비스 호출로 전파(셋), 요청 완료 로그의 "실패한 요청", 로그에 남기지 않는 것의 "하위 서비스 오류 본문"이다.
  - 기존 `DownstreamWireMock`을 쓰고, `traceparent`는 WireMock 요청 기록에서 읽는다. "post가 끝난 뒤 시작한 호출"은 post에 200ms 고정 지연을 준다.
  - 확인: `./gradlew :feed-service:integrationTest`가 새 테스트에서 실패하고, `./gradlew :feed-service:test`는 통과한다.
- [ ] 1.5 `feed-aggregation` 델타의 "하위 서비스 호출 실패 로그" 시나리오 네 개를 feed-service 테스트로 옮긴다. "연결할 수 없다"는 기존 `FeedUnreachableScenarioTest`처럼 recommendation 주소를 포트 1로 준다. 확인: `./gradlew :feed-service:integrationTest`가 새 테스트에서 실패하고, `./gradlew :feed-service:test`는 통과한다.
- [ ] 1.6 "서비스 6개의 로그를 traceId로 잇는다"와 "요청의 값이 로그에 없다"를 `e2e` 테스트로 옮긴다.
  - `e2e/build/e2e-logs/<서비스>.log`를 줄마다 JSON으로 파싱한다. 비동기 appender라 `eventually`로 기다린다.
  - 표식은 테스트마다 새로 만든다.
  - 확인: `./gradlew :e2e:integrationTest`가 새 테스트에서 실패하고, 기존 종단 간 테스트는 통과한다.
- [ ] 1.7 "Loki에서 traceId로 로그 조회"와 "Tempo에서 traceId로 트레이스 조회"를 `e2e`의 `Compose` 태그 테스트로 옮긴다.
  - feed-service 주소, Loki 주소, Tempo 주소는 시스템 속성으로 받는다(5.3에서 `composeTest`가 넘긴다).
  - Loki `query_range`와 Tempo `/api/traces/<id>`를 `eventually`(최대 60초)로 조회한다.
  - 확인: `./gradlew :e2e:compileTestKotlin`이 통과하고, `./gradlew :e2e:test :e2e:integrationTest`에서 이 테스트가 돌지 않는다(테스트 리포트에 없다).

## 2. libs/observability

- [ ] 2.1 JSON 로그를 만든다.
  - logstash-logback-encoder 의존성을 더한다.
  - `logback-spring.xml`에 design 결정 1의 인코더 설정(필드 이름, `service`, 스택 트레이스 줄이기)과 비동기 appender(대기열 8192, `discardingThreshold` 0, `neverBlock` false)를 둔다.
  - 확인: 1.2의 "예외가 있는 로그"가 통과한다. "요청 처리 중의 로그"는 `traceId`·`spanId`만 빠져 실패한다.
- [ ] 2.2 트레이싱을 켜고 라이브러리 기본 속성을 넣는다.
  - design 결정 3의 트레이싱 모듈 네 개를 더한다.
  - `EnvironmentPostProcessor`가 `observability-defaults.properties`를 속성 소스 맨 뒤에 붙이게 하고, `management.tracing.sampling.probability=1.0`만 넣는다. `propagate-context`는 아직 넣지 않는다.
  - OTLP 주소가 없으면 스팬을 내보내지 않는지 확인하는 테스트를 더한다(내보내기 Bean이 없거나, 요청 뒤 내보내기 오류 로그가 없다).
  - 서비스의 `application.yml`이 기본 속성을 이기는지 확인하는 테스트를 더한다.
  - 확인: 1.2의 두 시나리오와 위 두 테스트가 통과한다.
- [ ] 2.3 `X-Trace-Id` 헤더와 요청 완료 로그 필터를 만든다(design 결정 5).
  - `ServerHttpObservationFilter` 뒤에 두고, 오류 디스패치에서도 헤더를 다시 넣는다. 요청 완료 로그는 첫 디스패치에서만 남긴다. `/actuator`는 로그만 건너뛴다.
  - 오류 디스패치에서 헤더가 사라져 오류 응답 시나리오가 통과하지 않으면, 응답 본문을 바꾸는 방법(design Risks)으로 가지 말고 멈춘다.
  - 확인: 1.3의 두 시나리오, 1.4의 응답 헤더·들어온 trace 잇기·실패한 요청 시나리오가 통과한다.
- [ ] 2.4 공유 스레드 풀에서 traceId가 끊기는 것을 재현 테스트로 남긴다(design 결정 4의 2번).
  - feed-service 통합 테스트에 `spring.task.execution.propagate-context=false`를 명시하고, 하위 서비스 다섯 요청의 traceId가 응답의 `X-Trace-Id`와 다르다는 것을 확인한다.
  - 테스트 이름에 "전파를 끄면 끊긴다"는 뜻을 담는다.
  - 확인: 재현 테스트가 통과하고, 1.4의 전파 시나리오 세 개는 여전히 실패한다.
- [ ] 2.5 직접 만든 실행기의 끊김과 감싸기를 `libs/observability` 단위 테스트로 남긴다(design 결정 4의 4번).
  - `Executors.newFixedThreadPool`과 `ForkJoinPool.commonPool()`에서는 MDC의 `traceId`가 없다.
  - `ContextExecutorService.wrap`으로 감싸면 같은 `traceId`가 있다.
  - 확인: `./gradlew :libs:observability:test`가 통과한다.
- [ ] 2.6 라이브러리 기본값에 `spring.task.execution.propagate-context=true`를 넣는다.
  - 설정값 커밋이라 다른 변경과 섞지 않는다. 커밋 본문에 2.4의 재현 결과를 적는다.
  - 확인: 1.4의 전파 시나리오 세 개와 2.4의 재현 테스트가 모두 통과한다.
- [ ] 2.7 Prometheus 엔드포인트를 연다.
  - `weave.spring-service`에 `micrometer-registry-prometheus`를 더하고, 기본 속성에 `management.endpoints.web.exposure.include=health,info,prometheus`를 넣는다.
  - 서비스 6개의 `application.yml`에서 `management.endpoints.web.exposure.include`를 지운다(design 결정 2).
  - 확인: post-service 통합 테스트에서 `GET /actuator/prometheus`가 200이다. `./gradlew test integrationTest`가 1.5, 1.6의 아직 구현하지 않은 시나리오 말고는 모두 통과한다.
- [ ] 2.8 `docs/architecture.md` 7-1, 7-3을 정한 방식으로 고친다.
  - 7-1: 로그 필드, `X-Trace-Id`, 요청 완료 로그, 기본 속성을 넣는 방식
  - 7-3: Spring `TaskExecutor`는 `spring.task.execution.propagate-context`. 2.4, 2.5의 재현 결과(무엇이 어디서 끊겼는지)와 그 테스트 이름
  - 확인: `docs(architecture)` 커밋 하나로 남고, 적은 테스트 이름이 실제로 있다.

## 3. feed-service 호출 실패 로그

- [ ] 3.1 `cause` 분류 함수를 만든다(design 결정 6). `CompletionException`을 벗기고, 예외 종류별로 `http_<상태>`, `connect_failure`, `io_failure`, `other`와 가장 안쪽 예외 클래스 이름을 돌려준다. 확인: 예외 종류마다 값을 보는 단위 테스트가 `./gradlew :feed-service:test -Pkotest.filter.tests='*F-004*'`로 통과한다.
- [ ] 3.2 Gateway에 실패 로그를 붙인다.
  - 조회 메서드는 `whenComplete`, 토글은 `try`/`catch` 뒤 다시 던진다.
  - SLF4J key-value로 `event`, `dependency`, `cause`, `exception`을 넣고, 예외 객체는 로거에 넘기지 않는다.
  - 확인: 1.5의 네 시나리오와 1.4의 "하위 서비스 오류 본문" 시나리오가 통과하고, `./gradlew :feed-service:test :feed-service:integrationTest`가 모두 통과한다.

## 4. 종단 간 확인

- [ ] 4.1 1.6의 종단 간 시나리오를 통과시킨다. `ServiceProcesses`의 실행 인자나 로그 처리에서 고칠 곳이 나오면 여기서 고친다. 이미 커밋된 1.6의 테스트는 고치지 않는다. 확인: `./gradlew :e2e:integrationTest`가 모두 통과한다.

## 5. Docker Compose

- [ ] 5.1 서비스 6개와 MySQL을 Compose로 띄운다(design 결정 7).
  - `weave.spring-service`에서 `jar` 태스크를 끈다.
  - `docker/service.Dockerfile`과 루트 `compose.yaml`을 만들고, 서비스마다 헬스 체크와 로그 수집 라벨(`weave.logs=true`)을 단다.
  - `.env.example`에 MySQL 항목을 설명과 함께 더한다.
  - 확인: `./gradlew bootJar` 뒤 `docker compose --env-file <임의 값으로 만든 파일> up -d --build --wait`가 0으로 끝나고, `docker compose ps`에서 7개 컨테이너가 모두 healthy다. 끝나면 `docker compose down -v`로 내린다. `./gradlew :e2e:integrationTest`도 계속 통과한다.
- [ ] 5.2 관찰 인프라를 더한다.
  - Alloy(로그·OTLP), Loki, Tempo, Prometheus, Grafana를 `compose.yaml`에 더하고, 설정을 `docker/<이름>/`에 둔다.
  - 이미지 태그는 버전으로 고정한다.
  - 서비스에 `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`를 준다.
  - Grafana 데이터 소스에 Loki의 traceId → Tempo 링크와 Tempo의 trace-to-logs를 둔다.
  - 확인: 5.1과 같은 명령으로 모든 컨테이너가 healthy(헬스 체크가 없는 이미지는 running)다. Prometheus `/api/v1/targets`에서 서비스 6개가 `up`이다. 이 확인에 쓴 명령을 progress.md에 적는다.
- [ ] 5.3 `:e2e:composeTest` 태스크를 만든다(design 결정 10).
  - 서비스 6개의 `bootJar`에 기댄다.
  - 시작 전에 임의 계정으로 `e2e/build/compose-test.env`를 만들고, 프로젝트 이름 `weave-compose-test`로 `up -d --build --wait`를 돈다. 끝나면 성공·실패와 관계없이 `down -v`를 돈다.
  - `Compose` 태그 테스트만 돌리고, 주소를 시스템 속성으로 넘긴다.
  - 확인: `./gradlew :e2e:composeTest`가 1.7의 두 시나리오를 통과하고, 끝난 뒤 `docker compose -p weave-compose-test ps -a`가 비어 있다.
- [ ] 5.4 CI에 Compose 테스트를 더한다. `backend` 작업에 `./gradlew :e2e:composeTest` 단계를 두고, 실패하면 `docker compose -p weave-compose-test logs` 출력을 artifact로 올린다. 확인: 워크플로가 PR CI에서 통과하는 것은 PR 때 본다. 로컬에서는 워크플로에 적은 명령을 그대로 돌려 통과한다.
- [ ] 5.5 명령과 구성을 문서에 더한다.
  - `CLAUDE.md` 명령 표와 하네스 규칙 5장의 "로컬 인프라"를 `./gradlew bootJar` 뒤 `docker compose up -d --build --wait`로 바꾸고, `./gradlew :e2e:composeTest`를 더한다.
  - `docs/architecture.md`: 1장 표에 Compose 이미지 버전, 7-2에 Loki 라벨과 구조화 메타데이터, 12장 표에서 대시보드(7-4)를 `add-fault-experiments`로 옮긴다.
  - README의 실행 방법을 고친다.
  - 확인: 문서에 적은 명령을 그대로 돌려 5.1의 확인과 같은 결과가 나온다.
- [ ] 5.6 전체 검사를 돌린다. 확인: `./gradlew ktlintCheck build`, `./gradlew :e2e:composeTest`, `openspec validate --all --strict --no-interactive`가 통과한다.

## 6. 검토와 change 완료 조건

이 묶음은 Ralph 루프가 하지 않는다([하네스 규칙](../../../conventions/harness.md) 3-3, 4장).

- [ ] 6.1 구현에 참여하지 않은 리뷰어가 하네스 규칙 3-3의 검토 항목으로 change 문서와 diff를 본다.
  - 이 change에 해당하지 않는 항목(3번 격리, 4번 재시도)은 "해당 없음"으로 적는다. 6번 traceId는 이 change의 중심이니 공유 풀, `thenCompose` 뒤, 토글 경로를 모두 본다.
  - 사람이 `docker compose up` 환경의 Grafana에서 로그의 traceId 링크로 Tempo 트레이스를 열고, 트레이스에서 로그로 돌아오는 것을 한 번 확인한다.
  - 확인: 막는 지적이 없다.
- [ ] 6.2 하네스 규칙 4장의 change 완료 조건을 확인한다.
  - 장애 실험 세트는 아직 없으므로(고도화 2) 해당 없음으로 적는다.
  - PRD F-004 수용 기준을 체크한다. 벌크헤드 스레드 풀의 전파와 장애 대응 이벤트 로그는 F-001·F-002·F-005에서 채우므로, 이번에 한 범위(공유 풀, 호출 실패 로그)를 기준 옆에 적고 체크하지 않는다.
  - 확인: 체크리스트가 모두 채워진다.

## Workflow follow-up

- `/opsx:archive`로 스펙을 `openspec/specs/`에 합친다.
- `develop`으로 PR을 열고 CI가 통과하면 merge한다.
- 고도화 1 릴리스: `develop` → `main` 릴리스 PR, merge 뒤 `v0.2.0` 태그.
