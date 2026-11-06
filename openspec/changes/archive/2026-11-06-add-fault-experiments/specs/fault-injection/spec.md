# Spec Delta

## Purpose

하위 서비스 5개(post, user, like, recommendation, ad)가 실험 중에 응답 지연과 에러를 설정대로 만들어, 장애 실험이 같은 앱 수준 장애를 몇 번이든 똑같이 재현할 수 있게 한다. 무엇을 왜 하는지는 [PRD](../../../docs/prd.md) F-007을 따른다.

## ADDED Requirements

### Requirement: 실험 프로필에서만 켜진다 (F-007)

하위 서비스 5개는 `experiment` 프로필로 띄웠을 때만 장애 주입을 켜고 `GET`·`POST`·`DELETE /actuator/faults`를 열어야 한다(SHALL). 프로필 없이 띄우면 엔드포인트가 없고 응답에 지연이나 에러를 더하지 않는다. feed-service에는 장애 주입이 없다.

#### Scenario: 실험 프로필로 띄운다

- **WHEN** post, user, like, recommendation, ad 서비스를 각각 `experiment` 프로필로 띄우고 `GET /actuator/faults`를 부른다
- **THEN** 200과 함께 지금 장애 설정(지연 분포의 중앙값·p99, 에러율)이 온다

#### Scenario: 프로필 없이 띄운다

- **WHEN** 하위 서비스를 프로필 없이 띄우고 `GET /actuator/faults`를 부른다
- **THEN** 404가 온다

#### Scenario: feed-service에는 없다

- **WHEN** feed-service를 `experiment` 프로필로 띄우고 `GET /actuator/faults`를 부른다
- **THEN** 404가 온다

### Requirement: 평시 지연 분포 (F-007)

`experiment` 프로필의 하위 서비스는 업무 요청마다 설정한 분포(중앙값, p99)에서 뽑은 시간만큼 늦게 응답해야 한다(SHALL). 프로필의 기본값은 중앙값 20ms, p99 50ms다(PRD F-007의 "p99 약 50ms").

#### Scenario: 기본 분포를 따른다

- **GIVEN** `experiment` 프로필로 띄운 post-service가 기본 장애 설정이다
- **WHEN** `GET /posts`를 1,000번 차례로 부른다
- **THEN** 응답 시간의 중앙값은 20ms 이상 30ms 이하다
- **AND** 응답 시간의 p99는 40ms 이상 65ms 이하다

### Requirement: 실행 중에 지연을 바꾼다 (F-007)

`POST /actuator/faults`로 지연 분포의 중앙값과 p99를 바꾸면, 다시 띄우지 않아도 그 뒤의 업무 요청에 바로 적용돼야 한다(SHALL). 중앙값과 p99를 같게 주면 모든 요청이 그 시간만큼 늦는다.

#### Scenario: 고정 지연

- **WHEN** 지연을 중앙값·p99 모두 300ms로 바꾸고 업무 요청을 20번 부른다
- **THEN** 20번 모두 300ms 이상 걸려 응답한다
- **AND** `GET /actuator/faults`가 바꾼 값을 돌려준다

### Requirement: 에러율 (F-007)

`POST /actuator/faults`로 에러율(0~1)을 정하면, 업무 요청마다 그 확률로 처리하지 않고 500을 돌려줘야 한다(SHALL). 에러를 준 응답에도 지연은 그대로 더한다. 기본 에러율은 0이다.

#### Scenario: 에러율 1

- **WHEN** 에러율을 1로 바꾸고 업무 요청을 20번 부른다
- **THEN** 20번 모두 500이다

#### Scenario: 에러율 0.5

- **WHEN** 에러율을 0.5로 바꾸고 업무 요청을 1,000번 부른다
- **THEN** 500의 비율이 0.45 이상 0.55 이하이고, 나머지는 평소 응답(200)이다

### Requirement: 장애 설정 되돌리기 (F-007)

`DELETE /actuator/faults`는 장애 설정을 프로필의 기본값으로 되돌려야 한다(SHALL). 여러 번 불러도 결과가 같다.

#### Scenario: 되돌린다

- **GIVEN** 지연을 300ms로, 에러율을 1로 바꿨다
- **WHEN** `DELETE /actuator/faults`를 두 번 부른다
- **THEN** 두 번 모두 2xx다
- **AND** `GET /actuator/faults`가 기본값(중앙값 20ms, p99 50ms, 에러율 0)을 돌려주고, 업무 요청이 200으로 온다

### Requirement: 관리 경로는 장애에서 뺀다 (F-007)

`/actuator`로 시작하는 경로(헬스 체크, 메트릭, 장애 설정 자체)에는 지연과 에러를 더하지 않아야 한다(SHALL). 장애를 넣는 중에도 헬스 체크가 실패하거나 장애를 거두지 못하는 일이 없어야 한다.

#### Scenario: 장애 중의 헬스 체크

- **GIVEN** 지연을 중앙값·p99 모두 1,000ms로, 에러율을 1로 바꿨다
- **WHEN** `GET /actuator/health`, `GET /actuator/prometheus`, `GET /actuator/faults`를 부른다
- **THEN** 셋 모두 200이고 각각 1,000ms보다 빨리 응답한다

### Requirement: 잘못된 장애 설정은 거절한다 (F-007)

`POST /actuator/faults`는 에러율이 0~1 밖이거나, 지연이 음수이거나, p99가 중앙값보다 작으면 400을 돌려주고 지금 설정을 바꾸지 않아야 한다(SHALL).

#### Scenario: 범위를 벗어난 값

- **WHEN** 에러율 1.5, 중앙값 -1ms, 중앙값 100ms·p99 50ms 중 하나로 `POST /actuator/faults`를 부른다
- **THEN** 400이 온다
- **AND** `GET /actuator/faults`가 부르기 전과 같은 값을 돌려준다
