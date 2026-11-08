## MODIFIED Requirements

### Requirement: 중요·선택 등급의 실패는 피드를 실패시키지 않는다 (F-002)

user, like, recommendation, ad 호출이 실패해도(5xx, 4xx, 연결 실패, 호출 시간 상한 초과, 서킷 열림, 벌크헤드 거절, 요청 시간 예산 초과, 요청 수 상한, Retry-After 대기) `GET /feed`는 200이어야 한다(SHALL). 실패한 의존성은 재시도(`retry-policy`)가 끝난 뒤의 마지막 실패로 등급대로 대체한다. 대체를 정한 뒤에는 다시 부르지 않는다.

#### Scenario: 중요·선택 등급 하나가 5xx

- **GIVEN** user, like, recommendation, ad 중 하나가 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200이 온다. 네 의존성 각각에 대해 같다
- **AND** 실패한 의존성에는 그 의존성의 최대 시도 횟수(`retry-policy`)보다 많이 요청하지 않는다

#### Scenario: 중요·선택 등급이 모두 실패한다

- **GIVEN** post만 정상이고 user, like, recommendation, ad가 모두 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200과 함께 post-service가 준 게시글이 같은 순서로 온다
- **AND** `degradation.missing`은 `user`, `like`, `recommendation`, `ad` 네 개다

#### Scenario: 느린 선택 의존성

- **GIVEN** recommendation의 호출 시간 상한이 200ms이고, recommendation-service가 2초 늦게 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** 400ms 안에 200이 오고 `degradation.missing`에 `recommendation`이 있다

#### Scenario: 서킷이 열린 선택 의존성

- **GIVEN** recommendation 서킷이 열려 있다
- **WHEN** `GET /feed`를 부른다
- **THEN** recommendation-service는 요청을 받지 않고, 200이 오며 `degradation.missing`에 `recommendation`이 있다
