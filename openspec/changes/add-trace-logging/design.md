# Design

## Context

- `libs/observability`는 빈 자동 설정 클래스 하나뿐이다. `weave.spring-service` 컨벤션 플러그인이 모든 서비스에 이 라이브러리를 `implementation`으로 넣는다.
- 서비스는 Spring Boot 기본 텍스트 로그를 쓰고, 트레이싱 의존성이 없다. 서비스마다 `application.yml`에 `management.endpoints.web.exposure.include: health, info`가 있다.
- feed-service의 Gateway 다섯 개는 `CompletableFuture.supplyAsync(호출, applicationTaskExecutor)`로 하위 서비스를 부른다. FeedAssembler는 post가 끝나면 `thenCompose`로 user·like를 시작한다. 토글은 요청 스레드에서 동기로 부른다. 하위 서비스가 실패하면 `join()`의 `CompletionException`이 그대로 올라가 500이 된다.
- Boot 4.1.2 확인 결과(자동 설정 jar의 메타데이터와 바이트코드):
  - `spring.task.execution.propagate-context`(기본 `false`)가 `true`이고 클래스패스에 `io.micrometer:context-propagation`이 있으면, Boot가 `applicationTaskExecutor`에 `ContextPropagatingTaskDecorator`를 붙인다.
  - 샘플링은 `management.tracing.sampling.probability`(기본 0.1), 전파 형식은 `management.tracing.propagation.produce`(기본 W3C), OTLP 스팬 내보내기 주소는 `management.opentelemetry.tracing.export.otlp.endpoint`다.
  - `spring-boot-starter-opentelemetry`는 트레이싱 외에 `micrometer-registry-otlp`(메트릭을 OTLP로 밀어 넣기)까지 가져온다.
- `e2e`는 서비스 6개를 `java -jar`로 띄우고 로그를 `e2e/build/e2e-logs/<서비스>.log`에 쓴다.
- 이 change는 [아키텍처](../../../docs/architecture.md) 12장 표대로 7장과 Compose의 관찰 인프라를 가져간다. 다르게 정한 곳은 각 결정에 이유와 함께 적는다.

## Goals / Non-Goals

**Goals:**

- 서비스 쪽 코드 변경 없이 의존성만으로 로그 형식, 트레이싱, 헤더, 요청 완료 로그가 붙는다. 서비스가 하는 일은 feed-service의 실패 로그 하나다.
- traceId가 끊기는 곳을 고치기 전에 테스트로 먼저 보여 주고, 고친 뒤에도 "전파 없는 실행기에서는 끊긴다"를 테스트로 남긴다. F-001에서 벌크헤드 스레드 풀을 같은 방식으로 다룬다.
- Compose 한 번으로 고도화 2의 실험 환경이 될 바탕(서비스 6개 + MySQL + 관찰 인프라)을 만든다.

**Non-Goals:**

- 로그 보존 기간, Loki 용량 설정. 로컬 실험용이라 기본값으로 둔다.
- 메트릭 이름 정하기, 대시보드. Prometheus가 긁어 가는 것까지만 한다(결정 7).
- 클라이언트가 보낸 `traceparent`를 믿을지 여부. 인증이 없는 프로젝트라(PRD 8장) 그대로 잇는다.

## Decisions

### 1. JSON 로그: logstash-logback-encoder + 비동기 appender

`libs/observability`의 `src/main/resources/logback-spring.xml` 하나로 모든 서비스의 로그를 정한다. 서비스는 이 파일을 두지 않는다.

