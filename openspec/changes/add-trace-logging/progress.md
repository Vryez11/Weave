# 진행 메모: add-trace-logging

## 알아 둘 패턴
- 로그 검사는 `com.weave.observability.testing.LogCapture`(libs/observability test fixtures)를 쓴다. `LogCapture().use { ... }`로 열고 닫는다. `lines()`는 원문+파싱 결과(`LogLine`), `jsonLines()`는 JSON 객체 줄만 돌려준다. 원래 표준 출력에도 그대로 쓴다.
- `test` 태스크의 태그 식은 `!Integration & !Compose`, `integrationTest`는 `Integration`. Compose 태그 테스트는 아직 돌리는 태스크가 없다(5.3에서 만든다).
- `libs/observability`의 웹 시나리오 테스트는 `com.weave.observability.testapp`(`LoggingTestApplication`, `/log`, `/log-exception`)을 쓴다. 테스트를 더할 때 엔드포인트를 이 컨트롤러에 더한다.
- Kotest `eventually`는 `io.kotest.assertions.nondeterministic.eventually`(kotest-assertions-core에 있다)다.
- 요청 하나의 로그 줄은 응답 `X-Trace-Id`와 같은 `traceId`로 찾는다. 앞 테스트의 줄이 비동기로 늦게 와도 섞이지 않는다. "남기지 않는다"는 뒤에 보낸 확인용 요청의 줄이 나올 때까지 기다린 뒤 판정한다(`PostRequestLogScenarioTest` 헬스 체크).
- ktlint import 순서 위반은 `./gradlew :<모듈>:ktlintFormat`으로 고친다.
- 이 환경의 셸은 `ls`가 다른 도구로 바뀌어 있고 `$변수`·`-exec`가 든 명령은 막힌다. 파일 목록은 `find`, 내용은 Read 도구로 본다.
- feed-service에서 응답 헤더나 `traceparent`가 필요하면 `FeedHttpClient`(헤더를 안 돌려준다) 대신 `TracedFeedHttp`(`feed-service/src/test/.../TraceHttp.kt`)를 쓴다. `HttpResponse.traceId()`, `LoggedRequest.traceparentTraceId()`도 거기 있다. 커밋된 `DownstreamWireMock.kt`는 Ralph 중에 고칠 수 없다(pre-file 훅).
- e2e 로그는 `com.weave.e2e.ServiceLogs`(`rawLines`, `jsonLines`, `requestLines(service, traceId)`)로 읽는다. 스펙 클래스마다 `ServiceProcesses`를 따로 띄운다(MySQL 포함, 한 번에 20초쯤).
- e2e 테스트는 커밋 scope를 비운다(`test: ...`). commit.md scope 표에 `e2e`가 없다.
- `progress.md`는 작업 커밋에 함께 넣는다. 따로 커밋해도 `commit-msg` 훅이 본문 `- ` 항목을 요구한다.
- Compose 테스트 주소는 시스템 속성 `weave.compose.feed-url`, `weave.compose.loki-url`, `weave.compose.tempo-url`이다(`TraceLookupComposeScenarioTest` KDoc). 5.3의 `composeTest`가 이 이름으로 넘긴다.
- `./gradlew build`는 `integrationTest`까지 돈다(e2e 포함). 아직 구현하지 않은 시나리오가 실패하는 동안에는 `--continue`로 돌려 실패한 태스크·테스트가 그 시나리오뿐인지 본다.
- 라이브러리 기본 속성은 `libs/observability/src/main/resources/observability-defaults.properties`에 한 줄 더하면 된다(2.6 `propagate-context`, 2.7 노출 엔드포인트). 우선순위 테스트는 `ObservabilityDefaultsTest`에 있다.
- `X-Trace-Id`·`http_request` 로그는 `TraceLoggingFilter`(order `HIGHEST_PRECEDENCE + 2`)가 맡는다. traceId는 micrometer `Tracer.currentSpan()`에서 읽고 요청 속성에 담아 오류 디스패치에서 다시 쓴다.
- 처리되지 않은 예외의 Tomcat 로그는 요청 완료 로그(필터 `finally`)보다 늦게 쓰인다. "남기지 않는다"를 판정할 때는 그 요청의 줄이 아니라 뒤에 보낸 확인용 요청의 줄을 기다린다.
- `@SpringBootTest`에서는 메트릭 내보내기가 꺼져 `/actuator/prometheus`가 404다. 테스트에 `@AutoConfigureMetrics`를 붙인다(`PostPrometheusEndpointTest`).
- 셸 훅은 `;`·`>`·`xargs`로 이은 명령이나 `$(...)`가 든 명령을 막는다. 빌드 결과는 `./gradlew ... 2>&1 | grep ...` 파이프 하나로 본다. `~/.gradle` 아래는 읽을 수 없다.
- 하위 서비스 실패 분류는 `com.weave.feed.gateway.DownstreamFailure.of(throwable)`(`cause`, `exception`)다. F-001의 새 `cause` 값도 여기에 더한다. 실패 로그는 `DownstreamFailure.log(dependency, error)`, 조회 future는 `Gateways.kt`의 `logFailure`.
- 하위 서비스 오류의 예외 메시지에는 본문이 없다(`HttpClientConfig.bodylessErrorMessages`, design 결정 6). 처리되지 않은 예외는 Tomcat이 메시지·스택 트레이스와 함께 ERROR로 남기므로, 로그에 남기면 안 되는 값은 예외 메시지에도 넣지 않는다.
- 테스트 결과는 `<모듈>/build/test-results/<태스크>/*.xml`의 `<testsuite ... timestamp=...>`로 실제로 다시 돌았는지 확인한다. Gradle 출력만으로는 몇 초 만에 끝나 헷갈린다.

