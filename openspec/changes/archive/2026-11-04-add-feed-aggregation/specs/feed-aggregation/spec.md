# Spec Delta

## Purpose

feed-service가 하위 서비스 5개를 불러 피드 하나를 만들고, 클라이언트의 좋아요 요청을 like-service로 넘긴다. 클라이언트는 feed-service만 부른다. 의존성 등급과 호출 흐름은 [PRD](../../../../../../docs/prd.md) 3장을 따른다.

## ADDED Requirements

### Requirement: 피드 응답 구성 (F-006)

feed-service는 `GET /feed`에 게시글 목록, 추천, 광고로 이루어진 피드를 돌려줘야 한다(SHALL).

- `posts`: 항목마다 `id`, `title`, `content`, `createdAt`, `author`, `like`가 있고, post-service가 준 순서를 지킨다.
  - `author`: `id`, `nickname`, `profileImageUrl`
  - `like`: `count`, `myLike`(`LIKED` 또는 `NOT_LIKED`)
- `recommendations`: 항목마다 `postId`, `title`, `reason`
- `ads`: 항목마다 `id`, `title`, `imageUrl`, `landingUrl`

#### Scenario: 모든 하위 서비스가 정상

- **GIVEN** 하위 서비스 5개가 모두 정상으로 응답한다
- **WHEN** `X-User-Id: 1`로 `GET /feed`를 부른다
- **THEN** 200과 함께 post-service가 준 게시글이 같은 순서로 온다
- **AND** 게시글마다 작성자 프로필과 좋아요 수가 채워져 있고, 사용자 1이 누른 게시글은 `myLike`가 `LIKED`, 나머지는 `NOT_LIKED`다
- **AND** `recommendations`와 `ads`에 각 서비스가 준 항목이 온다

#### Scenario: 작성자 프로필이 없다

- **GIVEN** user-service가 어떤 작성자의 프로필을 돌려주지 않는다
- **WHEN** `GET /feed`를 부른다
- **THEN** 그 작성자의 게시글은 `author`가 null이고, 나머지는 정상으로 온다

#### Scenario: 게시글이 없다

- **GIVEN** post-service가 빈 목록을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200과 함께 `posts`가 빈 목록으로 온다
- **AND** user-service와 like-service는 호출하지 않는다

### Requirement: 피드 요청 검증 (F-006)

feed-service는 `GET /feed`에서 `X-User-Id` 헤더(양의 정수)를 요구하고, `size`(게시글 수, 기본 20, 1~50)를 받아야 한다(SHALL). 검증에 실패하면 하위 서비스를 부르지 않고 400을 돌려줘야 한다(SHALL).

#### Scenario: 사용자 헤더가 없다

- **WHEN** `X-User-Id` 없이 `GET /feed`를 부른다
- **THEN** 400이 오고, 하위 서비스는 하나도 호출하지 않는다

#### Scenario: 개수를 정한다

- **WHEN** `GET /feed?size=5`를 부른다
- **THEN** post-service에 `size=5`로 게시글을 요청한다

#### Scenario: 범위를 벗어난 개수

- **WHEN** `size`를 0이나 51로 `GET /feed`를 부른다
- **THEN** 400이 오고, 하위 서비스는 하나도 호출하지 않는다

### Requirement: 하위 서비스 호출 순서 (F-006)

feed-service는 post, recommendation, ad를 서로 기다리지 않고 동시에 불러야 한다(SHALL). user와 like는 post 응답을 받은 뒤 서로 기다리지 않고 동시에 불러야 한다(SHALL).

- user에는 게시글 작성자 ID를 중복 없이 넘긴다.
- like에는 게시글 ID와 요청한 사용자의 `X-User-Id`를 넘긴다.

#### Scenario: post 결과로 user와 like를 부른다

- **GIVEN** post-service가 게시글 10, 11, 12(작성자 3, 3, 4)를 준다
- **WHEN** `X-User-Id: 1`로 `GET /feed`를 부른다
- **THEN** user-service에 작성자 ID 3, 4로 한 번 요청한다
- **AND** like-service에 게시글 ID 10, 11, 12와 `X-User-Id: 1`로 한 번 요청한다

#### Scenario: 서로 기다리지 않는다

- **GIVEN** post, recommendation, ad, user, like가 각각 400ms 늦게 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** 피드가 1,200ms 안에 온다. 모두 차례로 불렀다면 2,000ms가 걸린다

