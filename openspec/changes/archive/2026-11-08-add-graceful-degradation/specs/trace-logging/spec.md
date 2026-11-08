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

- **GIVEN** like-service가 토글에 503을 준다. 피드 조회의 하위 서비스 실패는 고도화 4부터 등급대로 대체돼(`graceful-degradation`) 처리되지 않은 예외가 되지 않는다
- **WHEN** `POST /feed/likes/{postId}/toggle`이 500으로 응답한다
- **THEN** feed-service가 그 요청을 처리하며 쓴 ERROR 줄은 모두 `traceId`가 응답의 `X-Trace-Id`와 같다
- **AND** 그중 한 줄의 `stack_trace`에 하위 서비스 호출 실패 예외의 클래스 이름이 있다

### Requirement: 응답 헤더의 traceId (F-004)

모든 서비스는 HTTP 응답마다 `X-Trace-Id` 헤더에 그 요청의 traceId를 담아야 한다(SHALL). 오류 응답(4xx, 5xx)도 같다. 헤더 값은 그 요청을 처리하며 쓴 로그의 `traceId`와 같다.

#### Scenario: 정상 응답

- **WHEN** `GET /feed`가 200으로 응답한다
- **THEN** `X-Trace-Id` 헤더가 16진수 32자다
- **AND** feed-service가 그 요청을 처리하며 쓴 로그의 `traceId`가 헤더 값과 같다

#### Scenario: 오류 응답

- **WHEN** `GET /feed`가 요청 검증 실패로 400으로, 게시글을 대체할 수 없어 503으로 응답한다
- **THEN** 두 응답 모두 `X-Trace-Id` 헤더가 있다

### Requirement: 요청 완료 로그 (F-004)

모든 서비스는 HTTP 요청 하나를 마칠 때마다 로그를 한 줄 남겨야 한다(SHALL). 필드는 `event`(`http_request`), `method`, `path`(쿼리 문자열 제외), `status`, `durationMs`이고, JSON 로그 형식의 `traceId`가 함께 있다. `/actuator`와 `/actuator/`로 시작하는 경로는 남기지 않는다.

#### Scenario: 요청 하나에 한 줄

- **WHEN** `GET /posts?size=3`이 200으로 응답한다
- **THEN** post-service가 `event`가 `http_request`인 줄을 하나 남긴다
- **AND** 그 줄의 `method`는 `GET`, `path`는 `/posts`, `status`는 200이고, `durationMs`는 0 이상의 정수다

#### Scenario: 실패한 요청

- **WHEN** `GET /feed`가 503으로 응답한다
- **THEN** feed-service의 `http_request` 줄에 `status` 503과 응답의 `X-Trace-Id`와 같은 `traceId`가 있다

#### Scenario: 헬스 체크

- **WHEN** `GET /actuator/health`를 부른다
- **THEN** `http_request` 줄을 남기지 않는다
