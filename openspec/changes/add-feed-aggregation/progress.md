# 진행 메모: add-feed-aggregation

## 알아 둘 패턴
- Ralph 중에는 이미 커밋된 `src/test/` 파일을 고칠 수 없다(pre-file 훅). 테스트 보조 코드도 처음 커밋할 때 다음 작업까지 쓸 수 있게 만든다.
- like-service는 JPA 의존성 때문에 컨텍스트 테스트에 DB가 필요하다. 테스트 소스의 `com.weave.like.MySqlTestConfiguration`(`@Configuration`이라 컴포넌트 스캔으로 자동 적용, JVM에 컨테이너 하나, 컨텍스트가 닫혀도 안 멈춤)을 쓴다. 1.5의 "MySQL 테스트 구성"은 이미 있다. 테이블 비우기만 테스트에서 한다.
- Testcontainers는 2.0.5다. MySQL 클래스는 `org.testcontainers.mysql.MySQLContainer`(제네릭 없음).
- e2e의 jar 경로 시스템 속성 이름은 모듈 이름을 그대로 쓴다: `weave.e2e.jar.feed-service`, `weave.e2e.jar.like-service` 등. `integrationTest` 태스크에만 넘긴다.
- Gradle 9.8에서 `by configurations.creating`, `by tasks.registering`은 deprecated다. `configurations.create(name)`, `tasks.register<T>(name)`을 쓴다.

---

## 2026-11-04 17:30 - 1.1 테스트를 쓰기 위한 준비
- design 결정 7의 의존성을 buildSrc(kotlin-noarg), feed-service, like-service, e2e에 더했다. like-service에 `kotlin("plugin.jpa")`와 `allOpen`(Entity, MappedSuperclass, Embeddable).
- `weave.spring-service`가 `bootJarElements` 구성으로 bootJar를 내놓는다. `e2e` 모듈의 `serviceJars` 구성이 서비스 6개를 받고, `integrationTest`에 `-Dweave.e2e.jar.<모듈>=<경로>`를 넘긴다.
- JPA를 넣자 기존 `LikeServiceApplicationTests`가 DB 없이 깨져서, 그 테스트를 고치지 않고 `MySqlTestConfiguration`을 테스트 소스에 두었다(1.5 첫 항목을 앞당긴 것).
- `stop-check.sh` 모듈 목록에 `e2e`를 더했다.
- 바꾼 파일: `buildSrc/build.gradle.kts`, `buildSrc/src/main/kotlin/weave.spring-service.gradle.kts`, `settings.gradle.kts`, `feed-service/build.gradle.kts`, `like-service/build.gradle.kts`, `like-service/src/test/kotlin/com/weave/like/MySqlTestConfiguration.kt`, `like-service/CLAUDE.md`, `e2e/build.gradle.kts`, `scripts/harness/stop-check.sh`
- 확인: `./gradlew ktlintCheck build` 통과(like-service 통합 테스트가 MySQL 컨테이너로 뜸), `./gradlew :e2e:dependencies --configuration serviceJars`에 서비스 6개.
- 다음 반복이 알아 둘 것: 시스템 속성 전달은 e2e 테스트가 아직 없어 실제로 돌려 보지 못했다. 1.7에서 `System.getProperty("weave.e2e.jar.feed-service")`가 null이면 `e2e/build.gradle.kts`의 `jvmArgumentProviders`를 본다.
---
