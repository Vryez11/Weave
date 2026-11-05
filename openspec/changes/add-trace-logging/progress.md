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
- 처리되지 않은 예외의 Tomcat 로그는 요청 완료 로그(필터 `finally`)보다 늦게 쓰인다. "남기지 않는다"를 판정할 때는 그 요청의 줄이 아니라 뒤에 보낸 확인용 요청의 줄을 기다린다.

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
