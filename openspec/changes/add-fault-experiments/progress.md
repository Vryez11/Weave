# 진행 메모: add-fault-experiments

## 알아 둘 패턴
- 셸의 `ls`는 eza 별칭이라 경로 인자에서 깨진다. `command ls`를 쓴다.
- Gradle 9는 테스트 소스가 있는데 발견된 테스트가 없으면 `test` 태스크를 실패시킨다. 새 모듈에는 스펙을 하나 이상 둔다(`Integration` 태그만 있어도 된다).
- 장애 주입 테스트 앱: `libs/fault-injection`의 `com.weave.faultinjection.testapp.FaultInjectionTestApplication`, 업무 엔드포인트 `GET /work`(본문 `WorkController.WORK_RESPONSE`).
- 새 모듈은 `scripts/harness/stop-check.sh`의 모듈 목록에도 넣는다(Stop 훅이 바뀐 모듈만 검사한다).

---

## 2026-11-06 - 1.1 `libs/fault-injection` 모듈의 뼈대
- `settings.gradle.kts`에 `libs:fault-injection`을 넣고, `weave.spring-library`를 쓰는 `build.gradle.kts`(main은 `spring-boot-autoconfigure`만, 테스트는 webmvc·test 스타터와 kotest-spring)를 뒀다. main 패키지는 비어 있어 `.gitkeep`으로 남겼다. 하위 서비스에는 붙이지 않았다.
- 테스트 앱 `FaultInjectionTestApplication`(`GET /work`)과, 그 앱이 Tomcat에서 200·`ok`를 주는지 보는 `Integration` 스모크 스펙을 더했다. 스펙이 없으면 Gradle 9가 `test`를 실패시켜서다.
- Stop 훅 모듈 목록에 `libs/fault-injection`을 더했다.
- 바꾼 파일: `settings.gradle.kts`, `libs/fault-injection/**`, `scripts/harness/stop-check.sh`, `tasks.md`
- 확인: `./gradlew ktlintCheck build` 통과.
- 다음 반복(1.2): 엔드포인트·필터를 시험하려면 테스트 의존성에 `spring-boot-starter-actuator`(`/actuator/faults`)와, 2.2의 traceId 단언용 `project(":libs:observability")`·`testFixtures(project(":libs:observability"))`(`LogCapture`)가 필요하다. main에는 2.2에서 `compileOnly` webmvc·servlet-api·actuator를 더하게 된다.
---