- **인코더:** `LogstashEncoder`. 필드 이름은 스펙에 맞춰 바꾼다: `logger_name` → `logger`, `thread_name` → `thread`. `@version`, `level_value`는 뺀다. `service`는 `<springProperty source="spring.application.name">`으로 `customFields`에 넣는다.
- **traceId·spanId:** Micrometer Tracing이 스팬을 열 때 MDC에 `traceId`, `spanId`를 넣고, 인코더가 MDC를 그대로 필드로 내보낸다. MDC의 다른 키는 쓰지 않는다.
- **이벤트 필드:** SLF4J 2의 key-value(`log.atWarn().addKeyValue("dependency", ...)`)로 넣는다. 인코더가 key-value를 최상위 필드로 내보낸다. 서비스가 logstash 클래스(`StructuredArguments`)에 컴파일 의존하지 않아도 된다.
- **스택 트레이스:** `ShortenedThrowableConverter`로 예외당 깊이 30, 원인 먼저(`rootCauseFirst`), 프레임워크 프레임 일부 생략. 한 줄 JSON 안의 문자열이라 줄바꿈이 있어도 로그는 한 줄이다.
- **비동기 appender:** `AsyncAppender`(대기열 8192, `discardingThreshold` 0, `neverBlock` false)가 콘솔 appender를 감싼다.
  - 대기열 크기 계산: 실험 부하 200 RPS(아키텍처 9-1)에서 서비스 하나가 쓰는 로그는 요청당 1~2줄이라 초당 400줄 이하다. 8192는 출력이 멈춰도 약 20초를 버틴다.
  - 가득 차도 버리지 않는다(`discardingThreshold` 0). 기본값(80%가 차면 INFO 이하를 버림)대로면 장애 실험 중 정작 필요한 요청 로그가 조용히 빠진다. 대신 출력이 20초 넘게 막히면 요청 스레드가 기다린다. 표준 출력을 Docker가 읽어 가므로 그럴 일은 드물고, 생기면 실험 결과의 지연에 드러난다.
- **대안: Boot 내장 구조화 로그(`logging.structured.format.console: logstash`).** 의존성이 하나 줄고 필드 이름 바꾸기와 스택 트레이스 줄이기도 속성으로 된다. 하지만 비동기 appender를 쓰려면 결국 `logback-spring.xml`을 직접 둬야 하고, 그러면 내장 형식의 속성 설정과 xml이 나뉜다. 아키텍처 1장의 결정(encoder 9.0)을 그대로 따른다.

### 2. 라이브러리 기본 속성은 가장 낮은 우선순위로 넣는다

라이브러리가 `application.yml`을 가지면 서비스의 것과 부딪힌다. 그래서 `EnvironmentPostProcessor`(Boot 4: `org.springframework.boot.EnvironmentPostProcessor`)가 `observability-defaults.properties`를 속성 소스 맨 뒤에 붙인다. 서비스의 `application.yml`과 환경 변수가 언제나 이긴다.

| 속성 | 값 | 이유 |
|---|---|---|
| `management.tracing.sampling.probability` | `1.0` | 아키텍처 7-1. 로컬 실험에서 모든 요청을 본다 |
| `spring.task.execution.propagate-context` | `true` | 결정 4. 끊김 재현 테스트를 남긴 뒤에 넣는다 |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | 결정 7 |

- OTLP 내보내기 주소는 기본값에 두지 않는다. 주소가 없으면 스팬은 만들되 내보내지 않아, 테스트와 `bootRun`에서 없는 Alloy로 보내려다 오류 로그를 쏟지 않는다. Compose만 환경 변수 `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`로 준다. 주소가 없을 때 정말 내보내기가 꺼지는지는 작업 2.2에서 확인한다.
- 서비스 6개의 `application.yml`에서 `management.endpoints.web.exposure.include`를 지운다. 남겨 두면 서비스 쪽 값이 이겨 `prometheus`가 열리지 않고, "서비스는 설정을 따로 두지 않는다"(PRD F-004)와도 어긋난다.

### 3. 트레이싱 의존성: 모듈을 골라 넣는다

`libs/observability`에 아래를 `implementation`으로 넣는다(버전은 Boot BOM).

- `org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry`(자동 설정)
- `io.micrometer:micrometer-tracing-bridge-otel`
- `io.opentelemetry:opentelemetry-exporter-otlp`
- `io.micrometer:context-propagation`(이미 다른 의존성으로 들어오면 명시만 한다)

