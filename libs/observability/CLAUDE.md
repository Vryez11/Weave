# libs/observability

- 모든 서비스에 자동 적용되는 로그·트레이싱 설정. 서비스는 이 설정을 따로 두지 않는다.
- 테스트에서 로그를 검사할 때는 test fixtures의 `com.weave.observability.testing.LogCapture`를 쓴다. `weave.spring-service`가 서비스 테스트에 넣어 준다. 비동기 appender라 검사는 `eventually`로 기다린다.
- 실제 웹 요청으로 라이브러리 설정을 확인할 때는 테스트 소스의 `com.weave.observability.testapp.LoggingTestApplication`을 `@SpringBootTest(classes = [...], webEnvironment = RANDOM_PORT)`로 띄운다. 테스트 클래스가 다른 패키지에 있어도 같은 앱을 띄우도록 `classes`를 늘 적는다.
- 로그 형식은 `src/main/resources/logback-spring.xml` 하나가 정한다. MDC는 `includeMdcKeyName`에 적은 키(`traceId`, `spanId`)만 나간다. 이벤트 필드는 MDC가 아니라 SLF4J key-value로 넣는다.
- `@timestamp`는 `timestampPattern`으로 밀리초까지 고정했다. 인코더 기본값은 나노초까지 찍혀 자릿수가 바뀐다.
- 라이브러리 기본 속성은 `src/main/resources/observability-defaults.properties`에 더한다. `ObservabilityDefaultsPostProcessor`(`META-INF/spring.factories`에 등록)가 속성 소스 뒤쪽에 붙여 서비스 설정과 환경 변수가 이긴다. 속성 소스 맨 끝에는 Boot가 나중에 `applicationInfo`를 붙이므로 "맨 마지막"으로 단언하지 않는다.
- 기본 속성·Bean 조건은 `ObservabilityDefaultsTest`처럼 `SpringApplicationBuilder(LoggingTestApplication).web(NONE)`으로 컨텍스트를 띄워 본다. 서비스 `application.yml` 역할은 테스트 리소스의 프로필 파일(`application-<프로필>.yml`)이 한다.
- test fixtures는 `implementation`의 BOM을 물려받지 않는다. 의존성을 더할 때 `testFixturesImplementation(platform(...))`이 이미 있는지 본다.
