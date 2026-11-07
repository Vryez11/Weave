# Spec Delta

## MODIFIED Requirements

### Requirement: 하위 서비스 호출 순서 (F-006)

feed-service는 post, recommendation, ad를 서로 기다리지 않고 동시에 불러야 한다(SHALL). user와 like는 post 응답을 받은 뒤 서로 기다리지 않고 동시에 불러야 한다(SHALL).

- user에는 게시글 작성자 ID를 중복 없이 넘긴다.
- like에는 게시글 ID와 요청한 사용자의 `X-User-Id`를 넘긴다.

#### Scenario: post 결과로 user와 like를 부른다

- **GIVEN** post-service가 게시글 10, 11, 12(작성자 3, 3, 4)를 준다
- **WHEN** `X-User-Id: 1`로 `GET /feed`를 부른다
- **THEN** user-service에 작성자 ID 3, 4로 한 번 요청한다
- **AND** like-service에 게시글 ID 10, 11, 12와 `X-User-Id: 1`로 한 번 요청한다

#### Scenario: 서로 기다리지 않는다

- **GIVEN** 의존성마다의 호출 시간 상한이 1초, 요청 시간 예산이 2초이고(`call-isolation`), post, recommendation, ad, user, like가 각각 400ms 늦게 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** 피드가 1,200ms 안에 200으로 온다. 모두 차례로 불렀다면 2,000ms가 걸린다

### Requirement: 하위 서비스 호출 실패 로그 (F-004)

feed-service는 하위 서비스 호출이 실패할 때마다 로그를 한 줄 남겨야 한다(SHALL). 필드는 `event`(`downstream_call_failed`), `dependency`(`post`, `user`, `like`, `recommendation`, `ad` 중 하나), `cause`, `exception`(예외 클래스 이름)이고, 피드 요청의 `traceId`가 함께 있다. `cause`는 HTTP 상태가 있으면 `http_<상태 코드>`, 연결하지 못했으면(연결 거부, 호스트 이름을 찾지 못함, 경로 없음, 연결 시간 초과) `connect_failure`, 호출 시간 상한이나 응답 타임아웃을 넘겼으면 `timeout`, 서킷이 열려 부르지 않았으면 `circuit_open`, 벌크헤드가 가득 차 부르지 않았으면 `bulkhead_full`, 요청 시간 예산이 다 돼 기다리지 않았으면 `budget_exhausted`, 그 밖의 입출력 실패는 `io_failure`, 나머지는 `other`다. 예외 메시지와 스택 트레이스는 남기지 않는다. 클라이언트에 주는 응답은 바뀌지 않는다.

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

#### Scenario: 호출 시간 상한을 넘긴다

- **GIVEN** recommendation의 호출 시간 상한이 200ms이고 recommendation-service가 1초 늦게 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** `dependency` `recommendation`, `cause` `timeout`인 `downstream_call_failed` 줄이 하나 있다. 시간 상한이 지난 뒤 응답 타임아웃으로 끝난 같은 호출의 줄을 하나 더 남기지 않는다

## REMOVED Requirements

### Requirement: 보호 장치 없는 실패 전파 (F-006)

**Reason**: 고도화 3(F-001)에서 하위 서비스 호출에 시간 상한, 요청 시간 예산, 벌크헤드, 서킷 브레이커가 생겨 "직접 정한 타임아웃·서킷 브레이커·벌크헤드를 두지 않는다"와 "느리면 피드도 그만큼 기다린다"가 더는 맞지 않는다. 재시도·fallback이 없고 실패가 500이라는 부분은 아래 "하위 서비스 실패는 시간 안에 피드 실패로 끝난다"로 옮긴다.
**Migration**: 격리 동작은 `call-isolation`, 실패 응답은 "하위 서비스 실패는 시간 안에 피드 실패로 끝난다"를 따른다. MVP 기준선의 구성은 `docs/experiments/baseline-mvp.md`와 아키텍처 4-2에 기록돼 있다.

## ADDED Requirements

### Requirement: 하위 서비스 실패는 시간 안에 피드 실패로 끝난다 (F-001)

하위 서비스 하나라도 실패하면(5xx, 4xx, 연결 실패, 시간 상한 초과, 서킷 열림, 벌크헤드 거절, 시간 예산 초과) 피드 요청 전체가 500으로 실패해야 한다(SHALL). 느린 하위 서비스를 시간 상한이나 예산을 넘겨 기다리지 않는다. 재시도와 fallback은 두지 않고, HTTP 클라이언트 라이브러리가 스스로 하는 재시도도 끈 채로 둔다. 고도화 4·5에서 이 요구사항을 바꾼다.

#### Scenario: 하위 서비스 하나가 5xx

- **GIVEN** post, user, like, recommendation, ad 중 하나가 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 500이 온다. 다섯 의존성 각각에 대해 같다
- **AND** 실패한 의존성에는 한 번만 요청한다

#### Scenario: 하위 서비스가 속도 조절 응답을 준다

- **GIVEN** ad-service가 429와 `Retry-After: 1`을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 500이 오고, ad-service에는 한 번만 요청한다

#### Scenario: 하위 서비스 하나에 연결할 수 없다

- **GIVEN** recommendation-service의 주소에 아무것도 떠 있지 않다
- **WHEN** `GET /feed`를 부른다
- **THEN** 500이 온다

#### Scenario: 하위 서비스 하나가 느리다

- **GIVEN** recommendation의 호출 시간 상한이 200ms이고, recommendation-service가 2초 늦게 응답하며 나머지는 정상이다
- **WHEN** `GET /feed`를 부른다
- **THEN** 400ms 안에 500이 온다