`spring-boot-starter-opentelemetry`를 쓰지 않는 이유: 메트릭을 OTLP로 밀어 넣는 레지스트리까지 들어온다. 메트릭은 Prometheus가 긁어 가기로 했고(아키텍처 7-2), 둘 다 켜면 메트릭이 두 길로 나가거나 없는 수신처로 보내려다 오류를 낸다. 끄는 속성을 더하느니 처음부터 넣지 않는다.

HTTP 클라이언트 쪽은 따로 할 일이 없다. Boot가 `RestClient.Builder`에 관찰(`ObservationRegistry`)을 붙이고, HTTP 서비스 그룹의 `RestClient`가 이 빌더에서 나온다. 그러면 요청마다 클라이언트 스팬이 생기고 `traceparent`가 붙는다. 그룹 클라이언트에도 정말 붙는지는 작업 1.4의 시나리오 테스트가 확인한다.

ad-service는 외부 API 역할이지만 `traceparent`를 똑같이 보낸다. 실제 외부 API라면 내부 trace 정보를 밖으로 보내지 않는 것이 보통이지만, PRD F-004가 6개 서비스의 로그를 traceId로 잇기를 요구하고 ad-service도 우리가 띄우는 서비스다. ad-service만 빼는 정책은 F-005에서 외부 API 정책을 정할 때 다시 본다.

### 4. 끊김을 먼저 재현하고 속성 하나로 고친다

PRD F-004의 순서대로 간다.

1. 트레이싱만 켠다(결정 3). `propagate-context`는 기본값(`false`)이다.
2. **재현 테스트**를 남긴다. feed-service 통합 테스트에서 `spring.task.execution.propagate-context=false`를 **테스트에 명시**하고 `GET /feed`를 부른다.
   - 기대(=끊김): 하위 서비스 다섯 요청의 `traceparent` traceId가 응답의 `X-Trace-Id`와 다르다. 풀 스레드에는 현재 관찰이 없어 RestClient가 새 trace를 시작하기 때문이다. 풀 스레드에서 쓴 `downstream_call_failed` 로그에도 `traceId`가 없거나 다르다.
   - 속성을 테스트에 명시하므로, 기본값을 바꾼 뒤에도 이 테스트는 그대로 통과하며 "전파를 끄면 끊긴다"는 기록으로 남는다.
3. 라이브러리 기본값에 `spring.task.execution.propagate-context=true`를 넣는다. 전파 시나리오 테스트(작업 1.4)가 통과한다.
4. `libs/observability` 단위 테스트로 **직접 만든 실행기**도 기록한다. `Executors.newFixedThreadPool`과 `CompletableFuture`의 기본 풀(`ForkJoinPool.commonPool()`)에서는 MDC의 `traceId`가 없고, Micrometer Context Propagation으로 감싸면(`ContextExecutorService.wrap`) 이어진다. F-001의 ThreadPool 벌크헤드가 이 경우다.

- **대안: `TaskDecorator` Bean을 직접 등록.** Boot 4.1이 같은 일을 속성으로 해 주므로 코드를 두지 않는다. 아키텍처 7-3의 표에 "Spring `TaskExecutor`는 `spring.task.execution.propagate-context`"로 고쳐 적는다.
- `thenCompose`·`thenCombine` 콜백은 앞 단계를 끝낸 스레드(풀 스레드)나 요청 스레드에서 돈다. 풀 스레드는 3번으로 컨텍스트를 받았으므로, 그 안에서 시작한 user·like 호출도 같은 trace를 잇는다. 스펙의 "post가 끝난 뒤 시작한 호출" 시나리오가 이것을 확인한다.

### 5. 응답 헤더와 요청 완료 로그: 필터 하나

`libs/observability`의 자동 설정이 서블릿 필터 하나(`TraceLoggingFilter`)를 등록한다. 웹 애플리케이션일 때만 켠다(`@ConditionalOnWebApplication(type = SERVLET)`).

