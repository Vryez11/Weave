# Tasks

## 1. 준비와 시나리오 테스트

시나리오 테스트는 HTTP 수준의 블랙박스 테스트로 쓰고 `@Tags("Integration")`을 붙인다(design 결정 9). 구현이 없으니 `integrationTest`에서는 실패하고, `test`(Stop 훅)는 통과해야 한다. 테스트 이름에 `F-006`을 넣는다.

- [x] 1.1 테스트를 쓰기 위한 준비를 한다.
  - `buildSrc`와 각 모듈에 design 결정 7의 의존성을 더한다.
  - like-service에 `kotlin("plugin.jpa")`와 `allOpen` 설정을 더한다.
  - `weave.spring-service`가 bootJar를 `bootJarElements` 구성으로 내놓게 한다.
  - `e2e` 모듈을 만들어 `settings.gradle.kts`에 넣고, 서비스 6개의 bootJar 경로를 `weave.e2e.jar.<서비스>` 시스템 속성으로 `integrationTest`에 넘긴다(결정 10).
  - `scripts/harness/stop-check.sh`의 모듈 목록에 `e2e`를 더한다.
  - 확인: `./gradlew ktlintCheck build`가 통과하고, `./gradlew :e2e:dependencies --configuration <bootJar를 받는 구성>`에 서비스 6개가 보인다.
- [ ] 1.2 `post-catalog` 스펙의 시나리오를 post-service 테스트로 옮긴다. 고정 데이터의 기댓값은 결정 1의 식으로 계산한다. 확인: `./gradlew :post-service:integrationTest`가 새 테스트에서 실패하고(404 등), `./gradlew :post-service:test`는 통과한다.
- [ ] 1.3 `user-profile` 스펙의 시나리오를 user-service 테스트로 옮긴다. 확인: `./gradlew :user-service:integrationTest`가 새 테스트에서 실패하고, `./gradlew :user-service:test`는 통과한다.
- [ ] 1.4 `recommendation`, `ad-serving` 스펙의 시나리오를 각 서비스의 테스트로 옮긴다. 확인: `./gradlew :recommendation-service:integrationTest :ad-service:integrationTest`가 새 테스트에서 실패하고, 두 모듈의 `test`는 통과한다.
- [ ] 1.5 `post-like` 스펙의 시나리오를 like-service 테스트로 옮긴다.
  - Testcontainers MySQL(`mysql:9.7`, `@ServiceConnection`) 테스트 구성을 테스트 소스에 두고, 테스트마다 테이블을 비운다.
  - 영속 시나리오는 같은 컨테이너로 애플리케이션을 두 번 띄운다.
  - 확인: `./gradlew :like-service:integrationTest`가 새 테스트에서 실패하고, `./gradlew :like-service:test`는 통과한다.
- [ ] 1.6 `feed-aggregation` 스펙에서 "종단 간 동작"을 뺀 시나리오를 feed-service 테스트로 옮긴다.
  - WireMock 하나로 하위 서비스 다섯을 흉내 내고, `spring.http.serviceclient.<의존성>.base-url`을 WireMock 주소로 준다.
  - "연결할 수 없다" 시나리오는 recommendation 주소만 닫힌 포트로 준다.
  - 시간 기준은 스펙의 숫자를 그대로 쓴다.
  - 확인: `./gradlew :feed-service:integrationTest`가 새 테스트에서 실패하고, `./gradlew :feed-service:test`는 통과한다.
- [ ] 1.7 "종단 간 동작" 시나리오를 `e2e` 테스트로 옮긴다.
  - Testcontainers MySQL을 띄우고, 빈 포트로 서비스 6개를 `java -jar`로 띄운다.
  - `/actuator/health` UP을 서비스마다 60초까지 기다린다.
  - 로그는 `e2e/build/e2e-logs/`에 남기고, 끝나면 프로세스를 모두 내린다(결정 10).
  - 스펙이 끝날 때 띄운 프로세스가 모두 내려갔는지(`Process.isAlive()`가 false) 테스트 안에서 검사한다.
  - 확인: `./gradlew :e2e:integrationTest`가 서비스는 모두 뜬 뒤 피드 요청에서 실패하고, 프로세스 정리 검사는 통과한다.

## 2. 하위 서비스

