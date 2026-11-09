# call-isolation Specification

## Purpose
feed-service가 하위 서비스를 부를 때 의존성마다 시간, 동시 호출 수, 실패 누적을 따로 제한해, 하위 서비스 하나의 장애가 피드 서버의 자원을 묶어 피드 전체로 번지지 않게 한다. 왜 하는지와 판정 기준은 [PRD](../../../docs/prd.md) F-001과 4장 S-02~S-06, S-08을 따른다.

## Requirements

### Requirement: 의존성별 호출 시간 상한 (F-001)

feed-service는 하위 서비스 호출마다 의존성별로 설정한 시간 상한을 넘기면 응답을 기다리지 않고 그 호출을 실패로 끝내야 한다(SHALL). 상한은 호출을 시작하려고 기다린 시간(벌크헤드 대기)까지 포함한다. 연결을 맺지 못하거나 응답이 오지 않아도 상한을 넘겨 기다리지 않는다. 실패 로그의 `cause`는 `timeout`이다.

#### Scenario: 느린 의존성

- **GIVEN** recommendation의 호출 시간 상한이 200ms이고, recommendation-service가 2초 늦게 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** 응답이 400ms 안에 온다
- **AND** feed-service가 `dependency` `recommendation`, `cause` `timeout`인 `downstream_call_failed` 줄을 남긴다

#### Scenario: 응답하지 않는 의존성

- **GIVEN** user의 호출 시간 상한이 100ms이고, user 주소는 연결을 받은 뒤 아무것도 보내지 않는다
- **WHEN** `GET /feed`를 부른다
- **THEN** 응답이 400ms 안에 온다
- **AND** `dependency` `user`, `cause` `timeout`인 `downstream_call_failed` 줄이 있다

#### Scenario: 상한 안의 지연

- **GIVEN** post의 호출 시간 상한이 100ms이고, post-service가 60ms 늦게 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200과 함께 게시글이 채워진 피드가 온다

### Requirement: 요청 시간 예산 (F-001)

feed-service는 피드 요청 하나에 설정한 시간 예산을 넘겨 하위 서비스 응답을 기다리지 않아야 한다(SHALL). post 뒤에 부르는 user와 like는 남은 예산 안에서만 기다리고, 예산이 남지 않았으면 부르지 않는다. 예산이 다 될 때까지 끝나지 않은 의존성은 실패로 보고 등급대로 대체하며(`graceful-degradation`), feed-service는 그 의존성마다 `cause` `budget_exhausted`인 실패 로그를 남긴다.

#### Scenario: 의존성마다는 상한 안이지만 합치면 예산을 넘는다

- **GIVEN** 시간 예산이 250ms, post·user·like의 호출 시간 상한이 각각 200ms이고, post-service와 user-service가 각각 150ms 늦게 응답한다
- **AND** 게시글 작성자의 프로필이 user 로컬 캐시에 없다(있으면 예산을 넘긴 user를 캐시로 채워 빠진 영역이 아니다, `graceful-degradation`)
- **WHEN** `GET /feed`를 부른다
- **THEN** 응답이 350ms 안에 오고 200이며 `degradation.missing`에 `user`가 있다
- **AND** `dependency` `user`, `cause` `budget_exhausted`인 `downstream_call_failed` 줄이 있다

#### Scenario: 예산 안에 끝난다

- **GIVEN** 시간 예산이 250ms이고, 모든 하위 서비스가 각자의 호출 시간 상한보다 빨리 응답하며 post와 user·like의 지연 합이 150ms다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200과 함께 모든 영역이 채워진 피드가 온다

### Requirement: 의존성별 벌크헤드 (F-001)

feed-service는 피드 조회의 하위 서비스 호출을 의존성마다 따로 둔 동시 호출 상한(벌크헤드) 안에서 돌려야 한다(SHALL). 한 의존성이 상한을 다 쓰면 그 의존성의 새 호출은 기다리지 않고 바로 실패하고(`cause` `bulkhead_full`), 다른 의존성의 호출에 쓸 수 있는 몫은 줄지 않는다. 벌크헤드 거절은 그 의존성을 부르지 않는다.

#### Scenario: 한 의존성이 상한을 다 쓴다

- **GIVEN** recommendation의 동시 호출 상한이 2, 호출 시간 상한이 2초, 시간 예산이 3초이고, recommendation-service가 1초 늦게 응답한다
- **WHEN** `GET /feed` 5개를 동시에 보낸다
- **THEN** recommendation-service가 받은 요청은 2개다
- **AND** `dependency` `recommendation`, `cause` `bulkhead_full`인 `downstream_call_failed` 줄이 3개 있다

#### Scenario: 다른 의존성의 몫은 그대로다

