# Spec Delta

## Purpose

user-service가 피드 게시글의 작성자 프로필을 여러 명 한 번에 내준다. 피드에서 중요 등급 의존성이다([PRD](../../../../../docs/prd.md) 3장).

## ADDED Requirements

### Requirement: 프로필 배치 조회 (F-006)

user-service는 `GET /users?ids=1,2,3`에 요청한 ID의 프로필을 돌려줘야 한다(SHALL).

- 프로필마다 `id`, `nickname`, `profileImageUrl`이 있다.
- 없는 ID는 응답에서 빠지고, 요청 전체는 실패하지 않는다.
- 같은 ID를 여러 번 보내도 프로필은 한 번만 온다.
- `ids`는 1~100개를 받는다.

#### Scenario: 여러 명 조회

- **WHEN** `GET /users?ids=1,2,3`을 부른다
- **THEN** 200과 함께 ID 1, 2, 3의 프로필이 온다

#### Scenario: 없는 ID가 섞여 있다

- **WHEN** `GET /users?ids=1,9999`를 부른다
- **THEN** 200과 함께 ID 1의 프로필만 온다

#### Scenario: 중복 ID

- **WHEN** `GET /users?ids=2,2,2`를 부른다
- **THEN** ID 2의 프로필이 하나만 온다

#### Scenario: ID가 없거나 너무 많다

- **WHEN** `ids` 없이, 또는 101개의 ID로 `GET /users`를 부른다
- **THEN** 400이 온다

### Requirement: 고정 사용자 데이터 (F-006)

user-service는 시작할 때 ID 1~50의 사용자 50명을 만들고, 같은 버전이면 다시 띄워도 같은 프로필로 응답해야 한다(SHALL).

#### Scenario: 고정 범위

- **WHEN** `GET /users?ids=1,50,51`을 부른다
- **THEN** ID 1과 50의 프로필만 온다
