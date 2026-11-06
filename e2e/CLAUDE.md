# e2e

- 서비스 6개를 bootJar 프로세스로 띄우는 종단 간 테스트. 띄우기·내리기는 테스트 소스의 `ServiceProcesses`가 맡는다.
- 서비스 코드와 JDBC 드라이버가 없다. 그래서 MySQL은 `MySQLContainer`가 아닌 `DriverlessMySqlContainer`(로그로 준비를 기다림)를 쓴다. 드라이버를 의존성에 더하지 않는다.
- 실패 원인은 `e2e/build/e2e-logs/<서비스>.log`에서 본다. 테스트 결과는 `e2e/build/test-results/integrationTest/`.
- 로그 검사는 테스트 소스의 `ServiceLogs`로 로그 파일을 줄마다 읽는다(`rawLines`, `jsonLines`, `requestLines(service, traceId)`). e2e는 서비스 모듈이 아니라 `LogCapture`가 없다.
- 스펙 클래스마다 `ServiceProcesses`를 따로 띄운다. 로그 파일은 띄울 때마다 새로 쓰이므로, 요청의 줄은 응답의 `X-Trace-Id`로 찾는다.
- `Compose` 태그 테스트(`TraceLookupComposeScenarioTest`)는 Compose 환경을 띄우지 않고 시스템 속성 `weave.compose.feed-url`, `weave.compose.loki-url`, `weave.compose.tempo-url`로 주소만 받는다. `integrationTest` 리포트에는 skipped로 나온다(Kotest가 포함 태그만 있는 식에서는 스펙을 만들고 테스트마다 건너뛴다).
- `./gradlew :e2e:composeTest`는 `composeTestEnv`(임의 MySQL 계정을 `build/compose-test.env`에) → `composeTestUp`(먼저 `down -v --remove-orphans`로 끊긴 이전 실행을 치우고(출력은 `build/compose-logs/pre-down.log`, 실패하면 오류 메시지에도 실린다) `-p weave-compose-test up -d --build --wait`) → Compose 태그 테스트 → `composeTestLogs`(`docker compose logs`를 `build/compose-logs/compose.log`에) → `composeTestDown`(`down -v`, finalizer라 실패해도 돈다) 순서다. down 뒤에는 컨테이너가 없으니 실패 원인은 `compose.log`에서 본다(CI artifact에도 들어간다). 호스트 포트가 `compose.yaml`에 고정이라 손으로 띄운 로컬 환경이 있으면 먼저 내린다. 남은 컨테이너는 `docker compose -p weave-compose-test ps -a`로 본다.
- 실험 세트(`experiments/`) 테스트는 테스트 소스의 `ExperimentScripts`를 쓴다. 폴더 위치는 모든 `Test` 태스크에 넘기는 시스템 속성 `weave.experiments.dir`로 받고(`experiments/`가 태스크 입력이라 스크립트를 고치면 다시 돈다), 스크립트는 실행 파일로 직접 돌린다(`run("lib/verdict.sh", ...)`). 픽스처는 `src/test/resources/experiments/`에 있고, 스크립트의 인자·출력 형식은 각 테스트 KDoc이 정한다.
- e2e `build.gradle.kts`에서 태스크는 `tasks.register`로 만든다. Gradle 9.6부터 `by tasks.registering` 위임은 deprecated이고 스크립트 컴파일 오류가 된다.
- 서비스 jar는 `integrationTest`가 bootJar를 다시 만들어 받는다. 서비스 코드를 고친 뒤 따로 빌드할 필요 없다.
