# Spec Delta

## ADDED Requirements

### Requirement: 좋아요 상태 전달 (F-003)

feed-service는 `PUT /feed/likes/{postId}`에 본문 `{"liked": 불리언, "version": 양의 정수}`를 받으면 같은 게시글, `X-User-Id`, 본문으로 like-service의 상태 지정을 한 번 부르고, like-service가 준 `postId`, `liked`, `likeCount`를 그대로 돌려줘야 한다(SHALL). `X-User-Id`, `postId`, 본문이 잘못됐으면 like-service를 부르지 않고 400을 돌려줘야 한다(SHALL).

#### Scenario: 상태를 넘긴다

- **GIVEN** like-service가 상태 지정에 `liked` true, `likeCount` 3을 준다
- **WHEN** `X-User-Id: 1`로 `PUT /feed/likes/5`를 `liked` true, `version` 7로 부른다
- **THEN** like-service에 `PUT /likes/5`와 `X-User-Id: 1`, 같은 `liked`와 `version`으로 한 번 요청한다
- **AND** 200과 함께 `postId` 5, `liked` true, `likeCount` 3이 온다

#### Scenario: 사용자 헤더가 없다

- **WHEN** `X-User-Id` 없이 `PUT /feed/likes/5`를 부른다
- **THEN** 400이 오고, like-service는 호출하지 않는다

#### Scenario: 사용자 헤더 값이 잘못됐다

- **WHEN** `X-User-Id: 0`이나 `X-User-Id: abc`로 `PUT /feed/likes/5`를 부른다
- **THEN** 400이 오고, like-service는 호출하지 않는다

#### Scenario: 본문이 잘못됐다

- **WHEN** 본문 없이, `liked`나 `version` 없이, 또는 `version` 0으로 `PUT /feed/likes/5`를 부른다
- **THEN** 400이 오고, like-service는 호출하지 않는다

#### Scenario: like-service가 상태 변경에 실패한다

- **GIVEN** like-service가 상태 변경에 503을 준다
- **WHEN** `PUT /feed/likes/5`를 부른다
- **THEN** 500이 오고, like-service에는 한 번만 요청한다

#### Scenario: 토글 API는 없다

- **WHEN** `POST /feed/likes/5/toggle`을 부른다
- **THEN** 2xx가 오지 않고 like-service는 호출하지 않는다

### Requirement: 하위 서비스 호출 실패를 로그로 남긴다 (F-004)

feed-service는 하위 서비스 호출이 실패할 때마다 로그를 한 줄 남겨야 한다(SHALL). 필드는 `event`(`downstream_call_failed`), `dependency`(`post`, `user`, `like`, `recommendation`, `ad` 중 하나), `cause`, `exception`(예외 클래스 이름)이고, 피드 요청의 `traceId`가 함께 있다. `cause`의 값은 "하위 서비스 호출 실패 원인"을 따른다. 예외 메시지와 스택 트레이스는 남기지 않는다. 클라이언트에 주는 응답은 바뀌지 않는다.

#### Scenario: 하위 서비스가 5xx

- **GIVEN** recommendation-service가 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 응답은 200이고 `recommendations`는 빈 목록이다
- **AND** feed-service가 `event` `downstream_call_failed`, `dependency` `recommendation`, `cause` `http_503`인 줄을 하나 남긴다
- **AND** 그 줄의 `traceId`가 응답의 `X-Trace-Id`와 같다

#### Scenario: 하위 서비스에 연결할 수 없다

- **GIVEN** recommendation 주소가 아무도 듣지 않는 포트다
- **WHEN** `GET /feed`를 부른다
- **THEN** feed-service가 `dependency` `recommendation`, `cause` `connect_failure`인 `downstream_call_failed` 줄을 남긴다

#### Scenario: 하위 서비스의 호스트 이름을 찾을 수 없다

- **GIVEN** recommendation 주소의 호스트가 `weave-unresolvable.invalid`다
- **WHEN** `GET /feed`를 부른다
- **THEN** feed-service가 `dependency` `recommendation`, `cause` `connect_failure`인 `downstream_call_failed` 줄을 남긴다

#### Scenario: post 뒤에 부른 호출이 실패한다

- **GIVEN** like-service가 500을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** `dependency` `like`, `cause` `http_500`인 `downstream_call_failed` 줄의 `traceId`가 응답의 `X-Trace-Id`와 같다

#### Scenario: 좋아요 상태 전달이 실패한다

- **GIVEN** like-service가 상태 변경에 503을 준다
- **WHEN** `PUT /feed/likes/{postId}`를 부른다
- **THEN** feed-service가 `dependency` `like`, `cause` `http_503`인 `downstream_call_failed` 줄을 남긴다

#### Scenario: 호출 시간 상한을 넘긴다

- **GIVEN** recommendation의 호출 시간 상한이 200ms이고 recommendation-service가 1초 늦게 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** `dependency` `recommendation`, `cause` `timeout`인 `downstream_call_failed` 줄이 하나 있다. 시간 상한이 지난 뒤 응답 타임아웃으로 끝난 같은 호출의 줄을 하나 더 남기지 않는다

## MODIFIED Requirements

### Requirement: 종단 간 동작 (F-006)

서비스 6개를 각자 프로세스로 띄우고 feed-service가 나머지 다섯을 가리키게 하면, 클라이언트가 feed-service만 불러 피드를 받고 좋아요를 누를 수 있어야 한다(SHALL).

#### Scenario: 피드를 받고 좋아요를 누른다

- **GIVEN** 서비스 6개와 like-service의 데이터베이스가 떠 있다
- **WHEN** 사용자 1이 `GET /feed`를 부르고, 첫 게시글에 `PUT /feed/likes/{postId}`를 `liked` true, `version` 1로 부른 뒤, 다시 `GET /feed`를 부른다
- **THEN** 첫 피드는 게시글 20개, 추천, 광고가 채워진 200이다
- **AND** 상태 변경 응답은 `liked` true다
- **AND** 두 번째 피드에서 그 게시글의 `myLike`가 `LIKED`이고 `count`가 1 늘었다

## REMOVED Requirements

### Requirement: 하위 서비스 호출 실패 로그 (F-004)

**Reason**: 시나리오 하나가 없어지는 토글을 가리켜 시나리오 이름까지 바꾼다. 내용은 같고 토글 시나리오만 상태 전달로 바뀐다.
**Migration**: "하위 서비스 호출 실패를 로그로 남긴다"를 따른다.

### Requirement: 좋아요 토글 전달 (F-006)

**Reason**: 토글 API를 없앤다(F-003).
**Migration**: `PUT /feed/likes/{postId}`에 `{"liked": 불리언, "version": 양의 정수}`를 보낸다.