- **GIVEN** recommendation의 동시 호출 상한이 2이고 recommendation 호출이 2개 진행 중이다(recommendation-service가 응답하지 않는다)
- **WHEN** post·user·like의 동시 호출 상한보다 적은 수의 `GET /feed`를 동시에 보낸다
- **THEN** post, user, like 호출은 모두 하위 서비스까지 가고, 그 호출의 실패 로그는 없다

### Requirement: 서킷 브레이커의 실패 분류 (F-001)

feed-service는 의존성마다 서킷 브레이커를 두고, 호출 시간 상한 초과, 연결 실패, 그 밖의 입출력 실패, 5xx 응답을 실패로 세야 한다(SHALL). 4xx 응답(429 포함), 벌크헤드 거절, 열린 서킷으로 막힌 호출, 요청 시간 예산 초과, 요청 수 상한(RateLimiter)으로 막은 호출, Retry-After 대기로 막은 호출은 실패로 세지 않는다(뒤의 둘은 `retry-policy`). 설정한 최근 호출 수 중 실패 비율이 문턱 이상이면 서킷이 열린다. 재시도(`retry-policy`)는 시도마다 서킷을 거치므로 시도 하나하나가 집계된다.

#### Scenario: 5xx가 이어진다

- **GIVEN** recommendation 서킷이 최근 호출 10개 중 실패 비율 50% 이상이면 열리도록 설정됐고, recommendation-service가 503을 준다
- **WHEN** `GET /feed`를 10번 부른다
- **THEN** recommendation 서킷이 열린다

#### Scenario: 4xx는 세지 않는다

- **GIVEN** 위와 같은 설정에서 ad-service가 429와 `Retry-After: 60`을, recommendation-service가 404를 준다
- **WHEN** `GET /feed`를 20번 부른다
- **THEN** ad와 recommendation 서킷은 닫혀 있다
- **AND** recommendation-service는 요청 20개를 받는다
- **AND** ad-service는 요청 1개를 받는다(나머지 19번은 Retry-After 동안 부르지 않는다)

#### Scenario: 벌크헤드 거절은 세지 않는다

- **GIVEN** recommendation 서킷이 최근 호출 10개 중 50% 이상 실패면 열리도록 설정됐고, 동시 호출 상한이 1, 호출 시간 상한이 1초, 시간 예산이 2초이며, recommendation-service는 정상이지만 300ms 걸린다
- **WHEN** `GET /feed` 10개를 동시에 보낸다
- **THEN** `cause` `bulkhead_full`인 실패가 있지만 recommendation 서킷은 닫혀 있다

### Requirement: 열린 서킷은 부르지 않는다 (F-001)

서킷이 열린 의존성의 호출은 하위 서비스를 부르지 않고, 벌크헤드 자리도 쓰지 않고, 바로 실패해야 한다(SHALL). 실패 로그의 `cause`는 `circuit_open`이다.

#### Scenario: 열린 뒤에는 부르지 않는다

- **GIVEN** recommendation 서킷이 열렸고 열림 유지 시간이 10초다
- **WHEN** 그 10초 안에 `GET /feed`를 20번 부른다
- **THEN** recommendation-service는 요청을 하나도 받지 않는다
- **AND** 20번 모두 50ms 안에 응답한다
- **AND** `dependency` `recommendation`, `cause` `circuit_open`인 실패 줄이 20개 있다

### Requirement: 복구하면 서킷이 닫힌다 (F-001)

열린 서킷은 설정한 열림 유지 시간이 지나면 요청 없이도 반열림이 되고, 반열림에서 허용한 시험 호출이 문턱보다 적게 실패하면 닫혀 정상 호출로 돌아와야 한다(SHALL). 시험 호출이 문턱 이상 실패하면 다시 열린다.

#### Scenario: 복구된 의존성

- **GIVEN** recommendation 서킷이 열렸고 열림 유지 시간이 1초, 반열림 시험 호출이 3개이며, recommendation-service가 정상으로 돌아왔다
- **WHEN** 1초 뒤부터 `GET /feed`를 부른다
- **THEN** 결국 recommendation 서킷이 닫히고, 이후 피드는 200과 함께 추천이 채워진다

#### Scenario: 아직 아프다

- **GIVEN** 위와 같은 설정에서 recommendation-service가 계속 503을 준다
- **WHEN** 1초 뒤부터 `GET /feed`를 3번 부른다
- **THEN** recommendation-service는 시험 호출 3개를 받고 서킷은 다시 열린다

### Requirement: 벌크헤드 스레드에서도 traceId가 이어진다 (F-001, F-004)

벌크헤드의 스레드에서 돈 하위 서비스 호출은 피드 요청의 traceId를 `traceparent`로 보내고, 그 스레드에서 남긴 로그에도 같은 `traceId`가 있어야 한다(SHALL). 트레이스 문맥 전파를 끈 구성에서는 끊기는 것이 테스트로 남아 있어야 한다.