## 2026-11-06 - 1.2 `fault-injection` 시나리오를 `libs/fault-injection` 테스트로 옮긴다
- `FaultInjectionScenarioTest`(`weave.fault-injection.enabled=true`, 노출 `health,info,prometheus,faults`, `@AutoConfigureMetrics`): 기본 분포(데우기 20번 뒤 1,000번), 고정 지연, 에러율 1·0.5, 되돌린다, 장애 중의 헬스 체크, 범위를 벗어난 값 3가지. 테스트마다 `beforeTest`에서 `DELETE`로 되돌린다.
- `FaultInjectionDisabledScenarioTest`: 속성 없이, 노출 목록에는 `faults`를 넣고 404를 본다(지금도 통과).
- 테스트 의존성에 actuator 스타터·actuator-test·prometheus 레지스트리를 더했다. 공통 도구 `FaultsHttpClient.kt`(`FaultSettings`, `percentile`).
- 바꾼 파일: `libs/fault-injection/build.gradle.kts`, `libs/fault-injection/src/test/kotlin/com/weave/faultinjection/*.kt`, `libs/fault-injection/CLAUDE.md`, `tasks.md`
- 확인: `:libs:fault-injection:integrationTest` 11개 중 활성 스펙 9개가 실패(404, 지연 없음), `:libs:fault-injection:test`와 전체 `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과.
- 다음 반복이 알아 둘 것: 테스트가 가정한 JSON 형식은 `GET /actuator/faults` → `{"latencyMedianMs":..,"latencyP99Ms":..,"errorRate":..}`(숫자는 실수로 비교), `POST`는 준 필드만 바꾸고 2xx, `DELETE`는 2xx. 2.2에서 이 형식을 맞춘다.
---

## 2026-11-06 - 1.3 "실험 프로필로 띄운다"·"feed-service에는 없다"를 서비스 테스트로 옮긴다
- 하위 서비스 5개에 `<Svc>FaultsEndpointScenarioTest`(`@ActiveProfiles("experiment")`, `RANDOM_PORT`, 실제 HTTP로 `GET /actuator/faults`, 200과 세 필드가 숫자인지)를 더했다. 서비스의 노출 목록은 테스트가 넣지 않는다(2.3의 `application-experiment.yml`이 맡는다). like-service는 컴포넌트 스캔되는 `MySqlTestConfiguration`을 그대로 쓴다.
- feed-service에 `FeedFaultsEndpointScenarioTest`: `experiment` 프로필과 노출 목록 `health,info,prometheus,faults`로 띄워 404를 본다. 노출해도 엔드포인트가 없다는 것을 보려고 노출 목록을 넣었다.
- 바꾼 파일: `{post,user,like,recommendation,ad}-service/src/test/kotlin/com/weave/*/*FaultsEndpointScenarioTest.kt`, `feed-service/src/test/kotlin/com/weave/feed/FeedFaultsEndpointScenarioTest.kt`, `tasks.md`
- 확인: `./gradlew integrationTest --continue`에서 새 테스트 중 하위 서비스 5개가 404로 실패하고 feed는 통과. 그 밖의 실패는 1.2의 `FaultInjectionScenarioTest`(예정된 것)뿐. `./gradlew ktlintCheck test` 통과.
- 다음 반복이 알아 둘 것: `libs/fault-injection`의 테스트 도구(`FaultsHttpClient`, `FaultSettings`)는 testFixtures가 아니라 서비스 테스트에서 못 쓴다. 서비스 테스트는 `java.net.http.HttpClient`와 `tools.jackson.databind.json.JsonMapper`로 직접 부른다.
---

## 2026-11-06 - 1.4 `ad-serving` "요청 한도" 시나리오를 ad-service 테스트로 옮긴다
- `AdQuotaScenarioTest`(`@ActiveProfiles("experiment")`, `RANDOM_PORT`, `@Import(AdjustableClockConfiguration::class)`): 한도를 넘는다(3번 뒤 시계를 4초 옮겨 4번째가 429), 다음 창(실제 시계, `Retry-After`만큼 잔다), 한도 0, 꺼진 한도(100번), 잘못된 한도 2가지. 테스트마다 `beforeTest`에서 시계를 멈추고 `DELETE /actuator/quota`.
- `AdQuotaDisabledScenarioTest`: 프로필 없이, 노출 목록에 `quota`를 넣고 404(지금도 통과).
- 테스트 시계 `AdjustableClock`(`freeze`/`advance`/`useRealTime`)은 `@TestConfiguration`이라 컴포넌트 스캔에서 빠진다. 다른 ad 테스트에는 들어가지 않는다.
- 바꾼 파일: `ad-service/src/test/kotlin/com/weave/ad/{AdjustableClock,AdQuotaScenarioTest,AdQuotaDisabledScenarioTest}.kt`, `tasks.md`
- 확인: `:ad-service:integrationTest`에서 `AdQuotaScenarioTest` 6개가 404로 실패(그 밖의 실패는 1.3의 `AdFaultsEndpointScenarioTest`뿐), 전체 `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과.
- 다음 반복(3.1)이 알아 둘 것: 테스트가 가정한 형식은 `POST /actuator/quota` `{"limit":n,"windowSeconds":w}` → 2xx, `DELETE` → 2xx, `GET` → 200(본문 형식은 자유. 400 전후로 본문이 같아야 한다). `Retry-After`는 정수 초 헤더. 구현은 컨텍스트의 `Clock`을 `ObjectProvider<Clock>.getIfAvailable { Clock.systemUTC() }`처럼 받아야 테스트 시계가 먹는다. 엔드포인트는 기본으로 꺼져 있어야 한다(노출해도 404).
---
