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
