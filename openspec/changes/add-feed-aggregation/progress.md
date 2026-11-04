# 진행 메모: add-feed-aggregation

## 알아 둘 패턴
- Ralph 중에는 이미 커밋된 `src/test/` 파일을 고칠 수 없다(pre-file 훅). 테스트 보조 코드도 처음 커밋할 때 다음 작업까지 쓸 수 있게 만든다.
- like-service는 JPA 의존성 때문에 컨텍스트 테스트에 DB가 필요하다. 테스트 소스의 `com.weave.like.MySqlTestConfiguration`(`@Configuration`이라 컴포넌트 스캔으로 자동 적용, JVM에 컨테이너 하나, 컨텍스트가 닫혀도 안 멈춤)을 쓴다. 1.5의 "MySQL 테스트 구성"은 이미 있다. 테이블 비우기만 테스트에서 한다.
- Testcontainers는 2.0.5다. MySQL 클래스는 `org.testcontainers.mysql.MySQLContainer`(제네릭 없음).
- e2e의 jar 경로 시스템 속성 이름은 모듈 이름을 그대로 쓴다: `weave.e2e.jar.feed-service`, `weave.e2e.jar.like-service` 등. `integrationTest` 태스크에만 넘긴다.
- Gradle 9.8에서 `by configurations.creating`, `by tasks.registering`은 deprecated다. `configurations.create(name)`, `tasks.register<T>(name)`을 쓴다.
- Kotest 스펙 클래스의 생성자 주입은 안 된다(`SpecInstantiationException`). Bean은 테스트 안에서 `testContextManager().testContext.applicationContext.getBean(...)`으로 꺼낸다(`io.kotest.extensions.spring.testContextManager`, suspend 함수 안에서만).
- Boot 4의 MockMvc 어노테이션은 `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`다. JSON은 `tools.jackson.module.kotlin.jacksonObjectMapper()`로 `Map`/`List`로 읽어 비교한다(숫자는 `Int`로 읽힌다).
- Kotest `beforeEach` 안에서도 `testContextManager()`를 쓸 수 있다(like-service 테이블 비우기에서 확인).

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

## 2026-11-04 18:10 - 1.2 post-catalog 시나리오 테스트
- `PostCatalogScenarioTest`(`@SpringBootTest` + MockMvc, `@Tags("Integration")`)에 스펙 시나리오 5개를 옮겼다. 범위 밖 개수는 0, 51, abc 세 경우로 나눴다.
- 기댓값은 design 결정 1의 식(`expectedLatestPosts(size)`, 파일 안 private 함수)으로 만들어 응답 전체를 비교한다. 키 집합도 정확히 다섯 개인지 본다. "다시 띄워도 같은 데이터"는 결정 9대로 식과의 비교로 대신했다.
- 바꾼 파일: `post-service/src/test/kotlin/com/weave/post/PostCatalogScenarioTest.kt`
- 확인: `:post-service:integrationTest`에서 새 테스트 7개가 상태 검사(404)에서 실패, `:post-service:test`와 `ktlintCheck compileKotlin compileTestKotlin` 통과.
- 다음 반복이 알아 둘 것: 2.1 구현은 응답을 `{"posts": [...]}`로, `createdAt`을 `Instant.toString()`과 같은 문자열(예: `2026-01-01T03:20:00Z`)로 내보내야 통과한다. 응답 객체에 필드를 더 넣으면 키 집합 검사가 깨진다.
---