- [ ] 2.1 post-service의 고정 데이터와 `GET /posts`를 구현한다. 데이터 생성과 정렬에 단위 테스트를 둔다. 확인: `./gradlew :post-service:test :post-service:integrationTest`가 통과한다.
- [ ] 2.2 user-service의 고정 데이터와 `GET /users`를 구현한다. 없는 ID와 중복 ID 처리에 단위 테스트를 둔다. 확인: `./gradlew :user-service:test :user-service:integrationTest`가 통과한다.
- [ ] 2.3 recommendation-service의 `GET /recommendations`와 ad-service의 `GET /ads`를 고정 데이터로 구현한다. 확인: `./gradlew :recommendation-service:test :recommendation-service:integrationTest :ad-service:test :ad-service:integrationTest`가 통과한다.
- [ ] 2.4 like-service의 저장과 토글을 구현한다.
  - Flyway `V1__create_like_tables.sql`, 엔티티와 리포지토리, 결정 6의 "읽고 고쳐 저장하는" 토글, `POST /likes/{postId}/toggle`을 만든다.
  - `X-User-Id`를 검증한다.
  - `application.yml`에 DB 설정(`${LIKE_DB_URL}` 등), `ddl-auto: validate`, `open-in-view: false`를 둔다.
  - 확인: like-service 통합 테스트 중 토글, 사용자 식별, 영속 시나리오가 통과한다.
- [ ] 2.5 like-service의 `GET /likes` 배치 조회를 구현한다. 확인: `./gradlew :like-service:test :like-service:integrationTest`가 모두 통과한다.
- [ ] 2.6 like-service를 로컬에서 띄울 수 있게 한다.
  - 테스트 소스의 `TestLikeServiceApplication`이 1.5의 MySQL 테스트 구성을 함께 띄우게 한다.
  - `.env.example`에 `LIKE_DB_URL`, `LIKE_DB_USERNAME`, `LIKE_DB_PASSWORD`를 설명과 함께 적는다.
  - `bootTestRun`과 같은 방식(`SpringApplication.from(...).with(MySQL 테스트 구성)`)으로 빈 포트에 띄워 `POST /likes/1/toggle`을 부르는 통합 테스트를 더한다. Ralph는 `bootTestRun`처럼 끝나지 않는 명령이나 `curl`을 쓸 수 없다.
  - 확인: 그 통합 테스트가 `liked` true를 받아 통과한다. `./gradlew :like-service:bootTestRun`으로 직접 띄워 보는 것은 검토(6.1) 때 사람이 한다.

## 3. feed-service

- [ ] 3.1 하위 서비스 HTTP 클라이언트를 만든다.
  - 의존성마다 `@HttpExchange` 인터페이스와 응답 DTO(`com.weave.feed.client.<의존성>`)를 만든다.
  - `@ImportHttpServices` 그룹 `post`, `user`, `like`, `recommendation`, `ad`를 만들고, `application.yml`에 결정 11의 주소를 둔다.
  - `ClientHttpRequestFactoryBuilder` Bean이 `weave.http-client.max-connections-per-route` 속성이 있을 때만 커넥션 풀 크기를 바꾸게 한다. 값은 아직 넣지 않는다.
  - 그룹마다 HttpClient(커넥션 풀)가 따로 생기는지 통합 테스트로 확인하고, 같다면 design의 위험 항목대로 그룹 설정자로 바꾼다.
  - 확인: 그 통합 테스트가 통과하고, `./gradlew :feed-service:test`가 통과한다.
- [ ] 3.2 Gateway 다섯 개와 FeedAssembler를 만든다.
  - Gateway의 조회 메서드는 `applicationTaskExecutor`에서 도는 `CompletableFuture`를 돌려준다.
  - FeedAssembler는 결정 4의 순서로 부르고 피드 응답을 만든다.
  - FeedAssembler 단위 테스트(MockK)로 아래를 확인한다: 작성자 없음은 null, 좋아요 매핑, 게시글 없음이면 user·like를 부르지 않음, 작성자 ID 중복 제거.
  - 확인: `./gradlew :feed-service:test -Pkotest.filter.tests='*F-006*'`가 통과한다.
