# libs/observability

- 모든 서비스에 자동 적용되는 로그·트레이싱 설정. 서비스는 이 설정을 따로 두지 않는다.
- 테스트에서 로그를 검사할 때는 test fixtures의 `com.weave.observability.testing.LogCapture`를 쓴다. `weave.spring-service`가 서비스 테스트에 넣어 준다. 비동기 appender라 검사는 `eventually`로 기다린다.
- test fixtures는 `implementation`의 BOM을 물려받지 않는다. 의존성을 더할 때 `testFixturesImplementation(platform(...))`이 이미 있는지 본다.
