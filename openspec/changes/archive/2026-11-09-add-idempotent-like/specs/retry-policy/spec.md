# Spec Delta

## ADDED Requirements

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

## REMOVED Requirements

### Requirement: 멱등한 조회만 재시도한다 (F-005)

**Reason**: 상태 기반 좋아요도 멱등해져 "멱등한 것만"이 재시도 대상을 가리키지 못한다. 재시도 대상과 조건은 그대로다.
**Migration**: "조회만 재시도한다"를 따른다.