#### Scenario: 다섯 호출의 traceId

- **WHEN** `GET /feed`를 부른다
- **THEN** post, recommendation, ad, user, like로 간 요청 다섯 개의 `traceparent` traceId가 모두 응답의 `X-Trace-Id`와 같다

#### Scenario: 벌크헤드 스레드의 실패 로그

- **GIVEN** like-service가 500을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** `dependency` `like`인 `downstream_call_failed` 줄의 `traceId`가 응답의 `X-Trace-Id`와 같다

#### Scenario: 전파가 없으면 끊긴다

- **GIVEN** 벌크헤드 스레드로 넘어갈 때 트레이스 문맥을 넘기지 않는 구성이다
- **WHEN** `GET /feed`를 부른다
- **THEN** 하위 서비스 요청의 `traceparent` traceId가 응답의 `X-Trace-Id`와 다르다

### Requirement: 서킷 상태 전이 로그 (F-001, F-004)

feed-service는 서킷 상태가 바뀔 때마다 `event` `circuit_state_changed`, `dependency`, `from`, `to`(`CLOSED`, `OPEN`, `HALF_OPEN` 중 하나)를 담은 로그 한 줄을 남겨야 한다(SHALL). 피드 요청의 호출이 전이를 일으켰으면 그 요청의 `traceId`가 함께 있다. 시간이 지나 저절로 반열림이 되는 전이에는 `traceId`가 없을 수 있다.

#### Scenario: 서킷이 열린다

- **GIVEN** recommendation 서킷이 최근 호출 10개 중 50% 이상 실패면 열리도록 설정됐고, recommendation-service가 503을 준다
- **WHEN** 서킷이 열릴 때까지 `GET /feed`를 부른다
- **THEN** `event` `circuit_state_changed`, `dependency` `recommendation`, `from` `CLOSED`, `to` `OPEN`인 줄이 하나 있다
- **AND** 그 줄의 `traceId`는 서킷을 연 피드 요청의 `X-Trace-Id`와 같다

### Requirement: 격리 지표 (F-001)

feed-service는 `/actuator/prometheus`에 의존성마다 서킷 상태, 서킷이 막은 호출 수, 벌크헤드의 사용 중 스레드·최대 스레드·대기열 길이, 시간 상한 초과 수를 의존성 이름(`post`, `user`, `like`, `recommendation`, `ad`)으로 구분해 내보내야 한다(SHALL).

#### Scenario: 지표가 있다

- **GIVEN** 하위 서비스 5개가 정상 응답한다
- **WHEN** `GET /feed`를 한 번 부른 뒤 `GET /actuator/prometheus`를 부른다
- **THEN** 의존성 이름 5개마다 서킷 상태, 벌크헤드 사용량, 시간 상한 초과 지표가 있다
- **AND** 다섯 서킷의 상태 값이 모두 닫힘이다

### Requirement: 격리 설정값은 설정으로 바꾼다 (F-001)

호출 시간 상한, 요청 시간 예산, 연결·응답·커넥션 대기 타임아웃, 커넥션 풀 크기, 벌크헤드 크기, 서킷의 실패 문턱·최근 호출 수·열림 유지 시간·반열림 시험 호출 수는 의존성마다 코드가 아닌 설정 파일에 있고, 환경 변수로 덮어쓸 수 있어야 한다(SHALL).

#### Scenario: 환경 변수로 상한을 바꾼다

- **GIVEN** recommendation의 호출 시간 상한을 환경 변수로 1,000ms로 바꿔 띄웠고, recommendation-service가 500ms 늦게 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200과 함께 추천이 채워진 피드가 온다

### Requirement: 좋아요 상태 변경의 격리 (F-001, F-003)

feed-service는 좋아요 상태 변경 호출에도 동시 호출 상한과 like의 연결·응답 타임아웃을 적용하고, like의 서킷이 열려 있으면 like-service를 부르지 않고 바로 실패해야 한다(SHALL). 동시 호출 상한은 좋아요 조회의 벌크헤드와 따로 둔다. 실패하면 500이다.

#### Scenario: like의 서킷이 열려 있다

- **GIVEN** like의 서킷이 열려 있다
- **WHEN** `PUT /feed/likes/5`를 부른다
- **THEN** 500이 오고, like-service는 상태 변경 요청을 받지 않는다
- **AND** `dependency` `like`, `cause` `circuit_open`인 `downstream_call_failed` 줄이 있다

#### Scenario: like-service가 응답하지 않는다

- **GIVEN** like의 응답 타임아웃이 100ms이고, like-service가 상태 변경에 2초 늦게 응답한다
- **WHEN** `PUT /feed/likes/5`를 부른다
- **THEN** 400ms 안에 500이 오고, like-service에는 한 번만 요청한다