---

## 2026-11-05 14:50 - 1.1 테스트를 쓰기 위한 준비
- `libs/observability`에 `java-test-fixtures`와 테스트 의존성(webmvc 스타터, webmvc-test 스타터, kotest-extensions-spring)을 더했다. test fixtures에는 BOM과 `jackson-databind`를 따로 넣었다(implementation의 BOM을 물려받지 않는다).
- `LogCapture`와 단위 테스트 `LogCaptureTest`(3개, F-004)를 만들었다.
- `weave.spring-service`가 서비스 테스트에 `testFixtures(project(":libs:observability"))`를 넣고, `test` 태그 식을 `!Integration & !Compose`로 바꿨다.
- 바꾼 파일: `buildSrc/.../weave.kotlin-conventions.gradle.kts`, `buildSrc/.../weave.spring-service.gradle.kts`, `libs/observability/build.gradle.kts`, `libs/observability/src/testFixtures/.../LogCapture.kt`, `libs/observability/src/test/.../LogCaptureTest.kt`, `libs/observability/CLAUDE.md`
- 확인: `./gradlew ktlintCheck build` 통과(e2e 포함).
- 다음 반복(1.2)은 `libs/observability` 테스트 소스에 작은 웹 애플리케이션을 두고 `@SpringBootTest(RANDOM_PORT)`로 부른다. 아직 logback 설정이 없어 로그는 Boot 기본 텍스트라 JSON 시나리오는 실패해야 한다.
---

## 2026-11-05 14:58 - 1.2 "JSON 로그 형식" 시나리오 테스트
- 테스트 소스에 `LoggingTestApplication`(`GET /log`는 INFO 로그, `GET /log-exception`은 `IllegalStateException`을 담은 ERROR 로그)을 두고, `JsonLogFormatScenarioTest`(`Integration`, F-004) 두 개를 RANDOM_PORT의 실제 Tomcat으로 부른다.
- 확인: `:libs:observability:integrationTest`에서 두 시나리오 모두 "JSON 줄이 없다"로 실패한다(예외 시나리오는 텍스트 로그 줄은 잡힌다. LogCapture가 logback 출력을 잡는 것도 함께 확인됨). `:libs:observability:test`, `ktlintCheck`, `compileKotlin compileTestKotlin`, `openspec validate` 통과.
- 바꾼 파일: `libs/observability/src/test/.../testapp/LoggingTestApplication.kt`, `.../testapp/JsonLogFormatScenarioTest.kt`, `libs/observability/CLAUDE.md`
- 다음 반복(1.3)은 post-service의 요청 완료 로그 시나리오다. 2.1에서 "예외가 있는 로그"는 통과하고 "요청 처리 중의 로그"는 traceId·spanId 단언에서만 실패해야 한다. 서비스 이름은 `spring.application.name=logging-test-app`으로 준다.
---

