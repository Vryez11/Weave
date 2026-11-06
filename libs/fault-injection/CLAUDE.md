# libs/fault-injection

- 하위 서비스 5개의 앱 수준 장애 주입(add-fault-experiments design 결정 2). 컨벤션 플러그인에 넣지 않고 하위 서비스의 `build.gradle.kts`에서만 의존성으로 더한다. feed-service에는 붙이지 않는다.
- 실제 웹 요청으로 확인할 때는 테스트 소스의 `com.weave.faultinjection.testapp.FaultInjectionTestApplication`(`GET /work`)을 `@SpringBootTest(classes = [...], webEnvironment = RANDOM_PORT)`로 띄운다. 테스트 클래스가 다른 패키지에 있어도 `classes`를 늘 적는다.
- 시나리오 테스트는 테스트 소스의 `FaultsHttpClient`(응답 시간 측정, `FaultSettings`, `percentile`)로 부른다. 장애 설정은 컨텍스트에 하나라 스펙 안에서 테스트마다 `beforeTest`에서 `DELETE /actuator/faults`로 되돌린다.
- 장애 설정의 main 클래스는 `FaultConfig`다(만들 때 `require`로 검증, 틀리면 `IllegalArgumentException`). 테스트 소스에 같은 패키지의 `FaultSettings`가 이미 있어 그 이름은 main에 쓰지 않는다. 지연은 `FaultConfig.latencyDistribution().sampleMs(random)`으로 뽑는다.
- 켜는 스위치는 `weave.fault-injection.enabled` 하나다(`FaultInjectionAutoConfiguration`). 기본값은 `FaultInjectionProperties`에, 실행 중 값은 `FaultState`에 있다. 필터 순서는 `FaultInjectionFilter.ORDER`(`HIGHEST_PRECEDENCE + 10`)이고, 이 필터보다 먼저 걸러야 하는 필터(ad 요청 한도)는 `ORDER - 1`처럼 `TraceLoggingFilter`(`+2`)와 이 값 사이에 둔다.
- Boot 4 actuator에는 `@OptionalParameter`가 없다. `@WriteOperation`의 선택 인자는 Kotlin 널 가능 타입(`Double?`)만으로 선택이 된다. JSpecify `@Nullable`은 TYPE_USE라 인자에 달 수 없다.
- 테스트 클래스패스에 `libs/observability`(와 testFixtures `LogCapture`)가 있어, 테스트 앱이 서비스와 같은 추적·요청 로그 필터와 JSON 로그로 뜬다.
- `testContextManager()`는 suspend 함수라 `by lazy`에서 못 부른다. `beforeTest`나 테스트 안에서 포트를 읽는다.
- Gradle 9는 테스트 소스가 있는데 발견된 테스트가 없으면 `test`를 실패시킨다(`failOnNoDiscoveredTests`). `Integration` 태그 스펙만 있어도 발견은 되어 `test`가 통과한다.
