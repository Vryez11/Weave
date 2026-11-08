## MODIFIED Requirements

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
