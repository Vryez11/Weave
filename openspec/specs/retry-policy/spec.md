# retry-policy Specification

## Purpose
feed-service가 멱등한 조회의 순간 실패를 시간 예산과 재시도 예산 안에서만 다시 불러, 재시도가 장애 중에 하위 서비스 요청을 불리지 않게 한다. 외부 API(ad)는 다시 부르지 않고 429의 Retry-After를 지키며 계약 쿼터를 넘기 전에 스스로 멈춘다. 기준은 [PRD](../../../docs/prd.md) F-005와 4장 S-07, S-09를 따른다.

## Requirements

### Requirement: 재시도하는 실패 (F-005)

feed-service는 5xx 응답, 연결 실패, 응답 전에 연결이 끊긴 입출력 실패만 다시 불러야 한다(SHALL). 4xx 응답(429 포함), 호출 시간 상한·응답 타임아웃 초과, 커넥션 풀 대기 시간 초과, 서킷 열림, 벌크헤드 거절, 요청 시간 예산 초과, 속도 제한, Retry-After 대기는 다시 부르지 않는다. 재시도 한 번 한 번은 서킷 브레이커를 거치므로, 재시도하다 서킷이 열리면 남은 재시도는 보내지 않는다.

#### Scenario: 4xx는 다시 부르지 않는다

- **GIVEN** recommendation의 최대 시도 횟수가 3이고, recommendation-service가 404를 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** recommendation-service는 요청을 한 번만 받는다

#### Scenario: 시간 상한 초과는 다시 부르지 않는다

- **GIVEN** recommendation의 최대 시도 횟수가 3, 호출 시간 상한이 200ms이고, recommendation-service가 1초 늦게 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** recommendation-service는 요청을 한 번만 받는다

#### Scenario: 연결 실패는 다시 부른다

- **GIVEN** recommendation의 최대 시도 횟수가 3이고, recommendation 주소에 듣는 프로세스가 없다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200이고 `degradation.missing`은 `recommendation` 하나다
- **AND** `dependency` `recommendation`, `cause` `connect_failure`인 `downstream_call_retried` 줄이 두 개다

#### Scenario: 재시도하다 서킷이 열린다

- **GIVEN** like의 최대 시도 횟수가 3이고, like 서킷이 최근 호출 2개 중 50% 이상 실패하면 열리도록 설정됐으며, like-service가 늘 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** like-service는 좋아요 조회를 두 번만 받는다
- **AND** `dependency` `like`인 `downstream_call_failed` 줄의 `cause`는 `circuit_open`이다

#### Scenario: 열린 서킷은 다시 부르지 않는다

- **GIVEN** like의 최대 시도 횟수가 3이고, like 서킷이 열려 있다
- **WHEN** `GET /feed`를 부른다
- **THEN** like-service는 요청을 받지 않고 `downstream_call_retried` 줄도 없다

### Requirement: 재시도 간격 (F-005)

feed-service는 n번째 재시도(n = 1, 2, ...) 전에 기본 간격 × 배수^(n-1)을 jitter 비율만큼 무작위로 늘리거나 줄인 시간을 기다려야 한다(SHALL). 기다리는 동안 호출 스레드나 벌크헤드 자리를 잡지 않는다.

#### Scenario: 간격이 늘어난다

- **GIVEN** like의 최대 시도 횟수가 3, 기본 간격이 100ms, 배수가 2, jitter 비율이 0.5이고, like-service가 늘 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** like-service가 받은 첫 요청과 둘째 요청 사이는 50ms 이상 250ms 이하이고, 둘째와 셋째 사이는 100ms 이상 400ms 이하다

### Requirement: 남은 시간 예산 안에서만 재시도한다 (F-005)

feed-service는 다시 부르기 전에 그 피드 요청의 남은 시간 예산을 보고, 남은 시간이 그 의존성의 호출 시간 상한과 기다릴 시간의 합보다 짧으면 다시 부르지 않아야 한다(SHALL). 그래서 재시도는 피드 응답보다 늦게 끝나지 않는다. 재시도를 기다리는 동안 그 호출이 먼저 끝났으면(요청 시간 예산이 끝낸 경우 등) 기다리던 재시도를 보내지 않는다.