## 2026-11-05 14:57 - 1.3 post-service "요청 완료 로그" 시나리오 테스트
- `PostRequestLogScenarioTest`(`Integration`, F-004, RANDOM_PORT): "요청 하나에 한 줄"(`GET /posts?size=3`, traceId가 같은 `http_request` 줄이 정확히 하나, `method`·`path`·`status`·정수 `durationMs`), "헬스 체크"(`/actuator/health` 뒤 `/posts?size=1`의 줄이 나올 때까지 기다리고 `/actuator` 줄이 없다).
- 확인: `:post-service:integrationTest`에서 두 테스트 모두 "응답의 X-Trace-Id 헤더"로 실패한다. `ktlintCheck`, `compileKotlin compileTestKotlin`, `:post-service:test`, `openspec validate` 통과.
- 바꾼 파일: `post-service/src/test/kotlin/com/weave/post/PostRequestLogScenarioTest.kt`
- 다음 반복이 알아 둘 것: 이 테스트는 헤더와 traceId에 기대므로 2.3(필터)까지 와야 통과한다. 1.4도 같은 방식(응답 헤더 → 같은 traceId의 줄)으로 쓰면 된다.
---

## 2026-11-05 15:00 - 1.4 feed-service trace-logging 시나리오 테스트
- `FeedTraceLogScenarioTest`(`Integration`, F-004, RANDOM_PORT) 9개: 응답 헤더(정상, 오류 400·500), traceparent 받음·없음, 전파(다섯 호출, post 200ms 지연 뒤 user·like, 토글), 실패한 요청의 `http_request` status 500, 하위 서비스 오류 본문(recommendation 503 + 표식 본문)이 로그에 없음.
- 응답 헤더와 `traceparent`를 다루는 도우미 `TracedFeedHttp`를 새 파일 `TraceHttp.kt`에 두었다.
- 확인: `:feed-service:integrationTest`에서 새 9개가 모두 "응답의 X-Trace-Id 헤더"로 실패하고 기존 테스트는 통과. `:feed-service:test`, `ktlintCheck`, `compileTestKotlin`, `openspec validate` 통과.
- 바꾼 파일: `feed-service/src/test/kotlin/com/weave/feed/FeedTraceLogScenarioTest.kt`, `.../TraceHttp.kt`
- 다음 반복이 알아 둘 것:
  - "post가 끝난 뒤 시작한 호출"은 WireMock `loggedDate`로 user·like 요청이 post 요청보다 200ms 이상 뒤에 왔는지도 본다.
  - 오류 본문 시나리오는 3.2에서 통과해야 하는데, Gateway 로그만으로는 부족할 수 있다. `RestClientResponseException` 메시지에 본문이 들어가고(`503 Service Unavailable: "<본문>"`), 처리되지 않은 예외를 Tomcat/Spring이 스택 트레이스와 함께 ERROR로 남기면 `stack_trace`에 본문이 실린다. 3.2 때 실제로 그런지 보고, 그렇다면 응답 동작을 바꾸지 않고 막을 방법이 있는지 판단한다(없으면 BLOCKED).
---

## 2026-11-05 15:10 - 1.5 feed-aggregation "하위 서비스 호출 실패 로그" 시나리오 테스트
- `FeedCallFailureLogScenarioTest`(`Integration`, F-004) 3개: recommendation 503(응답 500, X-Trace-Id와 같은 traceId의 recommendation 줄이 정확히 하나, `cause` `http_503`), like 500(같은 traceId의 like `http_500` 줄), 토글 503(like `http_503` 줄).
- "연결할 수 없다"는 주소가 달라 컨텍스트를 따로 띄워야 해서 같은 파일의 `FeedCallFailureLogUnreachableScenarioTest`(recommendation 포트 1, `connect_failure` 줄)로 두었다.
- 확인: `:feed-service:integrationTest`에서 새 4개 실패(앞 둘은 X-Trace-Id 헤더, 뒤 둘은 실패 로그 줄 없음), 1.4의 9개 말고 기존 테스트는 통과. `:feed-service:test`, `ktlintCheck`, `compileTestKotlin`, `openspec validate` 통과.
- 바꾼 파일: `feed-service/src/test/kotlin/com/weave/feed/FeedCallFailureLogScenarioTest.kt`
- 다음 반복이 알아 둘 것: 실패 로그 줄은 파일 끝의 `LogCapture.failureLines(dependency, traceId?)`로 찾는다. 토글·연결 실패 시나리오는 스펙대로 traceId를 보지 않는다.
---

