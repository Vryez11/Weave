## MODIFIED Requirements

### Requirement: 요청 시간 예산 (F-001)

feed-service는 피드 요청 하나에 설정한 시간 예산을 넘겨 하위 서비스 응답을 기다리지 않아야 한다(SHALL). post 뒤에 부르는 user와 like는 남은 예산 안에서만 기다리고, 예산이 남지 않았으면 부르지 않는다. 예산이 다 될 때까지 끝나지 않은 의존성은 실패로 보고 등급대로 대체하며(`graceful-degradation`), feed-service는 그 의존성마다 `cause` `budget_exhausted`인 실패 로그를 남긴다.

#### Scenario: 의존성마다는 상한 안이지만 합치면 예산을 넘는다

- **GIVEN** 시간 예산이 250ms, post·user·like의 호출 시간 상한이 각각 200ms이고, post-service와 user-service가 각각 150ms 늦게 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** 응답이 350ms 안에 오고 200이며 `degradation.missing`에 `user`가 있다
- **AND** `dependency` `user`, `cause` `budget_exhausted`인 `downstream_call_failed` 줄이 있다

#### Scenario: 예산 안에 끝난다

- **GIVEN** 시간 예산이 250ms이고, 모든 하위 서비스가 각자의 호출 시간 상한보다 빨리 응답하며 post와 user·like의 지연 합이 150ms다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200과 함께 모든 영역이 채워진 피드가 온다
