# like-service

- 컨텍스트를 띄우는 테스트는 MySQL이 있어야 한다. 테스트 소스의 `MySqlTestConfiguration`(`@Configuration`, Testcontainers `mysql:9.7`, `JdbcConnectionDetails`)이 컴포넌트 스캔으로 모든 `@SpringBootTest`에 붙으니 따로 `@Import`하지 않는다. Docker가 필요하다.
- 컨테이너는 JVM에 하나이고 컨텍스트가 닫혀도 멈추지 않는다. 테스트끼리 데이터가 남으므로 테스트마다 테이블을 비운다.
- 컨테이너를 `@Bean`(`@ServiceConnection`)으로 내놓지 않는다. Boot가 컨텍스트를 닫을 때 컨테이너 Bean을 멈춰, 다음 컨텍스트가 빈 DB로 새 컨테이너를 띄운다. 접속 정보만 `JdbcConnectionDetails` Bean으로 내놓는다.
- 로컬 실행 진입점은 테스트 소스의 `TestLikeServiceApplication`(object, `@JvmStatic main`)이다. `LikeServiceApplication.kt`의 최상위 `main`과 같은 패키지라 최상위 `main`을 또 두면 충돌한다. 테스트에서는 `TestLikeServiceApplication.start("--server.port=0")`로 같은 방식으로 띄운다.
- 테스트에서 애플리케이션을 직접 띄울 때 포트는 `run("--server.port=0")`으로 넘긴다. `SpringApplicationBuilder.properties(...)`는 `application.yml`에 덮인다.
- 좋아요 쓰기는 `LikeService.setLike` 한 곳이다. READ COMMITTED에서 `lockOrCreate`(`ON DUPLICATE KEY UPDATE`)로 행 잠금을 먼저 잡고 판단한다(`INSERT IGNORE`는 중복 시 공유 잠금이라 교착한다). 잠금 순서는 좋아요 행 → 카운터 행이다. 판단 자체는 순수 함수 `LikeIntent.decide`다.
- 취소해도 `post_like` 행을 지우지 않는다(`liked = false`, 늦게 온 요청을 가려내는 기록). 행 수를 셀 때는 `liked = TRUE` 조건을 붙인다.
- 본문의 `liked`, `version`은 nullable로 받아 컨트롤러에서 400으로 돌린다. Kotlin의 non-null `Boolean`·`Long`은 필드가 빠지면 false·0으로 채워질 수 있다.
- 시나리오 테스트의 공용 도구는 `LikeHttp.kt`(`likeHttp()`, `clearLikes()`, `likedRows()`, `concurrently`)다. 실제 HTTP 포트로 부르고 구현 코드를 참조하지 않는다. 불일치 검사를 보는 테스트는 `management.endpoints.web.exposure.include`에 `likeconsistency`를 준다.
