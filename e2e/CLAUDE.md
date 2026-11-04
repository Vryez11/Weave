# e2e

- 서비스 6개를 bootJar 프로세스로 띄우는 종단 간 테스트. 띄우기·내리기는 테스트 소스의 `ServiceProcesses`가 맡는다.
- 서비스 코드와 JDBC 드라이버가 없다. 그래서 MySQL은 `MySQLContainer`가 아닌 `DriverlessMySqlContainer`(로그로 준비를 기다림)를 쓴다. 드라이버를 의존성에 더하지 않는다.
- 실패 원인은 `e2e/build/e2e-logs/<서비스>.log`에서 본다. 테스트 결과는 `e2e/build/test-results/integrationTest/`.
- 서비스 jar는 `integrationTest`가 bootJar를 다시 만들어 받는다. 서비스 코드를 고친 뒤 따로 빌드할 필요 없다.
