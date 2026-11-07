## MODIFIED Requirements

### Requirement: 피드 응답 구성 (F-006)

feed-service는 `GET /feed`에 게시글 목록, 추천, 광고로 이루어진 피드를 돌려줘야 한다(SHALL).

- `posts`: 항목마다 `id`, `title`, `content`, `createdAt`, `author`, `like`가 있고, post-service가 준 순서를 지킨다.
  - `author`: `id`, `nickname`, `profileImageUrl`, `unknown`. 프로필을 알 수 없으면(`graceful-degradation`) `unknown`이 true이고 `nickname`, `profileImageUrl`은 null이다
  - `like`: `count`, `myLike`(`LIKED`, `NOT_LIKED`, `UNKNOWN`). 좋아요를 알 수 없으면 `count`는 null, `myLike`는 `UNKNOWN`이다
- `recommendations`: 항목마다 `postId`, `title`, `reason`
- `ads`: 항목마다 `id`, `title`, `imageUrl`, `landingUrl`
- `degradation`: 빠진 영역(`missing`)과 오래된 데이터로 채운 영역(`stale`). 형식은 `graceful-degradation`의 "부분 응답 표시"를 따른다

#### Scenario: 모든 하위 서비스가 정상

- **GIVEN** 하위 서비스 5개가 모두 정상으로 응답한다
- **WHEN** `X-User-Id: 1`로 `GET /feed`를 부른다
- **THEN** 200과 함께 post-service가 준 게시글이 같은 순서로 온다
- **AND** 게시글마다 작성자 프로필(`unknown` false)과 좋아요 수가 채워져 있고, 사용자 1이 누른 게시글은 `myLike`가 `LIKED`, 나머지는 `NOT_LIKED`다
- **AND** `recommendations`와 `ads`에 각 서비스가 준 항목이 온다
- **AND** `degradation.missing`과 `degradation.stale`은 빈 목록이다

#### Scenario: 작성자 프로필이 없다

- **GIVEN** user-service가 어떤 작성자의 프로필을 돌려주지 않는다
- **WHEN** `GET /feed`를 부른다
- **THEN** 그 작성자의 게시글은 `author`가 null이고, 나머지는 정상으로 온다
- **AND** `degradation.missing`에 `user`가 없다. user-service는 정상으로 응답했다

#### Scenario: 게시글이 없다

- **GIVEN** post-service가 빈 목록을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200과 함께 `posts`가 빈 목록으로 온다
- **AND** user-service와 like-service는 호출하지 않는다

### Requirement: 하위 서비스 호출 실패 로그 (F-004)

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

#### Scenario: 토글 전달이 실패한다

- **GIVEN** like-service가 토글에 503을 준다
- **WHEN** `POST /feed/likes/{postId}/toggle`을 부른다
- **THEN** feed-service가 `dependency` `like`, `cause` `http_503`인 `downstream_call_failed` 줄을 남긴다

#### Scenario: 호출 시간 상한을 넘긴다

- **GIVEN** recommendation의 호출 시간 상한이 200ms이고 recommendation-service가 1초 늦게 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** `dependency` `recommendation`, `cause` `timeout`인 `downstream_call_failed` 줄이 하나 있다. 시간 상한이 지난 뒤 응답 타임아웃으로 끝난 같은 호출의 줄을 하나 더 남기지 않는다

## REMOVED Requirements

### Requirement: 하위 서비스 실패는 시간 안에 피드 실패로 끝난다 (F-001)

**Reason:** 고도화 4(F-002)에서 하위 서비스 실패를 의존성 등급대로 대체한다. 중요·선택 등급의 실패는 더는 피드를 실패시키지 않고, post 실패는 stale 캐시로 대체하거나 503으로 끝난다.

**Migration:** 실패 응답은 `graceful-degradation`의 요구사항이 정한다. "실패한 의존성에는 한 번만 요청한다"와 "ad 429에 한 번만 요청한다"는 같은 capability의 "중요·선택 등급의 실패는 피드를 실패시키지 않는다", "선택 영역 빼기" 시나리오로 옮겼다. 느린 의존성을 시간 상한 넘겨 기다리지 않는 것은 `call-isolation`이 계속 정한다.