- **순서:** Boot의 `ServerHttpObservationFilter`(서버 스팬을 연다) 바로 뒤. 그래야 필터 안에서 현재 스팬의 traceId를 읽을 수 있다.
- **헤더:** 체인을 부르기 전에 `X-Trace-Id`를 응답에 넣는다. 응답이 커밋된 뒤에는 헤더를 넣을 수 없기 때문이다.
- **요청 완료 로그:** `finally`에서 `event=http_request`, `method`, `path`(`requestURI`, 쿼리 제외), `status`, `durationMs`를 INFO로 한 줄 남긴다. 체인에서 예외가 올라왔으면 `status`는 500으로 적는다. 그 시점의 응답 상태는 아직 오류 처리 전이라 200이다.
- `/actuator`로 시작하는 경로는 로그만 건너뛴다(헤더는 넣는다). Compose 헬스 체크와 Prometheus 수집이 몇 초마다 와서 로그를 덮는다.
- 헤더 이름은 `X-Trace-Id`. W3C의 `traceresponse` 헤더는 아직 초안이고, 클라이언트가 값을 그대로 오류 리포트에 붙이기에는 traceId만 담은 헤더가 쉽다.
- **오류 디스패치:** 처리되지 않은 예외는 Tomcat이 `/error`로 다시 디스패치한다. 이때 응답이 초기화되며 헤더가 사라질 수 있다. 오류 디스패치에서도 필터를 돌려(`shouldNotFilterErrorDispatch` false) 헤더를 다시 넣되, 요청 완료 로그는 첫 디스패치에서만 남긴다. 실제로 헤더가 사라지는지는 오류 응답 시나리오 테스트가 판정한다.

### 6. feed-service의 하위 서비스 호출 실패 로그

- Gateway의 조회 메서드는 만든 `CompletableFuture`에 `whenComplete`를 달아 실패를 로그로 남긴다. 콜백은 호출을 돌린 풀 스레드에서 돌고, 결정 4로 컨텍스트가 넘어와 있어 `traceId`가 같다. 토글은 동기 호출이라 `try`/`catch`에서 남기고 다시 던진다. 응답 동작(500)은 바뀌지 않는다.
- 레벨은 WARN. 피드 요청의 실패 자체는 요청 완료 로그의 `status` 500이 보여 준다.
- `cause` 분류(`CompletionException`은 벗겨서 본다):

  | 예외 | `cause` |
  |---|---|
  | `RestClientResponseException` | `http_<상태 코드>` |
  | `ResourceAccessException`, 원인에 `ConnectException` | `connect_failure` |
  | 그 밖의 `ResourceAccessException` | `io_failure` |
  | 그 밖 | `other` |

  `exception` 필드에 가장 안쪽 예외의 클래스 이름을 함께 남긴다. `other`일 때 원인을 찾을 단서다.
- **예외 객체와 메시지를 로그에 넘기지 않는다.** `RestClientResponseException`의 메시지에는 하위 서비스의 응답 본문이 들어가, 스택 트레이스로 남기면 본문이 로그에 실린다(스펙 "로그에 남기지 않는 것"). 상태 코드와 클래스 이름이면 원인을 가리기에 충분하다.
- **예외 메시지에도 본문을 넣지 않는다(작업 3.2에서 정함).** 실패 로그만 고쳐서는 부족했다. 처리되지 않은 예외를 Tomcat이 `Servlet.service() ... threw exception` ERROR 줄로 남기며 `message`와 `stack_trace`에 예외 메시지(`503 Service Unavailable: "<본문>"`)를 싣는다. 그래서 HTTP 서비스 그룹 클라이언트에 오류 상태 처리기(`defaultStatusHandler`)를 두어, 기본 처리기와 같은 예외 종류·상태 코드·본문 바이트로 만들되 메시지는 `<상태 코드> <상태 문구>`로만 둔다. 응답(500)과 `cause` 분류는 바뀌지 않는다.
- 분류와 로그는 `com.weave.feed.gateway`의 작은 함수 하나로 모아 Gateway 다섯 개가 함께 쓴다. F-001에서 타임아웃, 서킷 열림, 벌크헤드 거절이 `cause`의 새 값으로 더해진다.

### 7. Docker Compose

루트에 `compose.yaml`을 두고, 설정 파일은 `docker/` 아래에 둔다.