## 2026-11-05 15:25 - 1.6 e2e "서비스 6개의 로그를 traceId로 잇는다"·"요청의 값이 로그에 없다" 시나리오 테스트
- `TraceLogEndToEndScenarioTest`(`Integration`, F-004) 2개: `GET /feed`의 `X-Trace-Id`와 같은 `traceId`의 `http_request` 줄이 서비스 6개에 모두 있는지 `eventually(15초)`로 본다. 표식 시나리오는 `X-User-Id`(9자리 임의 숫자), `X-Marker`, 쿼리 `marker`에 테스트마다 새 표식을 넣고, 그 요청과 뒤에 보낸 확인용 요청의 줄이 6개 서비스에 모두 나온 뒤 원문 줄 전체에서 표식을 찾는다.
- 로그 파일 읽기 도우미 `ServiceLogs`를 새로 두었다(커밋된 `ServiceProcesses`는 고치지 않음).
- 확인: `:e2e:integrationTest`에서 새 2개가 "응답의 X-Trace-Id 헤더"로 실패하고 기존 F-006 2개는 통과. 9자리 `X-User-Id`로도 피드는 200이다. `ktlintCheck`, `compileKotlin compileTestKotlin`, `:e2e:test`, `openspec validate` 통과.
- 바꾼 파일: `e2e/src/test/kotlin/com/weave/e2e/ServiceLogs.kt`, `.../TraceLogEndToEndScenarioTest.kt`, `e2e/CLAUDE.md`
- 다음 반복이 알아 둘 것: 1.7은 같은 패키지에 `Compose` 태그 테스트를 두고, 주소를 시스템 속성으로 받는다. `ServiceLogs`는 파일 기반이라 Compose 테스트에서는 쓰지 않는다. 4.1에서 이 테스트가 통과해야 한다.
---

## 2026-11-05 15:40 - 1.7 Loki·Tempo 조회 시나리오를 `Compose` 태그 테스트로
- `TraceLookupComposeScenarioTest`(`Compose`, F-004) 2개. 둘 다 `GET /feed`의 `X-Trace-Id`로 `eventually(60초)` 조회한다.
  - Loki: `query_range`에 `{service=~".+"} | traceId="<id>"`, `direction=forward`, `limit=1000`. 스트림별 결과를 Loki 시각(ns) 순으로 합친 뒤, 줄 JSON의 `service`가 6개 모두 있는지와 `@timestamp`(`OffsetDateTime`)가 줄지 않는지 본다.
  - Tempo: `/api/traces/<id>`(Accept JSON). `batches`/`resourceSpans`, `scopeSpans`/`instrumentationLibrarySpans`를 모두 받아 스팬이 있는 리소스의 `service.name`이 6개 모두인지 본다.
- 서비스 이름은 Compose 서비스 이름이 아니라 `spring.application.name`(로그 `service`, 스팬 `service.name`)으로 본다. 5.1에서 Compose 서비스 이름을 자유롭게 정해도 된다. 대신 Alloy가 로그 원문(JSON 한 줄)을 그대로 Loki에 넣어야 한다.
- 확인: `:e2e:ktlintCheck`, `:e2e:compileTestKotlin`, `openspec validate` 통과. `./gradlew :e2e:test :e2e:integrationTest`에서 이 테스트는 실행되지 않았다. 다만 "리포트에 없다"를 글자 그대로 채우지는 못했다. `integrationTest` 리포트에 테스트 2개가 skipped(0ms)로 나온다. `test` 리포트에도 기존 `Integration` 스펙들과 똑같이 클래스 이름만 나온다. Kotest가 걸러 낸 스펙도 리포트에 남기는 기본 동작이라, 태그 식(buildSrc)을 고치지 않고 두었다. 같은 실행에서 1.6의 2개는 여전히 X-Trace-Id 헤더로 실패하고, F-006 2개는 통과했다.
- 바꾼 파일: `e2e/src/test/kotlin/com/weave/e2e/TraceLookupComposeScenarioTest.kt`, `e2e/CLAUDE.md`
- 다음 반복(2.1)은 `libs/observability`의 JSON 로그다.
---

