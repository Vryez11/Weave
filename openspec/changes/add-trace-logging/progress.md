# 진행 메모: add-trace-logging

## 알아 둘 패턴
- 로그 검사는 `com.weave.observability.testing.LogCapture`(libs/observability test fixtures)를 쓴다. `LogCapture().use { ... }`로 열고 닫는다. `lines()`는 원문+파싱 결과(`LogLine`), `jsonLines()`는 JSON 객체 줄만 돌려준다. 원래 표준 출력에도 그대로 쓴다.
- `test` 태스크의 태그 식은 `!Integration & !Compose`, `integrationTest`는 `Integration`. Compose 태그 테스트는 아직 돌리는 태스크가 없다(5.3에서 만든다).
- 이 환경의 셸은 `ls`가 다른 도구로 바뀌어 있고 `$변수`·`-exec`가 든 명령은 막힌다. 파일 목록은 `find`, 내용은 Read 도구로 본다.

---

## 2026-11-05 14:50 - 1.1 테스트를 쓰기 위한 준비
- `libs/observability`에 `java-test-fixtures`와 테스트 의존성(webmvc 스타터, webmvc-test 스타터, kotest-extensions-spring)을 더했다. test fixtures에는 BOM과 `jackson-databind`를 따로 넣었다(implementation의 BOM을 물려받지 않는다).
- `LogCapture`와 단위 테스트 `LogCaptureTest`(3개, F-004)를 만들었다.
- `weave.spring-service`가 서비스 테스트에 `testFixtures(project(":libs:observability"))`를 넣고, `test` 태그 식을 `!Integration & !Compose`로 바꿨다.
- 바꾼 파일: `buildSrc/.../weave.kotlin-conventions.gradle.kts`, `buildSrc/.../weave.spring-service.gradle.kts`, `libs/observability/build.gradle.kts`, `libs/observability/src/testFixtures/.../LogCapture.kt`, `libs/observability/src/test/.../LogCaptureTest.kt`, `libs/observability/CLAUDE.md`
- 확인: `./gradlew ktlintCheck build` 통과(e2e 포함).
- 다음 반복(1.2)은 `libs/observability` 테스트 소스에 작은 웹 애플리케이션을 두고 `@SpringBootTest(RANDOM_PORT)`로 부른다. 아직 logback 설정이 없어 로그는 Boot 기본 텍스트라 JSON 시나리오는 실패해야 한다.
---
