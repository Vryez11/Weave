# Design

## Context

- 서비스 6개는 지금 Spring Boot 애플리케이션 클래스와 `application.yml`(이름, 포트, Actuator)만 있는 빈 모듈이다. 공통 설정은 `buildSrc`의 `weave.spring-service` 컨벤션 플러그인이 넣는다(webmvc, actuator, jackson-module-kotlin, Kotest, springmockk).
- 테스트는 Kotest 태그로 나뉜다. `./gradlew test`는 `Integration` 태그가 없는 것만, `./gradlew integrationTest`는 태그가 있는 것만 돈다. Stop 훅은 바뀐 모듈의 `test`만 돌린다.
- 서비스끼리 코드를 공유하지 않는다. feed-service는 하위 서비스 응답 형식을 자기 쪽에 따로 정의한다.
- 이 change는 [아키텍처](../../../../docs/architecture.md) 12장 표대로 4-1(호출 순서), 4-2(MVP 구성과 기본값 기록), 5-1(클라이언트 구성), 8장(토글 좋아요)을 가져간다. 다르게 정한 곳은 각 결정에 이유와 함께 적는다.

## Goals / Non-Goals

**Goals:**

- 고도화 3·4·5가 호출을 감싸는 코드만 더하면 되도록, 호출 경로의 층(Controller → FeedAssembler → Gateway → HTTP Interface)을 지금 만들어 둔다.
- MVP의 기본값(공유 스레드 풀, HttpClient 타임아웃과 풀)을 숫자로 확인해 기준선의 전제로 남긴다.
- 종단 간 테스트로 서비스 6개가 실제 프로세스로 맞물리는 것을 확인한다.

**Non-Goals:**

- Docker Compose, Toxiproxy, JSON 로그, traceId 전파. 고도화 1·2에서 한다.
- 좋아요의 동시성·재시도 정합성. 토글은 일부러 깨지는 방식으로 둔다(결정 6).
- 응답 계약의 degrade 표시(빠진 영역, stale). 고도화 4에서 정한다.

## Decisions

### 1. 하위 서비스 API와 고정 데이터

API 형식은 specs를 따른다. 고정 데이터는 아래 식으로 시작할 때 코드에서 만들고, 메모리에 들고 있는다. 파일이나 DB에 넣지 않는 것은 값이 식으로 정해져 있어 테스트에서 기댓값을 바로 계산할 수 있기 때문이다.

| 서비스 | 데이터 |
|---|---|
| post | 게시글 200개. `id` 1~200, `authorId = (id - 1) % 50 + 1`, `title = "게시글 {id}"`, `content = "게시글 {id}의 본문"`, `createdAt = 2026-01-01T00:00:00Z + id분`. 최신 게시글은 `id` 200(`authorId` 50, `2026-01-01T03:20:00Z`) |
| user | 사용자 50명. `id` 1~50, `nickname = "사용자{id}"`, `profileImageUrl = "https://cdn.example.com/users/{id}.png"` |
| recommendation | 20개. k = 1~20에 대해 `postId = 10k`, `title = "게시글 {postId}"`, `reason = "많이 본 게시글"`. k 순서대로 준다 |
| ad | 5개. `id` 1~5, `title = "광고 {id}"`, `imageUrl = "https://cdn.example.com/ads/{id}.png"`, `landingUrl = "https://example.com/ads/{id}"`. `id` 순서대로 준다 |

- 응답 JSON은 목록을 감싼 객체로 둔다: `{"posts": [...]}`, `{"users": [...]}`, `{"likes": [...]}`, `{"recommendations": [...]}`, `{"ads": [...]}`. 나중에 필드를 더해도 형식이 깨지지 않는다.
- 시각은 `Instant`를 ISO-8601 문자열로 내보낸다(Jackson 3 기본값).
- 파라미터 범위 검사는 컨트롤러에서 직접 하고, 벗어나면 `ResponseStatusException(BAD_REQUEST)`를 던진다. 범위 검사 몇 개를 위해 validation 스타터를 더하지 않는다. 헤더 누락과 타입 불일치는 Spring MVC가 이미 400으로 처리한다.

### 2. feed-service의 층

