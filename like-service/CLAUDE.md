# like-service

- 컨텍스트를 띄우는 테스트는 MySQL이 있어야 한다. 테스트 소스의 `MySqlTestConfiguration`(`@Configuration`, Testcontainers `mysql:9.7`, `@ServiceConnection`)이 컴포넌트 스캔으로 모든 `@SpringBootTest`에 붙으니 따로 `@Import`하지 않는다. Docker가 필요하다.
- 컨테이너는 JVM에 하나이고 컨텍스트가 닫혀도 멈추지 않는다. 테스트끼리 데이터가 남으므로 테스트마다 테이블을 비운다.
