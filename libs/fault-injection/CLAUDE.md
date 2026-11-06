# libs/fault-injection

- 하위 서비스 5개의 앱 수준 장애 주입(add-fault-experiments design 결정 2). 컨벤션 플러그인에 넣지 않고 하위 서비스의 `build.gradle.kts`에서만 의존성으로 더한다. feed-service에는 붙이지 않는다.
- 실제 웹 요청으로 확인할 때는 테스트 소스의 `com.weave.faultinjection.testapp.FaultInjectionTestApplication`(`GET /work`)을 `@SpringBootTest(classes = [...], webEnvironment = RANDOM_PORT)`로 띄운다. 테스트 클래스가 다른 패키지에 있어도 `classes`를 늘 적는다.
- 시나리오 테스트는 테스트 소스의 `FaultsHttpClient`(응답 시간 측정, `FaultSettings`, `percentile`)로 부른다. 장애 설정은 컨텍스트에 하나라 스펙 안에서 테스트마다 `beforeTest`에서 `DELETE /actuator/faults`로 되돌린다.
- 장애 설정의 main 클래스는 `FaultConfig`다(만들 때 `require`로 검증, 틀리면 `IllegalArgumentException`). 테스트 소스에 같은 패키지의 `FaultSettings`가 이미 있어 그 이름은 main에 쓰지 않는다. 지연은 `FaultConfig.latencyDistribution().sampleMs(random)`으로 뽑는다.
- `testContextManager()`는 suspend 함수라 `by lazy`에서 못 부른다. `beforeTest`나 테스트 안에서 포트를 읽는다.
- Gradle 9는 테스트 소스가 있는데 발견된 테스트가 없으면 `test`를 실패시킨다(`failOnNoDiscoveredTests`). `Integration` 태그 스펙만 있어도 발견은 되어 `test`가 통과한다.