```
FeedController ─ GET /feed, POST /feed/likes/{postId}/toggle
  └ FeedAssembler ─ 호출 순서, 응답 구성
      └ PostGateway, UserGateway, LikeGateway, RecommendationGateway, AdGateway
          └ PostClient 등 (@HttpExchange 인터페이스)
```

- **Gateway를 MVP에서 미리 둔다.** 아키텍처 5-2대로 고도화 3에서 Resilience4j 어노테이션이 Gateway 메서드에 붙는다. 지금 FeedAssembler가 HTTP 클라이언트를 바로 부르게 두면, 고도화 3에서 층을 새로 끼워 넣으며 FeedAssembler까지 고쳐야 한다.
- **Gateway의 조회 메서드는 `CompletableFuture`를 돌려준다.** 고도화 3의 ThreadPool 벌크헤드와 TimeLimiter가 `CompletableFuture`를 돌려주는 메서드에만 걸리기 때문이다. MVP에서는 Gateway가 공유 스레드 풀에서 HTTP 호출을 돌린다(결정 4).
- **좋아요 토글 전달은 동기 호출이다.** 피드 집계처럼 병렬로 부를 것이 없다. Gateway의 `toggle`이 요청 스레드에서 바로 부른다.
- feed-service의 하위 서비스 응답 DTO는 `com.weave.feed.client.<의존성>` 패키지에 둔다. 피드 응답 DTO와 섞지 않는다.

### 3. HTTP 클라이언트 구성

- **Spring Framework 7의 HTTP 서비스 그룹을 쓴다.** 의존성마다 `@ImportHttpServices(group = "<의존성>", types = [...])`로 그룹을 하나씩 만들고, 그룹 이름은 Resilience4j 인스턴스 이름과 같게 `post`, `user`, `like`, `recommendation`, `ad`로 한다. 주소는 Boot의 `spring.http.serviceclient.<그룹>.base-url`로 준다. `HttpServiceProxyFactory`를 직접 만들 수도 있지만, Boot 4의 그룹 설정이 그룹마다 `RestClient`를 따로 만들고 속성으로 타임아웃까지 열어 준다.
- **Apache HttpClient 5를 쓴다.** `httpclient5`가 클래스패스에 있으면 Boot가 그것을 고른다. 커넥션 풀 크기는 `ClientHttpRequestFactoryBuilder` Bean 하나를 `HttpComponentsClientHttpRequestFactoryBuilder.withConnectionManagerCustomizer`로 바꿔 정한다(결정 8). Boot가 그룹마다 이 빌더로 요청 팩토리를 새로 만들어, 그룹마다 HttpClient와 커넥션 풀이 따로 생긴다. 아키텍처 5-1의 "의존성마다 커넥션 풀"이 이것으로 채워진다. 정말 따로 생기는지는 작업 3.1에서 테스트로 확인한다.
- **풀 크기는 MVP에서 모든 의존성에 같은 값을 쓴다.** 아키텍처 5-1은 의존성마다 값을 정한다고 했지만, MVP는 의존성별 평시 지연이 같다고 두므로(PRD F-007, p99 약 50ms) 계산 결과도 같다. 의존성별로 다르게 둘 필요는 기준선 측정 뒤(고도화 3)에 판단한다.
- **타임아웃은 설정하지 않는다.** `spring.http.serviceclient.*.connect-timeout`, `read-timeout`을 두지 않아 라이브러리 기본값이 쓰인다(아키텍처 4-2). 실제 값은 작업 4.1에서 확인해 기록한다.
- **HttpClient 5의 자동 재시도는 끈다.** 기본 재시도 전략(`DefaultHttpRequestRetryStrategy`)은 429와 503에 1초 뒤 한 번 더 보내고, 멱등인지 보지 않아 토글 POST도 다시 보낸다. Boot의 빌더도 이것을 끄지 않는다. MVP의 "재시도 없음"과 어긋나고, 고도화 2의 기준선과 고도화 6의 토글 중복 재현을 흐리므로 `HttpClientBuilder.disableAutomaticRetries()`로 끈다. 보호 장치를 더하는 것이 아니라 라이브러리에 숨어 있던 보호 장치를 빼는 것이다. 구현 뒤 첫 검토에서 찾았다.
- **Toxiproxy를 거치는 것은 고도화 2부터다.** 아키텍처 2장은 MVP도 Toxiproxy를 거친다고 했지만, Toxiproxy는 고도화 2에서 Docker Compose와 함께 들어온다. 주소를 환경 변수로 덮어쓸 수 있게 해 두면(`POST_BASE_URL` 등) 그때 코드 변경 없이 프록시를 가리키게 할 수 있다. 기준선은 고도화 2에서 Toxiproxy를 거친 상태로 재므로, 결과를 비교할 때 연결 구조가 바뀌지 않는다는 원칙은 지켜진다.