#### Scenario: 남은 예산이 모자라다

- **GIVEN** 요청 시간 예산이 250ms, like의 호출 시간 상한과 응답 타임아웃이 200ms, 최대 시도 횟수가 3이고, like-service가 100ms 늦게 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200이고 `degradation.missing`은 `like` 하나다
- **AND** like-service는 좋아요 조회를 한 번만 받는다
- **AND** `weave_retry_calls_total{name="like",outcome="deadline"}`이 1 늘었다

#### Scenario: 기다리는 동안 호출이 먼저 끝났다

- **GIVEN** 최대 시도 횟수가 3, 기본 간격이 200ms이고, 첫 시도가 재시도할 수 있는 실패로 끝나 재시도를 기다리고 있다
- **WHEN** 기다리는 동안 그 호출의 결과를 다른 쪽(요청 시간 예산)이 먼저 끝내고 300ms가 지난다
- **THEN** 두 번째 시도는 보내지 않는다

### Requirement: 재시도 예산 (F-005)

feed-service는 의존성마다 최근 창(설정한 길이)의 원래 호출 수에 재시도 예산 비율을 곱한 값과 최솟값 중 큰 값까지만 재시도를 보내야 한다(SHALL). 창 안에서 보낸 재시도가 그 값에 닿으면 재시도할 수 있는 실패라도 다시 부르지 않고 바로 대체한다. 원래 호출은 서킷이 막은 호출까지 센다.

#### Scenario: 예산이 다 된다

- **GIVEN** 재시도 예산 비율이 0.1, 최솟값이 2, 창이 10초이고, like의 최대 시도 횟수가 3이며, like 서킷이 열리지 않는 설정이고, like-service가 늘 503을 준다
- **WHEN** 10초 안에 `GET /feed`를 20번 부른다
- **THEN** like-service가 받은 좋아요 조회는 22개다
- **AND** `weave_retry_calls_total{name="like",outcome="budget_exhausted"}`이 늘었다

#### Scenario: 창이 지나면 다시 쓴다

- **GIVEN** 위와 같은 설정에서 창이 1초이고, 피드를 20번 불러 예산을 다 썼다
- **WHEN** 1.5초 뒤 `GET /feed`를 한 번 부른다
- **THEN** 그 피드 요청으로 like-service는 좋아요 조회를 세 번 받는다

### Requirement: Retry-After 동안 부르지 않는다 (F-005)

하위 서비스가 429와 `Retry-After`를 주면 feed-service는 그 시각까지 그 의존성을 부르지 않고 바로 실패 처리해(`cause` `retry_after`) 등급대로 대체해야 한다(SHALL). `Retry-After`는 초(정수)와 HTTP 날짜를 받는다. 머리글이 없거나 읽을 수 없으면 설정한 기본 대기 시간만큼, 설정한 최대 대기 시간보다 길면 최대 대기 시간까지만 부르지 않는다. 대기는 서킷 브레이커에 세지 않는다. 대기 중에 429를 다시 받으면 더 늦은 시각으로 늘린다.

#### Scenario: 초로 준다

- **GIVEN** ad-service가 429와 `Retry-After: 2`를 준다
- **WHEN** `GET /feed`를 한 번 부르고, 2초 안에 다섯 번 더 부른다
- **THEN** 여섯 번 모두 200이고 `degradation.missing`은 `ad` 하나다
- **AND** ad-service는 요청을 한 번만 받는다
- **AND** `dependency` `ad`, `cause` `retry_after`인 `downstream_call_failed` 줄이 다섯 개이고, ad 서킷은 닫혀 있다

#### Scenario: 대기가 끝난다

- **GIVEN** ad-service가 첫 요청에 429와 `Retry-After: 1`을 주고 그 뒤에는 정상으로 응답한다
- **WHEN** `GET /feed`를 부르고 1.5초 뒤 다시 부른다
- **THEN** 두 번째 피드에는 광고가 채워져 있고 ad-service는 요청을 두 번 받는다

#### Scenario: HTTP 날짜로 준다

- **GIVEN** ad-service가 429와 지금부터 3초 뒤의 HTTP 날짜를 담은 `Retry-After`를 준다
- **WHEN** `GET /feed`를 한 번 부르고, 1초 안에 다섯 번 더 부른다
- **THEN** ad-service는 요청을 한 번만 받는다

