# Spec Delta

## ADDED Requirements

### Requirement: 하위 서비스 호출 실패 로그 (F-004)

feed-service는 하위 서비스 호출이 실패할 때마다 로그를 한 줄 남겨야 한다(SHALL). 필드는 `event`(`downstream_call_failed`), `dependency`(`post`, `user`, `like`, `recommendation`, `ad` 중 하나), `cause`, `exception`(예외 클래스 이름)이고, 피드 요청의 `traceId`가 함께 있다. `cause`는 HTTP 상태가 있으면 `http_<상태 코드>`, 연결하지 못했으면(연결 거부, 호스트 이름을 찾지 못함, 경로 없음, 연결 시간 초과) `connect_failure`, 그 밖의 입출력 실패는 `io_failure`, 나머지는 `other`다. 예외 메시지와 스택 트레이스는 남기지 않는다. 클라이언트에 주는 응답은 바뀌지 않는다.

#### Scenario: 하위 서비스가 5xx

- **GIVEN** recommendation-service가 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 응답은 500이다
- **AND** feed-service가 `event` `downstream_call_failed`, `dependency` `recommendation`, `cause` `http_503`인 줄을 하나 남긴다
- **AND** 그 줄의 `traceId`가 응답의 `X-Trace-Id`와 같다

#### Scenario: 하위 서비스에 연결할 수 없다

- **GIVEN** recommendation 주소가 아무도 듣지 않는 포트다
- **WHEN** `GET /feed`를 부른다
- **THEN** feed-service가 `dependency` `recommendation`, `cause` `connect_failure`인 `downstream_call_failed` 줄을 남긴다

#### Scenario: 하위 서비스의 호스트 이름을 찾을 수 없다

- **GIVEN** recommendation 주소의 호스트가 `weave-unresolvable.invalid`다
- **WHEN** `GET /feed`를 부른다
- **THEN** feed-service가 `dependency` `recommendation`, `cause` `connect_failure`인 `downstream_call_failed` 줄을 남긴다

#### Scenario: post 뒤에 부른 호출이 실패한다

- **GIVEN** like-service가 500을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** `dependency` `like`, `cause` `http_500`인 `downstream_call_failed` 줄의 `traceId`가 응답의 `X-Trace-Id`와 같다

#### Scenario: 토글 전달이 실패한다

- **GIVEN** like-service가 토글에 503을 준다
- **WHEN** `POST /feed/likes/{postId}/toggle`을 부른다
- **THEN** feed-service가 `dependency` `like`, `cause` `http_503`인 `downstream_call_failed` 줄을 남긴다