| 서비스 | 이미지 | 메모 |
|---|---|---|
| feed, post, user, like, recommendation, ad | `docker/service.Dockerfile`로 빌드 (`eclipse-temurin:25-jre`) | 빌드 컨텍스트는 각 모듈의 `build/libs`. 그 안의 bootJar 하나를 복사한다. 호스트에는 feed-service 8080만 연다 |
| MySQL | `mysql:9.7` | like-service의 테스트와 같은 버전. 계정은 `.env`에서 |
| Alloy | `grafana/alloy` | 로그: Docker 소켓으로 `weave.logs=true` 라벨이 붙은 컨테이너의 stdout을 읽는다. 스팬: OTLP(4318)로 받아 Tempo로 |
| Loki | `grafana/loki` | 단일 프로세스, 파일 저장소 |
| Tempo | `grafana/tempo` | 단일 프로세스, 파일 저장소, OTLP 수신 |
| Prometheus | `prom/prometheus` | 서비스 6개의 `/actuator/prometheus`를 15초마다 긁는다 |
| Grafana | `grafana/grafana` | 데이터 소스를 `docker/grafana/provisioning/`에서 불러온다. 로컬이라 익명 접근 |

- 이미지 태그는 구현할 때의 안정 버전으로 고정하고(`latest` 금지), 아키텍처 1장 표에 적는다.
- **plain jar를 끈다.** `weave.spring-service`에서 `jar` 태스크를 끄면 `build/libs`에 bootJar만 남아 Dockerfile이 jar 이름을 몰라도 된다. e2e는 `bootJarElements` 구성으로 jar를 받으므로 영향이 없다.
- **Loki 라벨:** `service`(Compose 서비스 이름)와 `level`만 라벨로 둔다. `traceId`는 요청마다 달라 라벨로 두면 스트림이 폭증하므로, Alloy의 `loki.process`에서 JSON을 풀어 **구조화 메타데이터**로 붙인다. 조회는 `{service=~".+"} | traceId="<id>"`다. 시각은 컨테이너 출력 시각이 아니라 로그의 `@timestamp`를 쓴다(`stage.timestamp`). 비동기 appender가 출력을 조금 미뤄도 순서가 앱 시각대로 남는다.
- **Grafana 연결:** Loki 데이터 소스의 derived field가 `traceId`를 Tempo 링크로, Tempo 데이터 소스의 trace-to-logs가 traceId로 Loki를 조회한다(아키텍처 7-2).
- **메트릭:** 서비스 공통 의존성에 `micrometer-registry-prometheus`를 더하고 `prometheus` 엔드포인트를 연다(결정 2). 대시보드는 만들지 않는다. 아키텍처 7-4의 패널(degrade 비율, 서킷 상태, 벌크헤드)은 대부분 고도화 3·4에서 생기는 지표라, 실험 리포트와 함께 F-007(`add-fault-experiments`)에서 만든다. 12장 표의 "7장 전체"와 다른 점이라 아키텍처 12장도 고친다.
- **자원 상한(아키텍처 9-1)은 걸지 않는다.** 실험 조건이라 F-007에서 실험과 함께 정한다.
- **Toxiproxy, Redis는 넣지 않는다.** 각각 F-007, F-002에서 넣는다. 그때 feed-service의 하위 서비스 주소만 바꾸면 된다.
- **비밀 값:** MySQL 계정은 Compose가 루트 `.env`에서 읽는다. `.env.example`에 `MYSQL_ROOT_PASSWORD`와 like-service가 쓸 사용자 이름·비밀번호를 설명과 함께 적는다. like-service의 `LIKE_DB_URL`은 Compose 파일에 `jdbc:mysql://mysql:3306/weave_like`로 적는다(비밀 값이 아니다).
- **띄우는 법:** `./gradlew bootJar` 뒤 `docker compose up -d --build --wait`. 하네스 규칙 5장의 "로컬 인프라" 칸을 이것으로 바꾼다.

### 8. 테스트 배치

