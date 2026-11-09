# post-like Specification

## Purpose

like-service가 게시글의 좋아요 수와 사용자의 좋아요 상태를 저장하고 내준다. 피드에서 선택 등급 의존성이고, 상태 기반 API와 `version` 순서 판단으로 재시도·중복·순서 뒤바뀜에도 사용자의 마지막 의도를 남긴다([PRD](../../../docs/prd.md) 3장, 6장 F-003).

## Requirements

### Requirement: 사용자 식별 (F-006)

like-service는 사용자를 `X-User-Id` 헤더(양의 정수)로 식별해야 한다(SHALL). 이 헤더가 필요한 API에서 헤더가 없거나 양의 정수가 아니면 400을 돌려줘야 한다(SHALL).

#### Scenario: 헤더가 없다

- **WHEN** `X-User-Id` 없이 `GET /likes?postIds=1`이나 `PUT /likes/1`을 부른다
- **THEN** 400이 온다

#### Scenario: 헤더 값이 잘못됐다

- **WHEN** `X-User-Id: abc`나 `X-User-Id: 0`으로 `PUT /likes/1`을 부른다
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

### Requirement: 좋아요 영속 (F-006)

like-service는 좋아요를 데이터베이스에 저장해, 서비스를 다시 띄워도 좋아요 상태와 수가 남아 있어야 한다(SHALL).

#### Scenario: 다시 띄운 뒤

- **GIVEN** 사용자 1이 게시글 5를 눌렀다
- **WHEN** 같은 데이터베이스로 like-service를 다시 띄우고 `GET /likes?postIds=5`를 부른다
- **THEN** `likeCount` 1, `liked` true가 온다

### Requirement: 좋아요 상태 지정 (F-003)

like-service는 `PUT /likes/{postId}`에 본문 `{"liked": 불리언, "version": 양의 정수}`를 받아 요청한 사용자의 그 게시글 좋아요 상태를 `liked`로 만들고, 처리 뒤의 `postId`, `liked`, `likeCount`를 200으로 돌려줘야 한다(SHALL). `version`은 클라이언트가 의도를 만들 때 정하고 재전송에도 그대로 쓰는 순서 값이다. 본문이 없거나 필드가 빠졌거나 `version`이 양의 정수가 아니면, 또는 `postId`가 양의 정수가 아니면 상태를 바꾸지 않고 400을 돌려줘야 한다(SHALL).

#### Scenario: 처음 누른다

- **WHEN** 사용자 1이 아무도 누르지 않은 게시글 5에 `PUT /likes/5`를 `liked` true, `version` 1로 부른다
- **THEN** 200과 함께 `postId` 5, `liked` true, `likeCount` 1이 온다

#### Scenario: 취소한다

- **GIVEN** 사용자 1이 게시글 5를 `version` 1로 눌렀다
- **WHEN** 사용자 1이 `PUT /likes/5`를 `liked` false, `version` 2로 부른다
- **THEN** `liked` false, `likeCount` 0이 온다

#### Scenario: 다른 사용자의 좋아요는 그대로다

- **GIVEN** 사용자 1과 2가 게시글 5를 눌렀다
- **WHEN** 사용자 1이 더 큰 `version`으로 `liked` false를 보낸다
- **THEN** `liked` false, `likeCount` 1이 오고, 사용자 2의 `GET /likes?postIds=5`는 `liked` true다

#### Scenario: 누른 적 없는 게시글을 취소한다

- **WHEN** 사용자 1이 아무도 누르지 않은 게시글 5에 `liked` false, `version` 1을 보낸다
- **THEN** `liked` false, `likeCount` 0이 온다

#### Scenario: 본문이 잘못됐다

- **WHEN** 본문 없이, `liked`나 `version` 없이, 또는 `version`이 0이나 숫자가 아닌 값으로 `PUT /likes/5`를 부른다
- **THEN** 400이 오고 게시글 5의 좋아요 수는 그대로다

#### Scenario: 잘못된 게시글 ID

- **WHEN** `postId`를 0이나 숫자가 아닌 값으로 `PUT /likes/{postId}`를 부른다
- **THEN** 400이 온다

#### Scenario: 토글 API는 없다

- **WHEN** `POST /likes/5/toggle`을 부른다
- **THEN** 2xx가 오지 않고 게시글 5의 좋아요 수는 그대로다

### Requirement: 같은 요청의 반복 (F-003)

like-service는 같은 사용자·게시글에 같은 요청을 몇 번 받아도 한 번 받은 것과 같은 상태와 수로 남겨야 한다(SHALL). 반복된 요청에도 200과 지금 상태를 돌려준다.

#### Scenario: 같은 요청을 다시 보낸다

- **GIVEN** 사용자 1이 게시글 5에 `liked` true, `version` 1을 보냈다
- **WHEN** 같은 요청을 네 번 더 보낸다
- **THEN** 모두 200과 `liked` true, `likeCount` 1이 온다

#### Scenario: 이미 그 상태인데 더 큰 version이 온다

- **GIVEN** 사용자 1이 게시글 5를 `version` 1로 눌렀다
- **WHEN** 사용자 1이 `liked` true, `version` 2를 보낸다
- **THEN** `liked` true, `likeCount` 1이 온다

### Requirement: 동시 요청의 정합성 (F-003)

like-service는 같은 사용자의 같은 요청이 동시에 와도 좋아요를 하나만 남기고, 서로 다른 사용자가 동시에 누르면 좋아요 수를 `liked` 상태인 행 수와 같게 둬야 한다(SHALL). 동시 처리로 생긴 잠금 충돌은 클라이언트에게 5xx로 보이지 않아야 한다(SHALL).