## 2026-11-05 15:15 - 2.1 JSON 로그
- `libs/observability`에 `logstash-logback-encoder:9.0`을 더하고 `logback-spring.xml`을 만들었다. Boot `defaults.xml`을 include하고, `LogstashEncoder`(필드 이름 `logger`·`thread`, `@version`·`level_value` 뺌, `customFields`로 `service`, `includeContext` false, MDC는 `traceId`·`spanId`만, `ShortenedThrowableConverter` 깊이 30·원인 먼저·리플렉션/프록시/필터 체인 프레임 생략)를 `AsyncAppender`(8192, `discardingThreshold` 0, `neverBlock` false)로 감쌌다.
- `@timestamp`는 `timestampPattern` `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`로 밀리초까지 고정했다. 기본값은 나노초까지 나와 스펙(밀리초)과 1.2의 정규식에 맞지 않는다.
- 확인: `:libs:observability:integrationTest`에서 "예외가 있는 로그"는 통과, "요청 처리 중의 로그"는 "Map did not contain the keys traceId, spanId"로만 실패. `./gradlew ktlintCheck build --continue`에서 실패한 것은 아직 구현하지 않은 1.2~1.6 시나리오 테스트뿐이고(e2e F-006 2개 등 기존 테스트는 JSON 로그로도 통과), `openspec validate` 통과.
- 바꾼 파일: `libs/observability/build.gradle.kts`, `libs/observability/src/main/resources/logback-spring.xml`, `libs/observability/CLAUDE.md`
- 다음 반복이 알아 둘 것: Spring의 commons-logging 브리지를 거친 로그에는 `"tags":["COMMONS-LOGGING"]`가 붙는다(마커). 스펙 필드와 부딪히지 않아 두었다. 2.2에서 트레이싱을 켜면 MDC에 `traceId`·`spanId`가 들어와 1.2 첫 시나리오가 통과해야 한다.
---

## 2026-11-05 15:17 - 2.2 트레이싱과 라이브러리 기본 속성
- `libs/observability`에 design 결정 3의 모듈 네 개(`spring-boot-micrometer-tracing-opentelemetry`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `context-propagation`)를 더했다. Boot 4.1.2 BOM으로 각각 4.1.2, 1.7.2, 1.62.0, 1.2.1로 풀린다.
- `ObservabilityDefaultsPostProcessor`(Boot 4의 `org.springframework.boot.EnvironmentPostProcessor`, `META-INF/spring.factories`에 등록, 순서 `LOWEST_PRECEDENCE`)가 `observability-defaults.properties`(지금은 `management.tracing.sampling.probability=1.0` 하나)를 속성 소스 뒤에 붙인다. ConfigData보다 늦게 돌아야 서비스 `application.yml`이 이긴다.
- `ObservabilityDefaultsTest`(`Integration`, F-004) 4개: 기본값 1.0과 환경 변수·시스템 속성보다 뒤 순서, 프로필 파일(`application-sampling-override.yml`, 0.25)이 기본값을 이김, OTLP 주소가 없으면 OTLP `SpanExporter` Bean 없음, 주소를 주면 생김(대조군).
- 확인: `:libs:observability:integrationTest`에서 1.2 두 시나리오와 새 4개 통과. `./gradlew ktlintCheck build --continue`에서 실패한 것은 아직 구현하지 않은 1.3~1.6 시나리오(post 2, feed 13, e2e 2)뿐이고 기존 테스트는 트레이싱이 붙어도 통과했다. `openspec validate` 통과.
- 바꾼 파일: `libs/observability/build.gradle.kts`, `.../ObservabilityDefaultsPostProcessor.kt`, `src/main/resources/observability-defaults.properties`, `src/main/resources/META-INF/spring.factories`, `src/test/.../testapp/ObservabilityDefaultsTest.kt`, `src/test/resources/application-sampling-override.yml`, `libs/observability/CLAUDE.md`
- 다음 반복이 알아 둘 것: 속성 소스 맨 끝에는 Boot가 시작 뒤 `applicationInfo`를 붙인다. 2.3 필터는 `ServerHttpObservationFilter` 뒤에 두고, 필터용 `spring-webmvc`·`jakarta.servlet-api`를 `compileOnly`로 더해야 한다(design 결정 9).
---

## 2026-11-05 15:23 - 2.3 `X-Trace-Id` 헤더와 요청 완료 로그 필터
- `TraceLoggingFilter`(`OncePerRequestFilter` + `Ordered`, order `HIGHEST_PRECEDENCE + 2`)를 만들고 `ObservabilityAutoConfiguration.ServletConfiguration`(`@ConditionalOnWebApplication(SERVLET)`)이 Bean으로 등록한다.
  - 체인 전에 `Tracer.currentSpan()`의 traceId를 `X-Trace-Id`로 넣고 요청 속성에 담는다. 오류 디스패치(`DispatcherType.ERROR`, 중첩 오류 디스패치)에서는 헤더만 다시 넣는다.
  - 첫 디스패치의 `finally`에서 SLF4J key-value로 `event=http_request`, `method`, `path`(`requestURI`), `status`(예외가 올라오면 500), `durationMs`(Long)를 INFO로 남긴다. `/actuator`는 로그만 건너뛴다.