시나리오 테스트는 HTTP 수준의 블랙박스 테스트로 쓰고, 테스트 이름에 `F-004`를 넣는다. 로그는 표준 출력을 가로채 줄마다 JSON으로 파싱해 본다. logback 콘솔 appender는 쓸 때마다 `System.out`을 다시 찾으므로, 테스트가 `System.out`을 바꿔 끼우면 로그가 잡힌다. 비동기 appender라 로그 검사는 `eventually`로 기다린다. 표준 출력을 가로채 JSON 줄로 돌려주는 도우미(`LogCapture`)는 `libs/observability`의 test fixtures(`java-test-fixtures`)로 두고, `weave.spring-service`가 서비스 테스트에 넣는다. 서비스끼리 코드를 나누는 것이 아니라 모든 서비스가 이미 쓰는 공통 라이브러리의 테스트 도구라, "서비스끼리 코드를 공유하지 않는다"와 부딪히지 않는다. 같은 도우미를 서비스마다 복사하면 로그 형식이 바뀔 때 여러 곳을 고쳐야 한다.

스펙 시나리오가 특정 서비스의 경로(`GET /feed`, `GET /posts`)를 쓰면 그 서비스의 테스트에 둔다. 모든 HTTP 테스트는 MockMvc가 아니라 `@SpringBootTest(RANDOM_PORT)`의 실제 Tomcat으로 부른다. 오류 디스패치에서 헤더가 남는지는 실제 Tomcat에서만 볼 수 있다.

| 어디 | 시나리오 | 방식 |
|---|---|---|
| `libs/observability` | JSON 로그 형식(요청 처리 중, 예외) | 테스트 소스의 작은 웹 애플리케이션. `Integration` 태그 |
| post-service | 요청 완료 로그(요청 하나에 한 줄, 헬스 체크) | `Integration` 태그 |
| feed-service | 응답 헤더(정상, 오류), 들어온 trace 잇기, 하위 서비스 호출로 전파(다섯 호출, post 뒤 호출, 토글), 요청 완료 로그(실패한 요청), 하위 서비스 오류 본문이 로그에 없다, feed-aggregation의 실패 로그 4개 | 기존 WireMock 구성. `traceparent`는 WireMock 요청 기록에서 읽는다. `Integration` 태그 |
| feed-service | 끊김 재현(결정 4의 2번) | 위와 같고 `propagate-context=false`를 명시. `Integration` 태그 |
| `libs/observability` | 직접 만든 실행기의 끊김과 감싸기(결정 4의 4번) | Spring 없이 `ObservationRegistry`와 OTel 트레이서를 직접 만든다. 태그 없음(Stop 훅이 돌린다) |
| `e2e` | 서비스 6개의 로그를 traceId로 잇는다, 요청의 값이 로그에 없다 | 기존 프로세스 테스트. `e2e/build/e2e-logs/*.log`를 줄마다 JSON으로 파싱한다. `Integration` 태그 |
| `e2e` | Loki 조회, Tempo 조회 | 결정 10. `Compose` 태그 |

단위 테스트(태그 없음): `cause` 분류 함수(예외 종류별 값, `CompletionException` 벗기기).

### 9. 의존성

| 모듈 | 추가 |
|---|---|
| `libs/observability` | `net.logstash.logback:logstash-logback-encoder:9.0`, 결정 3의 트레이싱 모듈 네 개, 필터용 `spring-webmvc`·`jakarta.servlet-api`(`compileOnly`, 서비스가 이미 가진다), 테스트용 webmvc 스타터·kotest-extensions-spring, `java-test-fixtures` 플러그인(결정 8의 `LogCapture`) |
| `weave.spring-service`(서비스 공통) | `io.micrometer:micrometer-registry-prometheus`, 테스트에 `testFixtures(project(":libs:observability"))` |
| `e2e` | 없음. Loki·Tempo HTTP API는 JDK `HttpClient`와 이미 있는 Jackson으로 부른다 |

### 10. Compose 테스트 (`:e2e:composeTest`)

