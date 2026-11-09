# trace-logging Specification

## Purpose

서비스 6개가 같은 형식의 JSON 로그와 traceId를 남겨, 피드 요청 하나가 거친 모든 서비스의 로그와 트레이스를 traceId 하나로 모아 볼 수 있게 한다. 무엇을 왜 하는지는 [PRD](../../../docs/prd.md) F-004를 따른다.

## Requirements

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

### Requirement: 응답 헤더의 traceId (F-004)

모든 서비스는 HTTP 응답마다 `X-Trace-Id` 헤더에 그 요청의 traceId를 담아야 한다(SHALL). 오류 응답(4xx, 5xx)도 같다. 헤더 값은 그 요청을 처리하며 쓴 로그의 `traceId`와 같다.

#### Scenario: 정상 응답

- **WHEN** `GET /feed`가 200으로 응답한다
- **THEN** `X-Trace-Id` 헤더가 16진수 32자다
- **AND** feed-service가 그 요청을 처리하며 쓴 로그의 `traceId`가 헤더 값과 같다

#### Scenario: 오류 응답

- **WHEN** `GET /feed`가 요청 검증 실패로 400으로, 게시글을 대체할 수 없어 503으로 응답한다
- **THEN** 두 응답 모두 `X-Trace-Id` 헤더가 있다

### Requirement: 들어온 trace를 잇는다 (F-004)

요청에 W3C `traceparent` 헤더가 있으면, 서비스는 그 traceId를 이어 써야 한다(SHALL). 없으면 새 traceId를 만든다.

#### Scenario: traceparent를 받는다

- **GIVEN** 요청 헤더 `traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`
- **WHEN** `GET /feed`를 부른다
- **THEN** `X-Trace-Id`가 `4bf92f3577b34da6a3ce929d0e0e4736`이다

#### Scenario: traceparent가 없다

- **WHEN** `traceparent` 없이 `GET /feed`를 두 번 부른다
- **THEN** 두 응답의 `X-Trace-Id`가 서로 다르다

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

### Requirement: 로그에 남기지 않는 것 (F-004)

서비스는 요청·응답 본문, 쿼리 문자열, 요청 헤더 값(`X-User-Id` 포함)을 로그에 남기지 않아야 한다(SHALL). 하위 서비스의 오류 응답 본문도 남기지 않는다.

#### Scenario: 요청의 값이 로그에 없다

- **GIVEN** 테스트마다 새로 만든 표식 문자열 `m`과 9자리 표식 숫자 `n`이 있다
- **WHEN** `X-User-Id: n`, `X-Marker: m` 헤더와 쿼리 `marker=m`을 담아 `GET /feed`를 부른다
- **THEN** 그 요청을 처리하며 서비스가 쓴 어떤 로그 줄에도 `m`과 `n`이 없다

#### Scenario: 하위 서비스 오류 본문이 로그에 없다

- **GIVEN** recommendation-service가 503과 표식 문자열을 담은 본문을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** feed-service의 어떤 로그 줄에도 표식이 없다

### Requirement: 서비스 6개의 로그를 traceId로 잇는다 (F-004)

피드 요청 하나를 처리하는 동안 서비스 6개가 쓴 로그는 모두 같은 `traceId`를 가져야 한다(SHALL).

#### Scenario: 실제 프로세스로 띄운 서비스 6개

- **GIVEN** 서비스 6개를 실제 프로세스로 띄웠다
- **WHEN** `GET /feed`를 한 번 부른다
- **THEN** 서비스 6개의 로그에 응답의 `X-Trace-Id`와 같은 `traceId`를 가진 `http_request` 줄이 서비스마다 하나 이상 있다

### Requirement: Loki에서 traceId로 로그 조회 (F-004)

Docker Compose로 띄운 환경에서, 서비스 6개의 로그는 Loki로 모여 traceId 하나로 조회되어야 한다(SHALL). 조회 결과는 로그의 `@timestamp` 순서이고, 항목마다 서비스 이름을 알 수 있다.

#### Scenario: 피드 요청 하나의 로그

- **GIVEN** `docker compose`로 서비스 6개와 관찰 인프라를 띄웠다
- **WHEN** `GET /feed`를 부르고, 응답의 `X-Trace-Id`로 Loki를 조회한다
- **THEN** 서비스 6개 모두의 로그가 나온다
- **AND** 결과는 `@timestamp`가 줄지 않는 순서다

### Requirement: Tempo에서 traceId로 트레이스 조회 (F-004)

Docker Compose로 띄운 환경에서, 피드 요청 하나의 스팬은 Tempo에 모여 traceId 하나로 조회되어야 한다(SHALL).

#### Scenario: 피드 요청 하나의 트레이스

- **GIVEN** `docker compose`로 서비스 6개와 관찰 인프라를 띄웠다
- **WHEN** `GET /feed`를 부르고, 응답의 `X-Trace-Id`로 Tempo를 조회한다
- **THEN** 서비스 6개 모두의 스팬이 한 트레이스에 나온다

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
