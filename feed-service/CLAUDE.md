# feed-service

- 하위 서비스 호출은 `com.weave.feed.gateway`의 Gateway Bean을 거친다. 조회 메서드는 `.logFailure("<의존성>")`, 동기 호출은 `try`/`catch`에서 `DownstreamFailure.log(...)` 뒤 다시 던진다. 새 호출 경로도 같은 실패 로그를 남겨야 한다.
- 실패 원인의 새 값(F-001의 타임아웃, 서킷 열림, 벌크헤드 거절 등)은 `DownstreamFailure.of`에 더한다.
- 하위 서비스 오류 응답의 예외 메시지에는 본문이 없다(`HttpClientConfig.bodylessErrorMessages`). 본문이 필요하면 `getResponseBodyAsString()`으로 읽고, 로그에는 남기지 않는다. 예외 객체를 로거에 넘기지 않는다.
