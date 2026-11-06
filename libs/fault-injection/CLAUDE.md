# libs/fault-injection

- 하위 서비스 5개의 앱 수준 장애 주입(add-fault-experiments design 결정 2). 컨벤션 플러그인에 넣지 않고 하위 서비스의 `build.gradle.kts`에서만 의존성으로 더한다. feed-service에는 붙이지 않는다.
- 실제 웹 요청으로 확인할 때는 테스트 소스의 `com.weave.faultinjection.testapp.FaultInjectionTestApplication`(`GET /work`)을 `@SpringBootTest(classes = [...], webEnvironment = RANDOM_PORT)`로 띄운다. 테스트 클래스가 다른 패키지에 있어도 `classes`를 늘 적는다.
- Gradle 9는 테스트 소스가 있는데 발견된 테스트가 없으면 `test`를 실패시킨다(`failOnNoDiscoveredTests`). `Integration` 태그 스펙만 있어도 발견은 되어 `test`가 통과한다.