### 4. 병렬 호출과 공유 스레드 풀

- Gateway는 `CompletableFuture.supplyAsync(호출, applicationTaskExecutor)`로 부른다. `applicationTaskExecutor`는 Boot가 만드는 `ThreadPoolTaskExecutor`이고, 이름으로 주입한다.
- FeedAssembler는 post, recommendation, ad를 한꺼번에 시작하고, post가 끝나면(`thenCompose`) user와 like를 시작한 뒤, 모두를 `join()`으로 기다린다. 기다리는 시간에 상한이 없다.
- 이것이 아키텍처 4-2의 "실무에서 흔한 실수"다. 모든 의존성이 풀 하나와 무제한 대기열을 함께 쓴다. 하위 서비스 하나가 느려지면 그 호출이 풀 스레드를 붙잡고, 대기열이 쌓이고, 요청 스레드가 `join()`에서 함께 묶인다.
- 풀 크기는 평시 부하를 감당하도록 계산해 정하고(결정 8), 대기열은 Boot 기본값(무제한)을 그대로 둔다.

### 5. 오류 처리

- 하위 서비스가 5xx를 주면 `RestClient`가 `HttpServerErrorException`을, 연결이 안 되면 `ResourceAccessException`을 던진다. `join()`에서 `CompletionException`으로 감싸여 나오고, 따로 처리하지 않아 Spring MVC 기본 오류 처리로 500이 된다.
- 하위 서비스의 4xx도 같은 경로로 500이 된다. feed-service가 요청을 검증한 뒤 부르므로 정상 흐름에서는 생기지 않는다.
- 오류 응답 본문은 Boot 기본(`/error`)을 쓴다. 오류 응답 계약은 고도화 4에서 정한다.

### 6. 좋아요 저장 (토글)

아키텍처 8장의 표에 대리 키를 더했다.

| 테이블 | 열 | 제약 |
|---|---|---|
| `post_like` | `id` BIGINT 자동 증가, `user_id` BIGINT, `post_id` BIGINT, `created_at` DATETIME(6) | `id` 기본 키, `(user_id, post_id)` 유니크 |
| `post_like_count` | `post_id` BIGINT, `like_count` BIGINT | `post_id` 기본 키 |

- `id`를 더한 이유: JPA 엔티티에 복합 키를 쓰면 `@IdClass`나 `@EmbeddedId`가 필요해 MVP 코드가 늘어난다. 유니크 제약은 그대로 두어, 고도화 6에서 "유니크 위반을 이미 반영된 요청으로 처리"를 다룰 수 있다.
- 토글은 한 트랜잭션에서 아래 순서로 한다. **읽고 고쳐 저장하는 방식을 일부러 쓴다.**
  1. `(user_id, post_id)`로 `post_like`를 찾는다. 있으면 지우고, 없으면 넣는다.
  2. `post_like_count`를 엔티티로 읽어(없으면 0으로 새로 만든다) 1을 더하거나 빼고 저장한다.
- 이 방식은 동시 요청에서 갱신 유실이 생기고, 같은 사용자의 동시 첫 요청은 유니크 위반으로 500이 된다. 고도화 6(F-003)에서 재현할 대상이므로 이 change에서 막지 않는다.
- 배치 조회는 `post_like_count`에서 `post_id IN (...)`로 수를, `post_like`에서 `user_id = ? AND post_id IN (...)`로 내 상태를 읽는다.
- 스키마는 Flyway(`like-service/src/main/resources/db/migration/V1__create_like_tables.sql`)로 만들고, JPA는 `ddl-auto: validate`, `open-in-view: false`로 둔다.
- Kotlin 엔티티는 `kotlin("plugin.jpa")`(기본 생성자)와 `allOpen`(`@Entity` 등)을 쓴다.