- 오류 디스패치에서 헤더가 사라지는 문제는 없었다(400·500 모두 헤더 있음). design Risks의 응답 본문 변경으로 갈 일 없음.
- 확인: `:post-service:integrationTest`의 1.3 두 개 통과. `:feed-service:integrationTest`에서 1.4의 정상·오류 응답 헤더, traceparent 받음·없음, 좋아요 토글 전파, 실패한 요청 통과. `./gradlew ktlintCheck build --continue`에서 실패는 아직 구현하지 않은 것뿐: feed 7개(1.4 전파 둘·오류 본문, 1.5 넷), e2e 1.6 둘(post-service 로그에 feed의 traceId 줄이 없음 = 풀 스레드 전파 없음). `openspec validate` 통과.
- 바꾼 파일: `libs/observability/build.gradle.kts`(`compileOnly` spring-webmvc, jakarta.servlet-api), `.../ObservabilityAutoConfiguration.kt`, `.../TraceLoggingFilter.kt`, `libs/observability/CLAUDE.md`
- 다음 반복(2.4)이 알아 둘 것: 지금 풀 스레드에서 나간 하위 서비스 호출은 새 trace를 시작한다(1.4 "다섯 호출"에서 post traceId가 X-Trace-Id와 다름). 2.4 재현 테스트는 이 상태를 `propagate-context=false`로 명시해 고정하면 된다. 토글은 요청 스레드라 이미 이어진다.
---

## 2026-11-05 15:26 - 2.4 공유 스레드 풀 끊김 재현 테스트
- `FeedTracePropagationOffTest`(`Integration`, F-004, RANDOM_PORT, `properties = ["spring.task.execution.propagate-context=false"]`) 1개: "F-004 전파를 끄면 끊긴다: …". `GET /feed` 200 뒤 하위 서비스 다섯 경로에 요청이 하나씩 왔고, 각 `traceparent` traceId가 있지만 `X-Trace-Id`와 다르다.
- 확인: `:feed-service:integrationTest`에서 새 테스트 통과. 실패는 아직 구현하지 않은 7개뿐: 1.4의 전파 둘("다섯 호출"은 post traceId 다름, "post 뒤 호출"은 user traceId 다름)과 오류 본문, 1.5 넷. `:feed-service:ktlintCheck`, `compileTestKotlin`, `:feed-service:test` 통과.
- tasks의 "1.4의 전파 시나리오 세 개는 여전히 실패한다"와 달리 토글 전파는 2.3부터 통과 중이다(요청 스레드에서 호출). 풀을 거치는 두 개만 실패하는 것이 맞는 상태다.
- 바꾼 파일: `feed-service/src/test/kotlin/com/weave/feed/FeedTracePropagationOffTest.kt`
- 다음 반복이 알아 둘 것:
  - 2.6 커밋 본문에 적을 재현 결과: `propagate-context=false`에서 풀 스레드의 RestClient가 새 trace를 시작해 post·recommendation·ad·user·like 다섯 요청의 traceId가 모두 `X-Trace-Id`와 달랐다. 토글(요청 스레드)은 이어졌다.
  - 3.2 주의(확인됨): 오류 본문 시나리오는 Tomcat `dispatcherServlet`의 ERROR 줄(`Servlet.service() ... threw exception`)의 `message`와 `stack_trace`에 `503 Service Unavailable: "<본문>"`이 실려 실패한다. Gateway 로그만으로는 통과하지 않는다.
---

## 2026-11-05 15:30 - 2.5 직접 만든 실행기의 끊김과 감싸기 단위 테스트
- `ExecutorTracePropagationTest`(태그 없음, F-004) 4개: 관찰 범위 안에서 `Executors.newFixedThreadPool(1)`·`CompletableFuture.supplyAsync`(commonPool)의 작업은 MDC `traceId`가 null, 같은 두 실행기를 `ContextExecutorService.wrap(..., ContextSnapshotFactory)`로 감싸면 요청 스레드와 같은 `traceId`.
- Spring 없이 Boot의 연결을 직접 짰다: `EventPublishingContextWrapper` + `Slf4JEventListener`(MDC), SDK `OtelTracer`, `DefaultTracingObservationHandler`, `ObservationThreadLocalAccessor`에 레지스트리 지정(스펙 끝나면 되돌림). 전파되는 것은 Observation이고, 작업 스레드에서 범위를 다시 열 때 MDC가 채워진다.
- 확인: `:libs:observability:test` 통과(새 4개 포함), `ktlintCheck`, `compileKotlin compileTestKotlin`(경고 없음), `openspec validate` 통과.
- 바꾼 파일: `libs/observability/src/test/kotlin/com/weave/observability/ExecutorTracePropagationTest.kt`, `libs/observability/CLAUDE.md`
- 다음 반복(2.6)이 알아 둘 것: 2.8의 아키텍처 7-3에 적을 테스트 이름은 이 파일의 네 테스트와 `FeedTracePropagationOffTest`다.
---