## 2026-11-04 18:40 - 1.3 user-profile 시나리오 테스트
- `UserProfileScenarioTest`(`@SpringBootTest` + MockMvc, `@Tags("Integration")`)에 스펙 시나리오 5개를 옮겼다. "ID가 없거나 너무 많다"는 ids 없음과 101개 두 테스트로 나눴다.
- 스펙이 순서를 정하지 않아 여러 명이 오는 경우는 `shouldContainExactlyInAnyOrder`로 비교한다. 키 집합이 정확히 `id`, `nickname`, `profileImageUrl`인지도 본다.
- 바꾼 파일: `user-service/src/test/kotlin/com/weave/user/UserProfileScenarioTest.kt`
- 확인: `:user-service:integrationTest`에서 새 테스트 6개가 상태 검사(404)에서 실패, `:user-service:test`와 `ktlintCheck compileKotlin compileTestKotlin` 통과.
- 다음 반복이 알아 둘 것: 2.2 구현은 `{"users": [...]}`로 내보내야 한다. ID 101개 요청은 1~101이라 범위 밖 ID가 섞여 있어도 개수 검사로 400이어야 한다.
---

## 2026-11-04 19:10 - 1.4 recommendation, ad-serving 시나리오 테스트
- `RecommendationScenarioTest`(시나리오 4개, 범위 밖은 size 0·21로 나눔)와 `AdServingScenarioTest`(시나리오 3개, 범위 밖은 size 0·6)를 `@SpringBootTest` + MockMvc, `@Tags("Integration")`으로 썼다.
- 기댓값은 결정 1의 식(파일 안 private 함수)으로 만들어 응답 전체를 비교하고, 키 집합도 정확히 본다. "사용자와 관계없다"는 `X-User-Id` 1, 2, 없음 세 응답을 비교한다.
- 바꾼 파일: `recommendation-service/src/test/kotlin/com/weave/recommendation/RecommendationScenarioTest.kt`, `ad-service/src/test/kotlin/com/weave/ad/AdServingScenarioTest.kt`
- 확인: 두 모듈 `integrationTest`에서 새 테스트 9개가 모두 상태 검사(404)에서 실패, 두 모듈 `test`와 `ktlintCheck compileKotlin compileTestKotlin`, `openspec validate` 통과.
- 다음 반복이 알아 둘 것: 2.3 구현은 `{"recommendations": [...]}`(필드 `postId`, `title`, `reason`만), `{"ads": [...]}`(필드 `id`, `title`, `imageUrl`, `landingUrl`만)로 내보내야 한다. 기본 size는 각각 5, 2이고 앞에서부터 자른다.
---

## 2026-11-04 17:35 - 1.5 post-like 시나리오 테스트
- `PostLikeScenarioTest`(`@SpringBootTest` + MockMvc, `@Tags("Integration")`)에 스펙 시나리오 12개를 16개 테스트로 옮겼다(헤더 없음 GET·POST, 잘못된 헤더 abc·0, ID 없음·101개, 잘못된 postId 0·abc로 나눔). 요구사항의 "같은 ID 여러 번이면 한 번만"도 테스트로 더했다.
- GIVEN은 토글 API로 만든다. `beforeEach`가 `information_schema`로 있는 테이블(`post_like`, `post_like_count`)만 `DELETE`로 비운다.
- 영속 시나리오는 `SpringApplicationBuilder(LikeServiceApplication)`를 `server.port=0`으로 두 번 띄워(`use`로 닫음) `java.net.http.HttpClient`로 부른다. 컴포넌트 스캔으로 같은 MySQL 컨테이너를 쓴다.
- 바꾼 파일: `like-service/src/test/kotlin/com/weave/like/PostLikeScenarioTest.kt`
- 확인: `:like-service:integrationTest`에서 새 테스트 16개가 모두 상태 검사(404)에서 실패, `:like-service:test`와 `ktlintCheck compileKotlin compileTestKotlin`, `openspec validate` 통과.
- 다음 반복이 알아 둘 것: 2.4·2.5 구현은 `GET /likes`를 `{"likes": [...]}`(항목 필드 `postId`, `likeCount`, `liked`만), 토글을 감싸지 않은 `{"postId", "liked", "likeCount"}`(이 세 키만)로 내보내야 한다. 비우기가 테이블 이름에 기대므로 Flyway 테이블 이름은 design 결정 6대로 `post_like`, `post_like_count`여야 한다.
---
