# e2e

- 서비스 6개를 bootJar 프로세스로 띄우는 종단 간 테스트. 띄우기·내리기는 테스트 소스의 `ServiceProcesses`가 맡는다.
- 서비스 코드와 JDBC 드라이버가 없다. 그래서 MySQL은 `MySQLContainer`가 아닌 `DriverlessMySqlContainer`(로그로 준비를 기다림)를 쓴다. 드라이버를 의존성에 더하지 않는다.
- 실패 원인은 `e2e/build/e2e-logs/<서비스>.log`에서 본다. 테스트 결과는 `e2e/build/test-results/integrationTest/`.
- 로그 검사는 테스트 소스의 `ServiceLogs`로 로그 파일을 줄마다 읽는다(`rawLines`, `jsonLines`, `requestLines(service, traceId)`). e2e는 서비스 모듈이 아니라 `LogCapture`가 없다.
- 스펙 클래스마다 `ServiceProcesses`를 따로 띄운다. 로그 파일은 띄울 때마다 새로 쓰이므로, 요청의 줄은 응답의 `X-Trace-Id`로 찾는다.
- `Compose` 태그 테스트(`TraceLookupComposeScenarioTest`)는 Compose 환경을 띄우지 않고 시스템 속성 `weave.compose.feed-url`, `weave.compose.loki-url`, `weave.compose.tempo-url`로 주소만 받는다. `integrationTest` 리포트에는 skipped로 나온다(Kotest가 포함 태그만 있는 식에서는 스펙을 만들고 테스트마다 건너뛴다).
- 서비스 jar는 `integrationTest`가 bootJar를 다시 만들어 받는다. 서비스 코드를 고친 뒤 따로 빌드할 필요 없다.
