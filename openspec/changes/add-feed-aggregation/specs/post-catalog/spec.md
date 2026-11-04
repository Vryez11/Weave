# Spec Delta

## Purpose

post-service가 피드의 뼈대인 최신 게시글 목록을 내준다. 피드에서 필수 등급 의존성이다([PRD](../../../../../docs/prd.md) 3장).

## ADDED Requirements

### Requirement: 최신 게시글 목록 조회 (F-006)

post-service는 `GET /posts`에 게시글을 작성 시각 최신순으로 돌려줘야 한다(SHALL).

- 게시글마다 `id`, `authorId`, `title`, `content`, `createdAt`(ISO-8601 UTC)이 있다.
- `size` 파라미터로 개수를 정한다. 기본값은 20이고 1~50만 받는다.
- 작성 시각이 같으면 `id`가 큰 것이 앞에 온다.

#### Scenario: 기본 개수로 조회

- **WHEN** `size` 없이 `GET /posts`를 부른다
- **THEN** 200과 함께 게시글 20개가 `createdAt` 내림차순으로 온다
- **AND** 게시글마다 `id`, `authorId`, `title`, `content`, `createdAt`이 있다

#### Scenario: 개수를 정해 조회

- **WHEN** `GET /posts?size=5`를 부른다
- **THEN** 최신 게시글 5개가 온다

#### Scenario: 범위를 벗어난 개수

- **WHEN** `size`를 0, 51, 또는 숫자가 아닌 값으로 `GET /posts`를 부른다
- **THEN** 400이 온다

### Requirement: 고정 데이터 (F-006)

post-service는 시작할 때 정해진 게시글 데이터를 만들고, 같은 버전이면 몇 번을 다시 띄워도 같은 데이터로 응답해야 한다(SHALL). 게시글 작성자는 모두 user-service의 고정 사용자 ID 범위 안에 있어야 한다(SHALL).

#### Scenario: 다시 띄워도 같은 데이터

- **WHEN** post-service를 두 번 따로 띄워 각각 `GET /posts?size=50`을 부른다
- **THEN** 두 응답이 같다

#### Scenario: 작성자 ID 범위

- **WHEN** `GET /posts?size=50`을 부른다
- **THEN** 모든 `authorId`가 1 이상 50 이하다