### Requirement: 보호 장치 없는 실패 전파 (F-006)

feed-service는 하위 서비스 호출에 직접 정한 타임아웃(라이브러리 기본값만 쓴다), 서킷 브레이커, 벌크헤드, 재시도, fallback을 두지 않아야 한다(SHALL). HTTP 클라이언트 라이브러리가 스스로 하는 재시도도 재시도에 든다. 하위 서비스 하나라도 실패하면 피드 요청 전체가 500으로 실패하고, 느리면 피드도 그만큼 기다려야 한다(SHALL). 이 상태가 고도화 2의 기준선이고, 고도화 3·4·5에서 이 요구사항을 바꾼다.

#### Scenario: 하위 서비스 하나가 5xx

- **GIVEN** post, user, like, recommendation, ad 중 하나가 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 500이 온다. 다섯 의존성 각각에 대해 같다
- **AND** 실패한 의존성에는 한 번만 요청한다

#### Scenario: 하위 서비스가 속도 조절 응답을 준다

- **GIVEN** ad-service가 429와 `Retry-After: 1`을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 500이 오고, ad-service에는 한 번만 요청한다

#### Scenario: 하위 서비스 하나에 연결할 수 없다

- **GIVEN** recommendation-service의 주소에 아무것도 떠 있지 않다
- **WHEN** `GET /feed`를 부른다
- **THEN** 500이 온다

#### Scenario: 하위 서비스 하나가 느리다

- **GIVEN** recommendation-service가 2초 늦게 응답하고 나머지는 정상이다
- **WHEN** `GET /feed`를 부른다
- **THEN** 2초 넘게 기다린 뒤 200과 함께 추천까지 채워진 피드가 온다

### Requirement: 좋아요 토글 전달 (F-006)

feed-service는 `POST /feed/likes/{postId}/toggle`을 받으면 같은 게시글과 `X-User-Id`로 like-service의 토글을 부르고, like-service가 준 `postId`, `liked`, `likeCount`를 그대로 돌려줘야 한다(SHALL). `X-User-Id`나 `postId`가 양의 정수가 아니면 like-service를 부르지 않고 400을 돌려줘야 한다(SHALL).

#### Scenario: 토글을 넘긴다

- **GIVEN** like-service가 토글에 `liked` true, `likeCount` 3을 준다
- **WHEN** `X-User-Id: 1`로 `POST /feed/likes/5/toggle`을 부른다
- **THEN** like-service에 `POST /likes/5/toggle`과 `X-User-Id: 1`로 한 번 요청한다
- **AND** 200과 함께 `postId` 5, `liked` true, `likeCount` 3이 온다

#### Scenario: 사용자 헤더가 없다

- **WHEN** `X-User-Id` 없이 `POST /feed/likes/5/toggle`을 부른다
- **THEN** 400이 오고, like-service는 호출하지 않는다

#### Scenario: 사용자 헤더 값이 잘못됐다

- **WHEN** `X-User-Id: 0`이나 `X-User-Id: abc`로 `POST /feed/likes/5/toggle`을 부른다
- **THEN** 400이 오고, like-service는 호출하지 않는다

#### Scenario: like-service가 토글에 실패한다

- **GIVEN** like-service가 토글에 503을 준다
- **WHEN** `POST /feed/likes/5/toggle`을 부른다
- **THEN** 500이 오고, like-service에는 토글을 한 번만 요청한다. 토글은 다시 보내면 상태가 또 뒤집히므로 두 번 보내면 안 된다

### Requirement: 종단 간 동작 (F-006)

서비스 6개를 각자 프로세스로 띄우고 feed-service가 나머지 다섯을 가리키게 하면, 클라이언트가 feed-service만 불러 피드를 받고 좋아요를 누를 수 있어야 한다(SHALL).

#### Scenario: 피드를 받고 좋아요를 누른다

- **GIVEN** 서비스 6개와 like-service의 데이터베이스가 떠 있다
- **WHEN** 사용자 1이 `GET /feed`를 부르고, 첫 게시글에 `POST /feed/likes/{postId}/toggle`을 부른 뒤, 다시 `GET /feed`를 부른다
- **THEN** 첫 피드는 게시글 20개, 추천, 광고가 채워진 200이다
- **AND** 토글 응답은 `liked` true다
- **AND** 두 번째 피드에서 그 게시글의 `myLike`가 `LIKED`이고 `count`가 1 늘었다
