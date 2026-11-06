# Spec Delta

## ADDED Requirements

### Requirement: 요청 한도 (F-007)

ad-service는 외부 API의 쿼터를 흉내 내, 요청 한도가 켜져 있으면 정해진 시간 창 안에서 한도를 넘은 `GET /ads`에 429와 `Retry-After`를 돌려줘야 한다(SHALL). 한도는 `experiment` 프로필에서만 열리는 `/actuator/quota`로 켜고(창 안의 요청 수, 창 길이 초) 끈다. 기본은 꺼져 있다.

- 창은 한도를 켠 시각부터 창 길이마다 새로 시작한다. 429로 거절한 요청은 한도에 세지 않는다.
- `Retry-After`는 지금 창이 끝날 때까지 남은 초를 올림한 정수이고 1 이상이다.

#### Scenario: 한도를 넘는다

- **GIVEN** 한도를 창 10초에 요청 3개로 켰다
- **WHEN** 같은 창 안에서 `GET /ads`를 4번 부른다
- **THEN** 앞의 3번은 200이고 4번째는 429다
- **AND** 429 응답의 `Retry-After`는 1 이상 10 이하의 정수다

#### Scenario: 다음 창

- **GIVEN** 한도를 창 2초에 요청 1개로 켰고, `GET /ads`가 한 번 200, 다음에 429와 `Retry-After`를 받았다
- **WHEN** `Retry-After`만큼 기다린 뒤 `GET /ads`를 부른다
- **THEN** 200이 온다

#### Scenario: 한도 0

- **GIVEN** 한도를 창 10초에 요청 0개로 켰다
- **WHEN** `GET /ads`를 부른다
- **THEN** 429와 `Retry-After`가 온다

#### Scenario: 꺼진 한도

- **GIVEN** `experiment` 프로필로 띄운 ad-service에서 한도를 켰다가 `DELETE /actuator/quota`로 껐다
- **WHEN** `GET /ads`를 100번 부른다
- **THEN** 100번 모두 200이다

#### Scenario: 실험 프로필이 아니다

- **WHEN** ad-service를 프로필 없이 띄우고 `GET /actuator/quota`를 부른다
- **THEN** 404가 온다

#### Scenario: 잘못된 한도

- **WHEN** 요청 수 -1 또는 창 길이 0초로 한도를 켜려고 한다
- **THEN** 400이 오고 한도는 켜지기 전 상태 그대로다
