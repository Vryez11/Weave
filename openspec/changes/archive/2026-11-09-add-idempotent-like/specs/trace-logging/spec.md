# Spec Delta

## ADDED Requirements

### Requirement: 하위 서비스 호출로 traceId를 전파한다 (F-004)

feed-service는 하위 서비스를 부를 때마다 W3C `traceparent` 헤더에 피드 요청의 traceId를 실어 보내야 한다(SHALL). 호출이 요청 스레드가 아닌 다른 스레드에서 시작해도 같다.

#### Scenario: 피드 요청의 다섯 호출

- **WHEN** `GET /feed`를 부른다
- **THEN** post, recommendation, ad, user, like로 간 요청 다섯 개 모두 `traceparent`가 있다
- **AND** 다섯 요청의 traceId가 모두 응답의 `X-Trace-Id`와 같다

#### Scenario: post가 끝난 뒤 시작한 호출

- **GIVEN** post-service가 200ms 늦게 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** post 응답을 받은 뒤 시작한 user, like 요청의 traceId가 응답의 `X-Trace-Id`와 같다

#### Scenario: 좋아요 상태 변경

- **WHEN** `PUT /feed/likes/{postId}`를 부른다
- **THEN** like-service로 간 상태 변경 요청의 traceId가 응답의 `X-Trace-Id`와 같다

## MODIFIED Requirements

### Requirement: JSON 로그 형식 (F-004)

모든 서비스는 로그 한 건을 표준 출력에 JSON 객체 한 줄로 써야 한다(SHALL).

- 늘 있는 필드: `@timestamp`(ISO-8601, 밀리초까지), `level`, `service`(서비스 이름), `logger`, `thread`, `message`
- HTTP 요청을 처리하는 중에 쓴 로그에는 `traceId`(16진수 32자)와 `spanId`(16진수 16자)가 있다. 요청 밖(시작, 종료)의 로그에는 없어도 된다.
- 예외가 있으면 `stack_trace`에 스택 트레이스를 담는다.
- 처리되지 않은 예외로 끝난 요청에서 그 예외를 남기는 로그도 그 요청의 `traceId`를 가진다.

#### Scenario: 요청 처리 중의 로그

- **WHEN** 어느 서비스든 HTTP 요청을 처리하며 로그를 쓴다
- **THEN** 표준 출력의 그 줄은 JSON 객체 하나로 파싱된다
- **AND** `@timestamp`, `level`, `service`, `logger`, `thread`, `message`, `traceId`, `spanId`가 있고, `service`는 그 서비스의 이름(예: `post-service`)이다

#### Scenario: 예외가 있는 로그

- **WHEN** 예외를 담아 로그를 쓴다
- **THEN** 그 줄의 `stack_trace`에 예외 클래스 이름이 들어 있다
- **AND** 줄바꿈이 있는 스택 트레이스여도 로그는 한 줄이다

#### Scenario: 처리되지 않은 예외로 끝난 요청

- **GIVEN** like-service가 상태 변경에 503을 준다. 피드 조회의 하위 서비스 실패는 고도화 4부터 등급대로 대체돼(`graceful-degradation`) 처리되지 않은 예외가 되지 않는다
- **WHEN** `PUT /feed/likes/{postId}`가 500으로 응답한다
- **THEN** feed-service가 그 요청을 처리하며 쓴 ERROR 줄은 모두 `traceId`가 응답의 `X-Trace-Id`와 같다
- **AND** 그중 한 줄의 `stack_trace`에 하위 서비스 호출 실패 예외의 클래스 이름이 있다

## REMOVED Requirements

### Requirement: 하위 서비스 호출로 전파 (F-004)

**Reason**: 시나리오 하나가 없어지는 토글을 가리켜 시나리오 이름까지 바꾼다. 내용은 같고 토글 시나리오만 상태 변경으로 바뀐다.
**Migration**: "하위 서비스 호출로 traceId를 전파한다"를 따른다.