#### Scenario: 머리글이 없다

- **GIVEN** 기본 대기 시간이 1초이고, ad-service가 `Retry-After` 없이 429를 준다
- **WHEN** `GET /feed`를 한 번 부르고, 0.5초 안에 세 번 더 부른 뒤, 1.5초 뒤 다시 부른다
- **THEN** ad-service는 요청을 두 번 받는다(처음과 마지막)

#### Scenario: 최대 대기 시간보다 길다

- **GIVEN** 최대 대기 시간이 1초이고, ad-service가 첫 요청에 429와 `Retry-After: 3600`을 주고 그 뒤에는 정상으로 응답한다
- **WHEN** `GET /feed`를 부르고 1.5초 뒤 다시 부른다
- **THEN** 두 번째 피드에는 광고가 채워져 있다

### Requirement: ad 계약 쿼터를 넘기 전에 멈춘다 (F-005)

feed-service는 ad 호출에 요청 수 상한(RateLimiter)을 두고, 주기마다의 상한을 넘는 ad 호출은 보내지 않고 기다리지도 않고 바로 실패 처리해(`cause` `rate_limited`) 광고 영역을 빼야 한다(SHALL). 상한으로 막은 호출은 서킷 브레이커에 세지 않는다. 상한은 ad의 계약 쿼터(PRD 3장)에서 정한다.

#### Scenario: 상한을 넘는다

- **GIVEN** ad의 요청 수 상한이 10초에 3개이고 ad-service는 정상이다
- **WHEN** `GET /feed`를 다섯 번 부른다
- **THEN** ad-service는 요청을 세 개 받는다
- **AND** 네 번째와 다섯 번째 피드는 200이고 `degradation.missing`은 `ad` 하나다
- **AND** `dependency` `ad`, `cause` `rate_limited`인 `downstream_call_failed` 줄이 두 개이고, ad 서킷은 닫혀 있다

#### Scenario: 다음 주기

- **GIVEN** ad의 요청 수 상한이 1초에 1개다
- **WHEN** `GET /feed`를 두 번 부르고 1.5초 뒤 한 번 더 부른다
- **THEN** ad-service는 요청을 두 개 받고, 세 번째 피드에는 광고가 채워져 있다

### Requirement: 재시도와 대기 이벤트 로그 (F-005, F-004)

feed-service는 다시 부를 때마다 `event` `downstream_call_retried`, `dependency`, `attempt`(이제 보낼 시도 번호, 2부터), `cause`(앞 시도의 실패 원인), `waitMs`(기다린 시간)를 담은 줄을 한 줄 남겨야 한다(SHALL). 대기 중이 아니던 의존성의 Retry-After 대기를 시작할 때는 `event` `retry_after_hold_started`, `dependency`, `holdMs`(적용한 대기 시간), `holdUntil`(ISO-8601)을 담은 줄을 한 줄 남긴다. 두 줄과 다시 부른 호출의 `traceparent`에는 그 피드 요청의 traceId가 있다. 대기 중에 막은 호출이나 대기 중에 받은 429로 대기를 늘릴 때는 대기 로그를 다시 남기지 않는다(막은 호출은 `cause` `retry_after`인 실패 로그로 남는다).

#### Scenario: 다시 부른다

- **GIVEN** 최대 시도 횟수가 3이고, like-service가 첫 좋아요 조회에 503을 주고 그 뒤에는 정상으로 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** `event` `downstream_call_retried`, `dependency` `like`, `attempt` 2, `cause` `http_503`인 줄이 하나 있고 `waitMs`가 0 이상이다
- **AND** 그 줄의 `traceId`와 like-service가 받은 두 요청의 `traceparent` traceId가 모두 응답의 `X-Trace-Id`와 같다

#### Scenario: Retry-After 대기를 시작한다

- **GIVEN** ad-service가 429와 `Retry-After: 2`를 준다
- **WHEN** `GET /feed`를 두 번 부른다
- **THEN** `event` `retry_after_hold_started`, `dependency` `ad`, `holdMs` 2000인 줄이 하나이고, 그 줄의 `traceId`는 첫 피드의 `X-Trace-Id`와 같다