## 2026-11-05 15:32 - 2.6 라이브러리 기본값에 `propagate-context=true`
- `observability-defaults.properties`에 `spring.task.execution.propagate-context=true` 한 줄(주석 포함)만 더했다. 설정값 커밋이라 다른 변경과 섞지 않았다.
- 확인: `:feed-service:integrationTest`에서 1.4의 전파 시나리오(다섯 호출, post 뒤 호출, 토글)와 2.4 `FeedTracePropagationOffTest`가 모두 통과. `./gradlew ktlintCheck build --continue`에서 실패는 3.x 몫 5개뿐(1.5 넷, 1.4 오류 본문). `openspec validate` 통과.
- e2e `TraceLogEndToEndScenarioTest`(1.6) 두 개도 이 속성으로 통과했다(`:e2e:integrationTest` 4개 모두 통과).
- 바꾼 파일: `libs/observability/src/main/resources/observability-defaults.properties`
- 다음 반복이 알아 둘 것: 4.1은 고칠 것 없이 `./gradlew :e2e:integrationTest` 통과 확인만 하면 될 수 있다(3.2의 Gateway 로그가 붙은 뒤 다시 본다). 2.7은 같은 properties 파일에 노출 엔드포인트를 더한다.
---

## 2026-11-05 15:34 - 2.7 Prometheus 엔드포인트
- `weave.spring-service`에 `io.micrometer:micrometer-registry-prometheus`(Boot BOM으로 1.17.2)를 더하고, `observability-defaults.properties`에 `management.endpoints.web.exposure.include=health,info,prometheus`를 넣었다. 서비스 6개의 `application.yml`에서 `management` 블록(노출 설정뿐이었다)을 통째로 지웠다.
- `PostPrometheusEndpointTest`(`Integration`, F-004): `GET /actuator/prometheus`가 200이고 본문에 `# TYPE`이 있다. 구현 전 404로 실패를 확인했다. 구현 뒤에도 404였는데, `@SpringBootTest`가 메트릭 내보내기를 꺼 `PrometheusMeterRegistry`가 없어서였다. `@AutoConfigureMetrics`를 붙여 통과.
- 확인: `./gradlew ktlintCheck build --continue`에서 실패는 3.x 몫 5개뿐(feed 1.5 넷, 1.4 오류 본문). e2e·like(Testcontainers) 포함 나머지 통과. `openspec validate` 통과.
- 바꾼 파일: `buildSrc/.../weave.spring-service.gradle.kts`, `libs/observability/src/main/resources/observability-defaults.properties`, 서비스 6개 `application.yml`, `post-service/src/test/.../PostPrometheusEndpointTest.kt`, `libs/observability/CLAUDE.md`
- 다음 반복(2.8)이 알아 둘 것: 아키텍처 7-3에 적을 테스트 이름은 `ExecutorTracePropagationTest`의 네 테스트와 `FeedTracePropagationOffTest`다(2.5 기록). 5.2의 Prometheus 긁기 경로는 `/actuator/prometheus`.
---

## 2026-11-05 15:40 - 2.8 아키텍처 7-1, 7-3
- 7-1: JSON 로그 필드 표(`@timestamp`~`stack_trace`, 이벤트 필드는 key-value), 비동기 appender 값과 근거, 트레이싱(모듈 선택, OTLP 주소 없으면 내보내지 않음), `TraceLoggingFilter`(헤더, 오류 디스패치, `http_request` 줄, `/actuator` 제외), 기본 속성 방식과 세 속성 표.
- 7-3: 방법 표의 Spring `TaskExecutor`를 `spring.task.execution.propagate-context`로 확정(벌크헤드는 후보로 둠). 재현 결과 표에 무엇이 어디서 끊겼는지와 테스트 이름(`FeedTracePropagationOffTest`, `FeedTraceLogScenarioTest`의 전파 둘, `ExecutorTracePropagationTest` 넷)을 적었다. 테스트 이름은 grep으로 실제 이름과 맞췄다.
- 7-2(Loki 라벨, 구조화 메타데이터), 1장 이미지 버전, 12장은 5.5 몫이라 건드리지 않았다.
- 확인: `openspec validate --all --strict --no-interactive` 통과. 문서만 바꿔 빌드는 돌리지 않았다.
- 바꾼 파일: `docs/architecture.md`
- 다음 반복(3.1)이 알아 둘 것: `cause` 분류는 `com.weave.feed.gateway`의 함수 하나로 두고 단위 테스트(태그 없음) 이름에 `F-004`를 넣는다.
---