### 7. 추가하는 의존성

[하네스 규칙](../../../../conventions/harness.md) 2-2에 따라 의존성 추가는 사람이 확인한다. 이 change를 승인하면 아래 목록을 확인한 것으로 보고, 구현 중에 목록 밖의 의존성이 필요하면 멈춘다. 버전이 없는 것은 Boot BOM이 정한다.

| 모듈 | 의존성 | 용도 |
|---|---|---|
| `buildSrc` | `org.jetbrains.kotlin:kotlin-noarg:2.3.21` | `kotlin("plugin.jpa")` |
| feed-service | `spring-boot-starter-restclient`, `org.apache.httpcomponents.client5:httpclient5` | HTTP 서비스 그룹, HttpClient 5 |
| feed-service (테스트) | `org.wiremock:wiremock-standalone:3.13.2` | 하위 서비스 흉내. standalone은 Jetty를 숨겨 Boot의 Tomcat과 부딪히지 않는다. 4.x는 아직 베타다 |
| like-service | `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `org.flywaydb:flyway-mysql`, `com.mysql:mysql-connector-j`(runtime) | 저장 |
| like-service (테스트) | `org.testcontainers:testcontainers-mysql` | MySQL 컨테이너. 처음에는 `spring-boot-testcontainers`(`@ServiceConnection`)도 넣었지만, 결정 9의 이유로 컨테이너를 Bean으로 두지 않게 되어 뺐다 |
| e2e | `org.testcontainers:testcontainers-mysql`, `tools.jackson.module:jackson-module-kotlin` | MySQL 컨테이너, 응답 읽기 |

MySQL 이미지는 `mysql:9.7`을 쓴다. 드라이버(9.7.0, Boot BOM)와 같은 계열이고, 아키텍처 1장의 "MySQL 9"와 맞다.

### 8. 용량 설정값과 근거

보호 장치가 없다는 것은 장애 대응이 없다는 뜻이지 평시 용량이 모자라도 된다는 뜻이 아니다(아키텍처 5-1). 평시 용량은 리틀의 법칙(동시에 처리 중인 일 ≈ 초당 도착 수 × 처리 시간)으로 계산한다. 입력값은 아직 측정 전이라 계획값을 쓰고, 고도화 2의 기준선에서 다시 확인한다.

- 실험 부하: 200 RPS (아키텍처 9-1 시작값)
- 하위 서비스 평시 p99: 50ms (PRD F-007)

| 값 | 계산 | 정한 값 | 기본값 |
|---|---|---|---|
| 의존성별 커넥션 풀 (라우트당 최대, 전체 최대) | 피드 1건당 의존성마다 1번 부른다. 200 × 0.05s = 동시 10개, 여유 2배 | 20 | 확인 후 기록 (HttpClient 5는 라우트당 5, 전체 25로 알려져 있다) |
| `applicationTaskExecutor` 코어 스레드 | 피드 1건이 풀 스레드를 쓰는 시간은 호출 5번 × 0.05s = 0.25s. 200 × 0.25 = 동시 50개, 여유 2배 | 100 | 확인 후 기록 (Boot는 코어 8로 알려져 있다) |
| `applicationTaskExecutor` 대기열 | 바꾸지 않는다. 무제한 대기열이 MVP가 재현할 실수다 | 기본값 | 확인 후 기록 |
| Tomcat 최대 스레드 (feed-service) | 피드 p99를 하위 p99 두 번(post → user·like)과 여유로 약 150ms로 두면 200 × 0.15 = 동시 30개. 기본값으로 충분하다 | 기본값 | 확인 후 기록 (200으로 알려져 있다) |

- 기본값 그대로면 평시에 이미 막힌다. 라우트당 커넥션 5개는 동시 10개보다 작고, 코어 스레드 8개는 동시 50개보다 작아 평시에도 대기열이 쌓인다. 그러면 기준선이 "장애 때 무너지는 모습"이 아니라 "평시에도 느린 모습"이 되어 비교가 흐려진다.
- 값은 `application.yml`에 둔다. 커넥션 풀은 `weave.http-client.max-connections-per-route`(전체 최대도 같은 값), 스레드 풀은 Boot의 `spring.task.execution.pool.core-size`다. 설정값 커밋은 다른 변경과 섞지 않는다([커밋 컨벤션](../../../../conventions/commit.md)).
- 기본값은 작업 4.1에서 실제 객체에서 꺼내 확인하고 아키텍처 4-2에 기록한다. 위 표의 "알려져 있다"는 확인 전의 예상이다.

### 9. 테스트 배치

시나리오 테스트는 **HTTP 수준의 블랙박스 테스트**로 쓴다. 애플리케이션 클래스 말고는 구현 코드를 참조하지 않아, 구현 전에 써도 컴파일되고 실행하면 실패한다. Spring 컨텍스트, WireMock, Testcontainers를 쓰므로 모두 `@Tags("Integration")`을 붙인다. 테스트 이름에 기능 ID(`F-006`)를 넣는다.

| 모듈 | 시나리오 테스트 | 방식 |
|---|---|---|
| post, user, recommendation, ad | 각 스펙의 시나리오 | `@SpringBootTest` + MockMvc. 고정 데이터 시나리오는 결정 1의 식으로 기댓값을 계산해 비교한다. 같은 식으로 만든 값이 매번 오는지 보는 것이므로 "다시 띄워도 같다"를 대신한다 |
| like | `post-like` 스펙의 시나리오 | `@SpringBootTest` + MockMvc + Testcontainers MySQL. 컨테이너는 JVM에 하나를 직접 띄우고 접속 정보만 `JdbcConnectionDetails` Bean으로 내놓는다. 컨테이너를 `@ServiceConnection` Bean으로 두면 Boot가 컨텍스트를 닫을 때 컨테이너도 멈춰, 영속 시나리오의 두 번째 애플리케이션이 빈 DB를 본다(구현 중 2.4에서 찾음). 테스트마다 테이블을 비운다. 영속 시나리오는 같은 컨테이너로 `SpringApplicationBuilder`를 두 번 띄운다 |
| feed | `feed-aggregation` 스펙에서 종단 간을 뺀 시나리오 | `@SpringBootTest(webEnvironment = RANDOM_PORT)` + WireMock. 하위 서비스 다섯을 WireMock 하나로 흉내 내고, `spring.http.serviceclient.<의존성>.base-url`을 WireMock 주소로 준다. 호출 여부와 파라미터는 WireMock의 요청 기록으로 확인한다 |
| e2e | 종단 간 시나리오 | 결정 10 |

구현 작업에서 더하는 단위 테스트(태그 없음, Stop 훅이 돌린다):

- FeedAssembler: Gateway를 MockK로 바꿔 응답 구성(작성자 없음, 좋아요 매핑, 게시글 없음이면 user·like를 부르지 않음)
- 각 하위 서비스의 고정 데이터 생성과 조회 로직

시간이 걸리는 시나리오(400ms 지연 병렬, 2초 지연)는 WireMock의 고정 지연으로 만들고, 판정 기준은 스펙의 숫자(1,200ms 미만, 2초 이상)를 쓴다. 순차면 2,000ms라 여유가 800ms 있다.

### 10. 종단 간 테스트 (`e2e` 모듈)

- **서비스를 각자 프로세스로 띄운다.** 한 JVM 안에 Spring 컨텍스트 6개를 띄우면 서비스마다 `application.yml`이 클래스패스에서 부딪히고, like-service의 JPA 자동 설정이 다른 서비스에도 걸린다. 실제 배포 단위인 bootJar를 띄우는 편이 고도화 2의 Compose 실행과도 가깝다.
- 새 Gradle 모듈 `e2e`는 `weave.kotlin-conventions`만 쓰고 서비스 코드에 기대지 않는다. 테스트는 모두 `@Tags("Integration")`이다.
- bootJar는 Gradle 구성으로 받는다. `weave.spring-service` 플러그인이 `bootJar` 산출물을 소비 가능한 구성(`bootJarElements`)으로 내놓고, `e2e`가 서비스 6개에 이 구성으로 기대어 jar 경로를 시스템 속성(`weave.e2e.jar.<서비스>`)으로 테스트에 넘긴다. 다른 프로젝트의 태스크를 직접 참조하지 않는다.
- 테스트는 Testcontainers로 MySQL을 띄우고, 빈 포트를 골라 서비스 6개를 `java -jar`로 띄운다.
  - 주소, DB 접속 정보, 포트는 명령줄 인자로 넘긴다.
  - 각 서비스의 `/actuator/health`가 UP이 될 때까지 기다린다. 서비스마다 상한은 60초다.
  - 서비스 로그는 `e2e/build/e2e-logs/<서비스>.log`에 남긴다.
  - 끝나면 프로세스를 모두 내린다.
- `java`는 테스트를 돌리는 JVM과 같은 것을 쓴다(`ProcessHandle.current().info().command()`).

### 11. 설정과 로컬 실행

| 무엇 | 값 |
|---|---|
| feed-service 하위 서비스 주소 | `spring.http.serviceclient.<의존성>.base-url: ${<의존성>_BASE_URL:http://localhost:<포트>}` (`POST_BASE_URL`, `USER_BASE_URL`, `LIKE_BASE_URL`, `RECOMMENDATION_BASE_URL`, `AD_BASE_URL`) |
| like-service DB | `spring.datasource.url/username/password: ${LIKE_DB_URL}` 등. 비밀 값이라 `.env.example`에 이름과 설명만 적는다 |
| like-service 로컬 실행 | `./gradlew :like-service:bootTestRun`. 테스트 소스의 `TestLikeServiceApplication`이 `SpringApplication.from(...).with(...)`로 테스트의 MySQL 구성을 붙여 Testcontainers MySQL을 함께 띄운다. Docker Compose가 고도화 1에서 들어오기 전까지 MySQL을 따로 설치하지 않아도 된다 |

명령은 `CLAUDE.md`와 하네스 규칙 5장에 더한다. `e2e` 모듈은 `scripts/harness/stop-check.sh`의 모듈 목록과 README의 모듈 표에 더한다.

## Risks / Trade-offs

- [시간 기준 테스트가 CI에서 흔들릴 수 있다] → 지연을 400ms로 크게 잡아 순차와 병렬의 차이(800ms)를 넓혔다. 그래도 흔들리면 기준을 바꾸지 말고 원인(풀 스레드 부족 등)을 먼저 본다.
- [e2e가 느리다. JVM 6개를 띄우는 데 수십 초가 걸린다] → `integrationTest`에서만 돌고 Stop 훅에서는 돌지 않는다.
- [e2e와 like 테스트는 Docker가 있어야 돈다] → 로컬과 GitHub Actions의 ubuntu 러너 모두 Docker가 있다. 없으면 Testcontainers가 원인을 알려 주며 실패한다.
- [HTTP 서비스 그룹이 그룹마다 커넥션 풀을 따로 만들지 않을 수 있다] → 작업 3.1에서 그룹마다 HttpClient가 다른지 테스트로 확인한다. 같다면 그룹 설정자(`RestClientHttpServiceGroupConfigurer`)로 그룹마다 요청 팩토리를 따로 넣는다. 이 경우 구현 방식만 바뀌고 스펙은 그대로다.
- [용량 값의 입력이 계획값이다] → 고도화 2의 기준선(S-01)에서 실제 RPS와 지연으로 다시 계산하고, 다르면 그때 설정값 커밋으로 고친다.
- [토글의 동시성 문제가 e2e나 다른 테스트를 흔들 수 있다] → 이 change의 테스트는 좋아요를 한 사용자가 차례로 누른다. 동시 요청은 고도화 6의 몫이다.

## 장애 시나리오와 실험 세트

이 change는 장애 대응 동작을 넣지 않는다. 대신 S-01~S-10이 모두 이 상태를 기준선으로 잰다. 실험 세트는 고도화 2(`add-fault-experiments`)에서 만들므로 이 change에서는 돌리지 않는다. 기준선의 전제가 되는 기본값과 용량 계산은 결정 8에 따라 아키텍처 4-2에 남긴다.

## Open Questions

- 기본값 확인 결과가 결정 8 표의 예상과 다르면 계산 결과와 비교만 다시 한다. 정한 값(20, 100)은 계산에서 나오므로 바뀌지 않는다.
