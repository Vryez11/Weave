# Spec Delta

## ADDED Requirements

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

## REMOVED Requirements

### Requirement: 좋아요 토글의 격리 (F-001)

**Reason**: 토글 API를 없앤다(F-003). 격리는 상태 변경 호출이 이어받는다.
**Migration**: "좋아요 상태 변경의 격리"를 따른다.