#### Scenario: 같은 사용자의 같은 요청이 동시에 온다

- **WHEN** 사용자 1이 게시글 5에 같은 요청(`liked` true, `version` 1)을 동시에 20개 보낸다
- **THEN** 모두 200이고, `liked` true, `likeCount` 1이며, `liked` 상태인 행은 하나다

#### Scenario: 서로 다른 사용자가 동시에 누른다

- **WHEN** 서로 다른 사용자 50명이 게시글 5에 `liked` true를 동시에 보낸다
- **THEN** 모두 200이고, `likeCount`는 50이며, 불일치 검사에 게시글 5가 나오지 않는다

### Requirement: 늦게 도착한 요청 (F-003)

like-service는 사용자·게시글마다 지금까지 반영한 가장 큰 `version`을 기억하고, 그 값보다 크지 않은 `version`의 요청은 상태를 바꾸지 않고 200과 지금 상태를 돌려줘야 한다(SHALL). 취소한 뒤에도 그 값을 잊지 않아야 한다(SHALL).

#### Scenario: 취소 뒤에 첫 요청의 재시도가 도착한다

- **GIVEN** 사용자 1이 게시글 5에 `liked` true `version` 1, `liked` false `version` 2를 차례로 보냈다
- **WHEN** `liked` true, `version` 1이 다시 도착한다
- **THEN** 200과 `liked` false, `likeCount` 0이 온다

#### Scenario: 먼저 보낸 요청이 나중에 도착한다

- **WHEN** 사용자 1의 `liked` false `version` 2가 먼저, `liked` true `version` 1이 나중에 도착한다
- **THEN** 최종 상태는 `liked` false, `likeCount` 0이다

#### Scenario: 어떤 순서로 몇 번이 와도 마지막 의도로 끝난다

- **GIVEN** 서로 다른 `version`을 가진 요청들의 임의 순서와 임의 중복
- **WHEN** 그 순서대로 보낸다
- **THEN** 최종 상태는 `version`이 가장 큰 요청의 `liked`이고, `likeCount`는 `liked` 상태인 행 수와 같다

#### Scenario: 같은 version에 다른 값이 온다

- **GIVEN** 사용자 1이 게시글 5에 `liked` true, `version` 3을 보냈다
- **WHEN** `liked` false, `version` 3이 온다
- **THEN** 200과 `liked` true, `likeCount` 1이 온다

### Requirement: 좋아요 수 불일치 검사 (F-003)

like-service는 `GET /actuator/likeconsistency`에 저장된 좋아요 수와 `liked` 상태인 행 수가 다른 게시글을 `mismatches` 목록(`postId`, `likeCount`, `likedRows`)으로 돌려줘야 한다(SHALL). `postId` 쿼리를 주면 그 게시글만 본다. 이 엔드포인트는 값을 고치지 않는다.

#### Scenario: 어긋남이 없다

- **GIVEN** 사용자 1과 2가 게시글 5를 눌렀다
- **WHEN** `GET /actuator/likeconsistency`를 부른다
- **THEN** `mismatches`는 비어 있다

#### Scenario: 어긋남이 있다

- **GIVEN** 게시글 5의 저장된 좋아요 수가 3이고 `liked` 상태인 행은 2개다
- **WHEN** `GET /actuator/likeconsistency?postId=5`를 부른다
- **THEN** `mismatches`에 `postId` 5, `likeCount` 3, `likedRows` 2가 있다

#### Scenario: 행은 있는데 저장된 수가 없다

- **GIVEN** 게시글 5에 `liked` 상태인 행이 1개 있고 저장된 좋아요 수가 없다
- **WHEN** `GET /actuator/likeconsistency`를 부른다
- **THEN** `mismatches`에 `postId` 5, `likeCount` 0, `likedRows` 1이 있다

#### Scenario: 취소한 행은 세지 않는다

- **GIVEN** 사용자 1이 게시글 5를 눌렀다가 취소했다
- **WHEN** `GET /actuator/likeconsistency?postId=5`를 부른다
- **THEN** `mismatches`는 비어 있다

### Requirement: 좋아요 수 갱신 방식 (F-003)

like-service는 좋아요 수를 고치는 방식을 설정으로 읽고 고쳐 저장하기, 원자적 UPDATE, 카운터 분할 중에서 골라야 한다(SHALL). 원자적 UPDATE와 카운터 분할은 동시 요청에서도 수가 행 수와 같아야 하고(SHALL), 읽고 고쳐 저장하기는 비교 기준선이라 이 보장이 없다. 어느 방식이든 조회와 응답의 `likeCount`는 게시글의 전체 수여야 한다(SHALL).

#### Scenario: 원자적 UPDATE로 몰린다

- **GIVEN** 갱신 방식이 원자적 UPDATE다
- **WHEN** 서로 다른 사용자 50명이 게시글 5에 동시에 누른다
- **THEN** `GET /likes?postIds=5`의 `likeCount`는 50이다

#### Scenario: 카운터 분할로 몰린다

- **GIVEN** 갱신 방식이 카운터 분할이다
- **WHEN** 서로 다른 사용자 50명이 게시글 5에 동시에 누르고, 그중 10명이 취소한다
- **THEN** `GET /likes?postIds=5`의 `likeCount`는 40이고, 불일치 검사에 게시글 5가 나오지 않는다

#### Scenario: 방식을 바꿔도 수가 이어진다

- **GIVEN** 원자적 UPDATE로 게시글 5의 좋아요가 3개 쌓였다
- **WHEN** 갱신 방식을 카운터 분할로 바꿔 다시 띄우고 다른 사용자가 게시글 5를 누른다
- **THEN** `likeCount`는 4다
