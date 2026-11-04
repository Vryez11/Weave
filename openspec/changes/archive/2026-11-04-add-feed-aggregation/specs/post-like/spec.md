# Spec Delta

## Purpose

like-service가 게시글의 좋아요 수와 사용자의 좋아요 상태를 저장하고 내준다. 피드에서 선택 등급 의존성이고, 정합성은 고도화 6에서 다룬다([PRD](../../../../../../docs/prd.md) 3장, 6장 F-003).

## ADDED Requirements

### Requirement: 사용자 식별 (F-006)

like-service는 사용자를 `X-User-Id` 헤더(양의 정수)로 식별해야 한다(SHALL). 이 헤더가 필요한 API에서 헤더가 없거나 양의 정수가 아니면 400을 돌려줘야 한다(SHALL).

#### Scenario: 헤더가 없다

- **WHEN** `X-User-Id` 없이 `GET /likes?postIds=1`이나 `POST /likes/1/toggle`을 부른다
- **THEN** 400이 온다

#### Scenario: 헤더 값이 잘못됐다

- **WHEN** `X-User-Id: abc`나 `X-User-Id: 0`으로 `POST /likes/1/toggle`을 부른다
- **THEN** 400이 온다

### Requirement: 좋아요 배치 조회 (F-006)

like-service는 `GET /likes?postIds=1,2,3`에 요청한 게시글마다 좋아요 수와 요청한 사용자의 좋아요 여부를 돌려줘야 한다(SHALL).

- 항목마다 `postId`, `likeCount`, `liked`(불리언)가 있다.
- 좋아요가 한 번도 없던 게시글도 `likeCount` 0, `liked` false로 온다.
- 같은 ID를 여러 번 보내도 항목은 한 번만 온다.
- `postIds`는 1~100개를 받는다. 게시글이 실제로 있는지는 확인하지 않는다.

#### Scenario: 좋아요가 없는 게시글

- **WHEN** 아무도 누르지 않은 게시글 7에 대해 `GET /likes?postIds=7`을 부른다
- **THEN** `postId` 7, `likeCount` 0, `liked` false가 온다

#### Scenario: 내 좋아요 여부와 전체 수

- **GIVEN** 사용자 1과 2가 게시글 3을 눌렀다
- **WHEN** 사용자 1이 `GET /likes?postIds=3,4`를 부른다
- **THEN** 게시글 3은 `likeCount` 2, `liked` true이고, 게시글 4는 `likeCount` 0, `liked` false다

#### Scenario: 사용자마다 다른 상태

- **GIVEN** 사용자 1만 게시글 3을 눌렀다
- **WHEN** 사용자 2가 `GET /likes?postIds=3`을 부른다
- **THEN** `likeCount` 1, `liked` false가 온다

#### Scenario: ID가 없거나 너무 많다

- **WHEN** `postIds` 없이, 또는 101개의 ID로 `GET /likes`를 부른다
- **THEN** 400이 온다

### Requirement: 좋아요 토글 (F-006)

like-service는 `POST /likes/{postId}/toggle`을 받을 때마다 요청한 사용자의 그 게시글 좋아요 상태를 뒤집고, 뒤집은 뒤의 `postId`, `liked`, `likeCount`를 돌려줘야 한다(SHALL). 같은 요청을 다시 보내면 다시 뒤집혀야 한다(SHALL). 고도화 6(F-003)에서 이 성질 때문에 생기는 문제를 재현한다.

#### Scenario: 처음 누른다

- **WHEN** 사용자 1이 아무도 누르지 않은 게시글 5에 `POST /likes/5/toggle`을 부른다
- **THEN** 200과 함께 `liked` true, `likeCount` 1이 온다

#### Scenario: 다시 누르면 취소된다

- **GIVEN** 사용자 1이 게시글 5를 눌렀다
- **WHEN** 사용자 1이 `POST /likes/5/toggle`을 한 번 더 부른다
- **THEN** `liked` false, `likeCount` 0이 온다

#### Scenario: 다른 사용자의 좋아요는 그대로다

- **GIVEN** 사용자 1과 2가 게시글 5를 눌렀다
- **WHEN** 사용자 1이 `POST /likes/5/toggle`을 부른다
- **THEN** `liked` false, `likeCount` 1이 오고, 사용자 2의 `liked`는 true로 남는다

#### Scenario: 잘못된 게시글 ID

- **WHEN** `postId`를 0이나 숫자가 아닌 값으로 `POST /likes/{postId}/toggle`을 부른다
- **THEN** 400이 온다

### Requirement: 좋아요 영속 (F-006)

like-service는 좋아요를 데이터베이스에 저장해, 서비스를 다시 띄워도 좋아요 상태와 수가 남아 있어야 한다(SHALL).

#### Scenario: 다시 띄운 뒤

- **GIVEN** 사용자 1이 게시글 5를 눌렀다
- **WHEN** 같은 데이터베이스로 like-service를 다시 띄우고 `GET /likes?postIds=5`를 부른다
- **THEN** `likeCount` 1, `liked` true가 온다
