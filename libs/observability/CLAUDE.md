# libs/observability

- 모든 서비스에 자동 적용되는 로그·트레이싱 설정. 서비스는 이 설정을 따로 두지 않는다.
- 테스트에서 로그를 검사할 때는 test fixtures의 `com.weave.observability.testing.LogCapture`를 쓴다. `weave.spring-service`가 서비스 테스트에 넣어 준다. 비동기 appender라 검사는 `eventually`로 기다린다.
- 실제 웹 요청으로 라이브러리 설정을 확인할 때는 테스트 소스의 `com.weave.observability.testapp.LoggingTestApplication`을 `@SpringBootTest(classes = [...], webEnvironment = RANDOM_PORT)`로 띄운다. 테스트 클래스가 다른 패키지에 있어도 같은 앱을 띄우도록 `classes`를 늘 적는다.
- 로그 형식은 `src/main/resources/logback-spring.xml` 하나가 정한다. MDC는 `includeMdcKeyName`에 적은 키(`traceId`, `spanId`)만 나간다. 이벤트 필드는 MDC가 아니라 SLF4J key-value로 넣는다.
- `@timestamp`는 `timestampPattern`으로 밀리초까지 고정했다. 인코더 기본값은 나노초까지 찍혀 자릿수가 바뀐다.
- 라이브러리 기본 속성은 `src/main/resources/observability-defaults.properties`에 더한다. `ObservabilityDefaultsPostProcessor`(`META-INF/spring.factories`에 등록)가 속성 소스 뒤쪽에 붙여 서비스 설정과 환경 변수가 이긴다. 속성 소스 맨 끝에는 Boot가 나중에 `applicationInfo`를 붙이므로 "맨 마지막"으로 단언하지 않는다.
- 서비스 `application.yml`에 `management.endpoints.web.exposure.include`를 두지 않는다. 두면 기본값(`health,info,prometheus`)을 덮어 `prometheus`가 닫힌다.
- `@SpringBootTest`는 메트릭 내보내기를 꺼서 `/actuator/prometheus`가 404다. 엔드포인트를 보는 테스트에는 `@AutoConfigureMetrics`(`org.springframework.boot.micrometer.metrics.test.autoconfigure`)를 붙인다.
- 기본 속성·Bean 조건은 `ObservabilityDefaultsTest`처럼 `SpringApplicationBuilder(LoggingTestApplication).web(NONE)`으로 컨텍스트를 띄워 본다. 서비스 `application.yml` 역할은 테스트 리소스의 프로필 파일(`application-<프로필>.yml`)이 한다.
- 서블릿 쪽 Bean(`TraceLoggingFilter`)은 `ObservabilityAutoConfiguration.ServletConfiguration`(`@ConditionalOnWebApplication(SERVLET)`)에 둔다. `spring-webmvc`·`jakarta.servlet-api`는 `compileOnly`라 웹이 아닌 컨텍스트에서 이 클래스들을 건드리지 않게 한다.
- Filter는 `FilterRegistrationBean` 없이 `Ordered`를 단 `OncePerRequestFilter` Bean으로 둔다. Boot가 order대로 등록하고 오류 디스패치에도 건다. Tomcat의 `/error` 디스패치에서 `doFilterInternal`이 다시 불릴 수 있어 `dispatcherType`으로 가른다(중첩 오류 디스패치는 `doFilterNestedErrorDispatch`).
- 스레드 경계의 traceId 전파를 Spring 없이 단위 테스트할 때는 `ExecutorTracePropagationTest`처럼 Boot가 하는 연결을 직접 짠다: `ContextStorage.addWrapper(EventPublishingContextWrapper(...))`(OTel 컨텍스트를 처음 쓰기 전에), `Slf4JEventListener`로 MDC, `DefaultTracingObservationHandler`, `ObservationThreadLocalAccessor`에 레지스트리 지정. `ContextExecutorService.wrap(executor)` 한 인자 형태는 deprecated라 `ContextSnapshotFactory`를 함께 넘긴다.
- 처리되지 않은 예외는 `TraceLoggingFilter`가 `http_request` 줄을 ERROR로 올려 예외와 함께 남긴다. Tomcat의 `...[dispatcherServlet]` 로거 줄은 traceId가 없어 `TomcatServletExceptionLogFilter`(`ASYNC` appender의 `<filter>`)가 막는다. 엔진 이름이 `Tomcat-N`으로 바뀌어 `<logger>`로는 못 막는다. 요청 완료 로그를 건너뛰는 경로(actuator)를 더하면 예외는 따로 남겨야 한다(`unhandled_exception` 줄).
- logback 클래스(`Filter`, `ILoggingEvent`)는 `compileOnly("ch.qos.logback:logback-classic")`로 컴파일한다. 서비스는 Boot 기본 로깅으로 이미 가진다.
- test fixtures는 `implementation`의 BOM을 물려받지 않는다. 의존성을 더할 때 `testFixturesImplementation(platform(...))`이 이미 있는지 본다.