- [ ] 3.3 `GET /feed` 컨트롤러와 요청 검증을 만든다. 확인: feed-service 통합 테스트 중 피드 응답 구성, 요청 검증, 호출 순서, 실패 전파 시나리오가 통과한다.
- [ ] 3.4 `POST /feed/likes/{postId}/toggle`을 만든다. 확인: `./gradlew :feed-service:test :feed-service:integrationTest`가 모두 통과한다.

## 4. 기본값 기록과 용량 설정

설정값을 바꾸는 4.2와 4.3은 각각 따로 커밋한다(커밋 컨벤션).

- [ ] 4.1 MVP 기본값을 실제 객체에서 꺼내 확인하고 `docs/architecture.md` 4-2에 기록한다.
  - `applicationTaskExecutor`의 코어·최대 스레드와 대기열 크기
  - HttpClient 5의 연결 타임아웃, 응답 타임아웃, 라우트당·전체 최대 커넥션
  - feed-service Tomcat 최대 스레드
  - 확인 방법(테스트에서 Bean을 꺼내 읽기, 또는 Actuator `configprops`)도 함께 적는다. design 결정 8 표의 예상과 다르면 계산 비교를 다시 한다.
  - 확인: 4-2에 값과 확인 방법이 있고, `docs(architecture)` 커밋 하나로 남는다.
- [ ] 4.2 feed-service의 `weave.http-client.max-connections-per-route`를 20으로 둔다.
  - 커밋 본문에 결정 8의 계산(200 RPS × 50ms = 10, 여유 2배)과 4.1에서 확인한 기본값을 적는다.
  - 확인: 3.1의 통합 테스트에 라우트당 최대 커넥션이 20인지 보는 검사를 더해 통과한다.
- [ ] 4.3 feed-service의 `spring.task.execution.pool.core-size`를 100으로 둔다. 대기열은 바꾸지 않는다.
  - 커밋 본문에 결정 8의 계산(200 RPS × 호출 5번 × 50ms = 50, 여유 2배)을 적는다.
  - 확인: `applicationTaskExecutor`의 코어가 100이고 대기열은 4.1에서 기록한 기본값인지 보는 통합 테스트가 통과한다.

## 5. 종단 간 확인과 문서

- [ ] 5.1 종단 간 테스트를 통과시킨다. 1.1의 Gradle 연결이나 서비스 실행 인자에서 고칠 곳이 나오면 여기서 고친다. 이미 커밋된 1.7의 테스트는 고치지 않는다. 확인: `./gradlew :e2e:integrationTest`가 프로세스 정리 검사까지 모두 통과한다.
- [ ] 5.2 명령과 모듈을 문서에 더한다.
  - `CLAUDE.md`의 명령 표와 하네스 규칙 5장에 `./gradlew :like-service:bootTestRun`, `./gradlew :e2e:integrationTest`를 더한다.
  - `CLAUDE.md` 구조 표와 README 모듈 표에 `e2e`를 더한다.
  - 확인: 문서에 적은 `./gradlew :e2e:integrationTest`를 그대로 돌려 통과한다. `bootTestRun`은 끝나지 않는 명령이라 6.1에서 사람이 확인한다.
- [ ] 5.3 전체 검사를 돌린다. 확인: `./gradlew ktlintCheck build`와 `openspec validate --all --strict --no-interactive`가 통과한다.

## 6. 검토와 change 완료 조건

이 묶음은 Ralph 루프가 하지 않는다([하네스 규칙](../../../conventions/harness.md) 3-3, 4장).

- [ ] 6.1 구현에 참여하지 않은 리뷰어가 하네스 규칙 3-3의 검토 항목으로 change 문서와 diff를 본다. 이 change에 해당하지 않는 항목(3번 격리, 4번 재시도, 6번 traceId)은 "해당 없음"으로 적는다. 확인: 막는 지적이 없다.
- [ ] 6.2 하네스 규칙 4장의 change 완료 조건을 확인한다. 장애 실험 세트는 아직 없으므로(고도화 2) 해당 없음으로 적는다. 구현하며 요구사항이 바뀌었다면 PRD F-006도 고친다. 확인: 체크리스트가 모두 채워진다.

## Workflow follow-up

- `/opsx:archive`로 스펙을 `openspec/specs/`에 합친다.
- `develop`으로 PR을 열고 CI가 통과하면 merge한다.
- MVP 릴리스: `develop` → `main` 릴리스 PR, merge 뒤 `v0.1.0` 태그.