## 2026-11-05 15:38 - 3.1 `cause` 분류 함수
- `DownstreamFailure(cause, exception)`와 `DownstreamFailure.of(throwable)`를 만들었다. 겉의 `CompletionException`을 한 겹 벗긴 뒤 `RestClientResponseException` → `http_<상태>`, `ResourceAccessException` + 원인 사슬에 `ConnectException` → `connect_failure`, 그 밖의 `ResourceAccessException` → `io_failure`, 나머지 `other`. `exception`은 원인 사슬의 가장 안쪽 예외의 전체 클래스 이름(`javaClass.name`)이다. 원인이 순환해도 멈춘다.
- `DownstreamFailureTest`(태그 없음, F-004) 6개: HTTP 503·404, `CompletionException` 벗기기, 연결 실패(HttpClient 5의 `HttpHostConnectException`은 `ConnectException`의 하위 클래스라 그대로 잡힌다), 읽기 타임아웃 등 `io_failure`, `other`, 순환 원인. 구현 전 컴파일 실패를 확인했다.
- 확인: `./gradlew :feed-service:test -Pkotest.filter.tests='*F-004*'` 통과(6개), `:feed-service:ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과.
- 바꾼 파일: `feed-service/src/main/kotlin/com/weave/feed/gateway/DownstreamFailure.kt`, `feed-service/src/test/kotlin/com/weave/feed/gateway/DownstreamFailureTest.kt`
- 다음 반복(3.2)이 알아 둘 것:
  - 연결 타임아웃(`ConnectTimeoutException`)은 `ConnectException`이 아니라 `io_failure`로 나온다. design 표를 그대로 따랐다. 타임아웃은 F-001에서 따로 값을 둔다.
  - 3.2의 로그는 `DownstreamFailure.of(e)`의 두 값과 `event`, `dependency`만 key-value로 넣는다. 오류 본문 시나리오(1.4)는 Gateway 로그 말고도 Tomcat `dispatcherServlet`의 ERROR 줄에 본문이 실려 실패한다(2.4 기록). 응답 동작을 바꾸지 않고 막을 방법이 없으면 BLOCKED.
---

## 2026-11-05 15:42 - 3.2 Gateway 실패 로그
- `DownstreamFailure.log(dependency, error)`가 WARN 한 줄(`event` `downstream_call_failed`, `dependency`, `cause`, `exception`)을 SLF4J key-value로 남긴다. 예외 객체는 로거에 넘기지 않는다. 조회 Gateway 넷은 `supplyAsync(...).logFailure("<의존성>")`(`whenComplete`, 그 future를 돌려줌), 토글은 `try`/`catch`에서 남기고 다시 던진다.
- 오류 본문 시나리오(1.4): Gateway 로그만으로는 Tomcat ERROR 줄(`Servlet.service() ... threw exception`)의 `message`·`stack_trace`에 `503 Service Unavailable: "<본문>"`이 남는다. 응답을 바꾸지 않는 방법이 있어 멈추지 않았다. `HttpClientConfig`에 `RestClientHttpServiceGroupConfigurer`를 두어 그룹 클라이언트마다 `defaultStatusHandler(HttpStatusCode::isError)`로 같은 예외 종류(`HttpClientErrorException.create`/`HttpServerErrorException.create`/`UnknownHttpStatusCodeException`)·상태 코드·본문 바이트를 만들되 메시지는 `<상태 코드> <상태 문구>`만 넣는다. design 결정 6에 이 결정을 한 줄 더했다(검토 때 볼 것).
- 확인: `:feed-service:test :feed-service:integrationTest` 모두 통과(1.5 넷, 1.4 아홉 포함, F-006 기존 24개 포함). `./gradlew ktlintCheck build --continue` 통과(e2e 6개 서비스 테스트 포함, Compose 2개는 skipped). `openspec validate` 통과.
- 바꾼 파일: `feed-service/src/main/kotlin/com/weave/feed/gateway/DownstreamFailure.kt`, `.../gateway/Gateways.kt`, `.../client/HttpClientConfig.kt`, `feed-service/CLAUDE.md`(새로), `openspec/changes/add-trace-logging/design.md`
- 다음 반복(4.1)이 알아 둘 것: 이번 `build`에서 `:e2e:integrationTest`의 1.6 두 개가 이미 통과했다. 4.1은 고칠 것 없이 확인만 하면 된다.
---