### Requirement: 재시도와 외부 API 지표 (F-005)

feed-service는 `/actuator/prometheus`에 의존성마다 재시도 결정 수(`weave_retry_calls_total`, `outcome`은 `retried`·`budget_exhausted`·`deadline`·`attempts_exhausted`), Retry-After 대기로 막은 호출 수(`weave_retry_after_rejected_calls_total`)와 남은 대기 시간(`weave_retry_after_hold_remaining_seconds`), 요청 수 상한이 있는 의존성의 상한으로 막은 호출 수(`weave_ratelimiter_rejected_calls_total`)를 의존성 이름(`name`)으로 내보내야 한다(SHALL). 대시보드와 판정이 장애 전에도 시계열을 보도록 시작할 때 0으로 등록한다.

#### Scenario: 지표가 있다

- **WHEN** 피드를 부르기 전에 `GET /actuator/prometheus`를 부른다
- **THEN** 의존성 5개마다 재시도 결정 네 가지, Retry-After로 막은 호출 수, 남은 대기 시간이 있고, ad의 상한으로 막은 호출 수가 있다

#### Scenario: 재시도를 센다

- **GIVEN** 최대 시도 횟수가 3이고, like-service가 첫 좋아요 조회에 503을 주고 그 뒤에는 정상으로 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** `weave_retry_calls_total{name="like",outcome="retried"}`이 1 늘었다

### Requirement: 재시도와 외부 API 설정값은 설정으로 바꾼다 (F-005)

의존성별 최대 시도 횟수, 기본 간격, 배수, jitter 비율, 재시도 예산(비율, 최솟값, 창), Retry-After 기본·최대 대기 시간, ad 요청 수 상한(주기당 개수, 주기)은 코드가 아닌 설정 파일에 있고 환경 변수로 덮어쓸 수 있어야 한다(SHALL).

#### Scenario: 환경 변수로 최대 시도 횟수를 바꾼다

- **GIVEN** like의 최대 시도 횟수를 환경 변수로 2로 바꿔 띄웠고, like-service가 늘 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** like-service는 좋아요 조회를 두 번 받는다

### Requirement: 조회만 재시도한다 (F-005, F-003)

feed-service는 post, user, like, recommendation 조회가 재시도할 수 있는 실패("재시도하는 실패")로 끝나면 설정한 최대 시도 횟수까지 그 의존성을 다시 불러야 한다(SHALL). 다시 부른 호출이 성공하면 그 영역은 정상으로 채우고, 모두 실패하면 마지막 실패로 등급대로 대체한다(`graceful-degradation`). 좋아요 상태 변경(재시도는 같은 `version`으로 다시 보내는 클라이언트 한 층에 둔다)과 ad(외부 API)는 다시 부르지 않는다. 실패 로그(`downstream_call_failed`)는 마지막 시도가 실패했을 때 한 줄만 남긴다.

#### Scenario: 한 번 실패한 뒤 성공한다

- **GIVEN** 최대 시도 횟수가 3이고, like-service가 첫 좋아요 조회에 503을 주고 그 뒤에는 정상으로 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200이고 좋아요가 채워져 있으며 `degradation.missing`은 비어 있다
- **AND** like-service는 좋아요 조회를 두 번 받는다
- **AND** `dependency` `like`인 `downstream_call_failed` 줄이 없다

#### Scenario: 계속 실패한다

- **GIVEN** 최대 시도 횟수가 3이고, like-service가 늘 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200이고 `degradation.missing`은 `like` 하나다
- **AND** like-service는 좋아요 조회를 세 번 받는다
- **AND** `dependency` `like`인 `downstream_call_failed` 줄이 하나다

#### Scenario: 광고는 다시 부르지 않는다

- **GIVEN** ad-service가 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200이고 `degradation.missing`은 `ad` 하나다
- **AND** ad-service는 요청을 한 번만 받는다

#### Scenario: 좋아요 상태 변경은 다시 부르지 않는다

- **GIVEN** like의 최대 시도 횟수가 3이고, like-service가 상태 변경에 503을 준다
- **WHEN** `PUT /feed/likes/{postId}`를 부른다
- **THEN** 500이 오고, like-service는 상태 변경을 한 번만 받는다