- Kotest 태그 `Compose`를 새로 둔다. `test`는 `!Integration & !Compose`, `integrationTest`는 `Integration`만 돈다. `Compose` 테스트는 `e2e`의 `composeTest` 태스크에서만 돈다.
- `composeTest`는 서비스 6개의 `bootJar`에 기대고, 시작 전에 `docker compose up -d --build --wait`, 끝나면 성공·실패와 관계없이 `docker compose down -v`를 돈다. Compose 프로젝트 이름을 `weave-compose-test`로 따로 주어, 손으로 띄운 로컬 환경과 부딪히지 않게 한다.
- MySQL 계정은 태스크가 실행할 때마다 임의 값으로 `e2e/build/compose-test.env`를 만들어 `--env-file`로 넘긴다. CI에는 `.env`가 없고, 비밀 값을 커밋하지 않는다.
- 테스트는 `GET /feed`를 부르고, 응답의 `X-Trace-Id`로 Loki의 `query_range`와 Tempo의 `/api/traces/<id>`를 `eventually`(최대 60초)로 조회한다. Alloy가 로그를 모으고 Tempo가 스팬을 받는 데 시간이 걸리기 때문이다.
- CI의 `backend` 작업에 `./gradlew :e2e:composeTest` 단계를 더하고, 실패하면 `docker compose logs`를 artifact로 올린다. Stop 훅은 돌리지 않는다.
- 하네스 규칙 5장과 `CLAUDE.md` 명령 표에 `./gradlew :e2e:composeTest`를 더한다.
- Grafana에서 로그 → 트레이스 링크를 누르는 것은 자동으로 보지 않는다. 검토(7.1) 때 사람이 한 번 확인한다.

## Risks / Trade-offs

- [오류 디스패치에서 `X-Trace-Id`가 사라질 수 있다] → 결정 5대로 오류 디스패치에서도 헤더를 다시 넣는다. 그래도 없으면 오류 응답을 Spring MVC 안(`@RestControllerAdvice`)에서 만들어 Tomcat 오류 디스패치를 타지 않게 하는 방법이 있다. 응답 본문이 바뀌므로 그때는 멈추고 사람에게 묻는다.
- [HTTP 서비스 그룹의 `RestClient`에 관찰이 붙지 않을 수 있다] → 작업 1.4의 시나리오 테스트가 판정한다. 안 붙으면 그룹 설정자(`RestClientHttpServiceGroupConfigurer`)로 `ObservationRegistry`를 넣는다. 스펙은 그대로다.
- [비동기 appender가 막히면 요청이 기다린다] → 결정 1의 계산으로 20초 여유가 있다. 실험에서 로그 출력이 지연의 원인으로 보이면 F-007에서 따로 잰다.
- [로그가 늘어 실험 결과에 영향을 준다] → 요청 완료 로그는 요청당 한 줄이고, 고도화 2의 기준선을 이 상태에서 잰다. 이후 단계도 같은 로그를 쓰므로 비교는 공정하다.
- [Compose 테스트가 느리고 CI 시간을 늘린다] → 이미지 6개 빌드와 인프라 기동에 몇 분이 걸린다. Stop 훅에서는 돌지 않고 CI에서만 돈다. 너무 느리면 CI에서 `develop`·`main` push에만 돌리는 것을 검토 때 정한다.
- [Docker 소켓을 Alloy에 연다] → 로컬 실험 환경에서만 쓰고 읽기 전용으로 마운트한다.
- [traceId 끊김 재현 테스트가 Boot의 동작에 기댄다] → 재현 테스트가 기대하는 것은 "전파를 끄면 다른 traceId"뿐이라, Boot가 기본값을 바꿔도 속성을 명시한 테스트는 흔들리지 않는다.

## 장애 시나리오와 실험 세트

이 change는 장애 대응 동작과 설정값을 바꾸지 않는다. 고도화 2의 기준선(S-01~S-10)을 이 로그·트레이싱이 붙은 상태에서 재므로, 비동기 appender와 샘플링 100%의 비용은 기준선에 함께 들어간다. 실험 세트는 고도화 2에서 만들므로 이 change에서는 돌리지 않는다.

## Open Questions

- Compose 이미지 빌드 시간이 CI에서 너무 길면 `composeTest`를 어느 이벤트에서 돌릴지(결정 10). 스펙과 작업 나누기는 바뀌지 않는다.
