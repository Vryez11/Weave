# like-service

- 컨텍스트를 띄우는 테스트는 MySQL이 있어야 한다. 테스트 소스의 `MySqlTestConfiguration`(`@Configuration`, Testcontainers `mysql:9.7`, `@ServiceConnection`)이 컴포넌트 스캔으로 모든 `@SpringBootTest`에 붙으니 따로 `@Import`하지 않는다. Docker가 필요하다.
- 컨테이너는 JVM에 하나이고 컨텍스트가 닫혀도 멈추지 않는다. 테스트끼리 데이터가 남으므로 테스트마다 테이블을 비운다.
- 컨테이너를 `@Bean`(`@ServiceConnection`)으로 내놓지 않는다. Boot가 컨텍스트를 닫을 때 컨테이너 Bean을 멈춰, 다음 컨텍스트가 빈 DB로 새 컨테이너를 띄운다. 접속 정보만 `JdbcConnectionDetails` Bean으로 내놓는다.
- 테스트에서 애플리케이션을 직접 띄울 때 포트는 `run("--server.port=0")`으로 넘긴다. `SpringApplicationBuilder.properties(...)`는 `application.yml`에 덮인다.
