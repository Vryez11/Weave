# graceful-degradation Specification

## Purpose
feed-service가 하위 서비스 호출 실패를 의존성 등급대로 대체해, 필수 등급(post)을 대체할 수 없을 때만 피드가 실패하고 나머지 실패는 그 영역만 빠지거나 대체 데이터로 채운 응답이 되게 한다. 등급과 대체 방법, 판정 기준은 [PRD](../../../docs/prd.md) 3장, F-002와 4장 S-02~S-06을 따른다.

## Requirements

### Requirement: 부분 응답 표시 (F-002)

`GET /feed`의 200 응답에는 늘 `degradation`이 있어야 한다(SHALL). `missing`은 빠진 영역의 의존성 이름 목록(`user`, `like`, `recommendation`, `ad`), `stale`은 오래된 대체 데이터로 채운 영역의 목록이고 항목마다 `section`(의존성 이름)과 `cachedAt`(그 데이터를 받은 시각)이 있다. 모든 하위 서비스가 정상이면 둘 다 빈 목록이다.

#### Scenario: 모든 하위 서비스가 정상

- **GIVEN** 하위 서비스 5개가 모두 정상으로 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200이고 `degradation.missing`과 `degradation.stale`이 빈 목록이다

#### Scenario: 여러 영역이 빠진다

- **GIVEN** like-service와 ad-service가 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200이고 `degradation.missing`은 `like`, `ad` 두 개다(순서는 상관없다)

### Requirement: 중요·선택 등급의 실패는 피드를 실패시키지 않는다 (F-002)

user, like, recommendation, ad 호출이 실패해도(5xx, 4xx, 연결 실패, 호출 시간 상한 초과, 서킷 열림, 벌크헤드 거절, 요청 시간 예산 초과) `GET /feed`는 200이어야 한다(SHALL). 실패한 의존성은 등급대로 대체하고, 다시 부르지 않는다. 재시도는 F-005에서 정한다.

#### Scenario: 중요·선택 등급 하나가 5xx

- **GIVEN** user, like, recommendation, ad 중 하나가 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200이 온다. 네 의존성 각각에 대해 같다
- **AND** 실패한 의존성에는 한 번만 요청한다

#### Scenario: 중요·선택 등급이 모두 실패한다

- **GIVEN** post만 정상이고 user, like, recommendation, ad가 모두 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200과 함께 post-service가 준 게시글이 같은 순서로 온다
- **AND** `degradation.missing`은 `user`, `like`, `recommendation`, `ad` 네 개다

#### Scenario: 느린 선택 의존성

- **GIVEN** recommendation의 호출 시간 상한이 200ms이고, recommendation-service가 2초 늦게 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** 400ms 안에 200이 오고 `degradation.missing`에 `recommendation`이 있다

#### Scenario: 서킷이 열린 선택 의존성

- **GIVEN** recommendation 서킷이 열려 있다
- **WHEN** `GET /feed`를 부른다
- **THEN** recommendation-service는 요청을 받지 않고, 200이 오며 `degradation.missing`에 `recommendation`이 있다

### Requirement: 선택 영역 빼기 (F-002)

recommendation이나 ad 호출이 실패하면 그 영역(`recommendations`, `ads`)을 빈 목록으로 두고 `degradation.missing`에 그 의존성을 넣어야 한다(SHALL). 나머지 영역은 그대로다.

#### Scenario: 추천이 실패한다

- **GIVEN** recommendation-service가 503을 주고 나머지는 정상이다
- **WHEN** `GET /feed`를 부른다
- **THEN** `recommendations`는 빈 목록이고 `degradation.missing`은 `recommendation` 하나다
- **AND** 게시글, 작성자, 좋아요, 광고는 모두 정상으로 채워져 있다

#### Scenario: 광고가 속도 조절 응답을 준다

- **GIVEN** ad-service가 429와 `Retry-After: 1`을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200이고 `ads`는 빈 목록이며 `degradation.missing`은 `ad` 하나다
- **AND** ad-service에는 한 번만 요청한다

### Requirement: 좋아요 상태를 모름으로 내린다 (F-002)

like 호출이 실패하면 모든 게시글의 `like.count`를 null로, `like.myLike`를 `UNKNOWN`으로 두고 `degradation.missing`에 `like`를 넣어야 한다(SHALL). 클라이언트는 `UNKNOWN`이면 좋아요 버튼을 비활성화한다.

#### Scenario: 좋아요 조회가 실패한다

- **GIVEN** like-service가 500을 주고 나머지는 정상이다
- **WHEN** `X-User-Id: 1`로 `GET /feed`를 부른다
- **THEN** 200이고 모든 게시글의 `like.count`가 null, `like.myLike`가 `UNKNOWN`이다
- **AND** `degradation.missing`은 `like` 하나다

### Requirement: 작성자 프로필을 로컬 캐시로 채운다 (F-002)

feed-service는 user 호출이 성공할 때마다 받은 프로필을 로컬 캐시에 넣고, user 호출이 실패하면 캐시에 있는 작성자는 캐시의 프로필로, 없는 작성자는 `author.unknown` true(`nickname`, `profileImageUrl`은 null)로 채워야 한다(SHALL). 캐시로 모두 채웠으면 빠진 영역이 아니고, 한 명이라도 채우지 못했으면 `degradation.missing`에 `user`를 넣는다. 정상 응답의 `author.unknown`은 false다.

#### Scenario: 캐시에 모든 작성자가 있다

- **GIVEN** 앞선 피드 요청에서 작성자 3, 4의 프로필을 받았고, 지금은 user-service가 503을 준다
- **WHEN** 작성자가 3, 4인 게시글로 `GET /feed`를 부른다
- **THEN** 200이고 작성자 3, 4는 앞서 받은 `nickname`, `profileImageUrl`로 채워져 있고 `unknown`은 false다
- **AND** `degradation.missing`에 `user`가 없다

#### Scenario: 캐시에 없는 작성자가 있다

- **GIVEN** 앞선 피드 요청에서 작성자 3의 프로필만 받았고, 지금은 user-service가 503을 준다
- **WHEN** 작성자가 3, 4인 게시글로 `GET /feed`를 부른다
- **THEN** 작성자 3은 캐시의 프로필이고, 작성자 4는 `id` 4, `unknown` true, `nickname`과 `profileImageUrl`이 null이다
- **AND** `degradation.missing`에 `user`가 있다

#### Scenario: 정상일 때는 캐시를 쓰지 않는다

- **GIVEN** 앞선 피드 요청에서 작성자 3의 `nickname`이 "old"였고, 지금 user-service는 정상으로 "new"를 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 작성자 3의 `nickname`은 "new"다
- **AND** 그 뒤 user-service가 503을 주면 작성자 3의 `nickname`은 "new"다

#### Scenario: 캐시 유효 기간이 지났다

- **GIVEN** user 캐시의 유효 기간이 1초이고, 작성자 3의 프로필을 받은 지 1초가 넘었으며, 지금은 user-service가 503을 준다
- **WHEN** 작성자 3의 게시글로 `GET /feed`를 부른다
- **THEN** 작성자 3은 `unknown` true이고 `degradation.missing`에 `user`가 있다

### Requirement: 게시글을 stale 캐시로 대체한다 (F-002)

feed-service는 post 호출이 성공할 때마다 그 결과와 받은 시각을 같은 `size` 요청의 캐시로 남기고, post 호출이 실패하면 같은 `size`의 캐시가 stale 허용 시간(10분) 안의 것일 때 그 게시글로 피드를 만들어야 한다(SHALL). 이때 `degradation.stale`에 `section` `post`와 `cachedAt`을 넣고, user와 like는 캐시의 게시글로 평소처럼 부른다.

#### Scenario: 10분 안의 캐시가 있다

- **GIVEN** 5분 전에 `size=20` 피드 요청에서 post-service가 게시글 10, 11, 12를 줬고, 지금은 post-service가 503을 준다
- **WHEN** `GET /feed?size=20`을 부른다
- **THEN** 200과 함께 게시글 10, 11, 12가 같은 순서로 온다
- **AND** `degradation.stale`은 `section` `post` 하나이고 `cachedAt`은 5분 전 그 응답을 받은 시각이다
- **AND** user-service와 like-service는 게시글 10, 11, 12의 작성자와 ID로 요청을 받는다

#### Scenario: post 서킷이 열려 있다

- **GIVEN** 1분 전의 `size=20` 캐시가 있고 post 서킷이 열려 있다
- **WHEN** `GET /feed?size=20`을 부른다
- **THEN** post-service는 요청을 받지 않고, 200과 함께 캐시의 게시글이 stale로 온다

#### Scenario: 다른 개수의 캐시는 쓰지 않는다

- **GIVEN** 1분 전의 `size=20` 캐시만 있고 post-service가 503을 준다
- **WHEN** `GET /feed?size=5`를 부른다
- **THEN** 503이 온다

### Requirement: 게시글을 대체할 수 없으면 503 (F-002)

post 호출이 실패하고 쓸 수 있는 캐시가 없으면(캐시가 없거나, stale 허용 시간이 지났거나, 캐시를 읽지 못했으면) `GET /feed`는 503이어야 한다(SHALL). recommendation과 ad를 기다리지 않고 바로 응답하며, 피드 요청의 시간 예산 안에 응답한다. 503 응답에도 `X-Trace-Id`가 있다.

#### Scenario: 캐시가 없다

- **GIVEN** post 캐시가 비어 있고 post-service가 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 503이 오고 `X-Trace-Id` 헤더가 있다

#### Scenario: 캐시가 10분을 넘었다

- **GIVEN** 11분 전의 `size=20` 캐시만 있고 post-service가 503을 준다
- **WHEN** `GET /feed?size=20`을 부른다
- **THEN** 503이 온다

#### Scenario: 503도 시간 안에 나간다

- **GIVEN** post 캐시가 비어 있고, post의 호출 시간 상한이 100ms, 시간 예산이 250ms이며, post-service가 2초 늦게 응답하고 recommendation-service도 2초 늦게 응답한다
- **WHEN** `GET /feed`를 부른다
- **THEN** 300ms 안에 503이 온다

### Requirement: 대체 수단의 장애는 피드를 막지 않는다 (F-002)

post 캐시 저장소가 죽거나 느려도 post-service가 정상이면 피드는 정상 응답이어야 한다(SHALL). 캐시 쓰기는 피드 응답을 기다리게 하지 않고 실패해도 피드에 영향이 없다. 캐시 읽기는 설정한 읽기 시간 상한을 넘겨 기다리지 않는다. 캐시 저장소의 상태는 feed-service의 헬스 체크를 내리지 않는다.

#### Scenario: 캐시 저장소가 죽었고 post는 정상

- **GIVEN** post 캐시 저장소에 연결할 수 없고 하위 서비스 5개는 정상이다
- **WHEN** `GET /feed`를 부른다
- **THEN** 200이고 `degradation.missing`과 `degradation.stale`이 빈 목록이다
- **AND** `GET /actuator/health`는 `UP`이다

#### Scenario: 캐시 저장소와 post가 함께 죽었다

- **GIVEN** post 캐시 저장소에 연결할 수 없고 post-service가 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** 300ms 안에 503이 온다

#### Scenario: 캐시 저장소가 응답하지 않는다

- **GIVEN** post 캐시 읽기 시간 상한이 30ms이고, `size=20` 캐시가 있지만 캐시 저장소가 1초 동안 응답하지 않으며, post-service가 503을 준다
- **WHEN** `GET /feed?size=20`을 부른다
- **THEN** 300ms 안에 503이 온다

#### Scenario: 캐시 쓰기를 기다리지 않는다

- **GIVEN** 캐시 저장소가 1초 동안 응답하지 않고 하위 서비스 5개는 정상이다
- **WHEN** `GET /feed`를 부른다
- **THEN** 300ms 안에 200이 온다

### Requirement: 대체 이벤트 로그 (F-002, F-004)

feed-service는 피드 요청에서 의존성을 대체할 때마다 그 의존성마다 로그를 한 줄 남겨야 한다(SHALL). 필드는 `event`(`fallback_applied`), `dependency`, `fallback`이고 피드 요청의 `traceId`가 함께 있다. `fallback`은 post `stale_cache`·`unavailable`, user `local_cache`(모두 채움)·`partial_cache`(일부 `unknown`), like `unknown`, recommendation·ad `omitted`다. post `unavailable`에는 `reason`(`cache_miss`, `cache_expired`, `cache_error`)이 있다.

#### Scenario: 선택 영역을 뺀다

- **GIVEN** recommendation-service가 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** `event` `fallback_applied`, `dependency` `recommendation`, `fallback` `omitted`인 줄이 하나 있고 그 `traceId`가 응답의 `X-Trace-Id`와 같다
- **AND** 같은 호출의 `downstream_call_failed` 줄도 그대로 있다

#### Scenario: 작성자 일부를 채우지 못한다

- **GIVEN** 캐시에 작성자 3만 있고 user-service가 503을 준다
- **WHEN** 작성자가 3, 4인 게시글로 `GET /feed`를 부른다
- **THEN** `dependency` `user`, `fallback` `partial_cache`인 `fallback_applied` 줄이 하나 있다

#### Scenario: 게시글을 대체할 수 없다

- **GIVEN** post 캐시가 비어 있고 post-service가 503을 준다
- **WHEN** `GET /feed`를 부른다
- **THEN** `dependency` `post`, `fallback` `unavailable`, `reason` `cache_miss`인 `fallback_applied` 줄이 하나 있다

#### Scenario: 정상이면 남기지 않는다

- **GIVEN** 하위 서비스 5개가 모두 정상이다
- **WHEN** `GET /feed`를 부른다
- **THEN** `fallback_applied` 줄이 없다

### Requirement: 대체 지표 (F-002)

feed-service는 `/actuator/prometheus`에 의존성 5개마다 200 피드 응답에서 그 영역의 결과(정상, 캐시로 채움, stale, 빠짐) 수, post 캐시 읽기 결과(적중, 만료, 없음, 오류)와 쓰기 결과(성공, 오류, 버림) 수, user 캐시 조회의 적중·누락 수를 내보내야 한다(SHALL). 장애가 없어도 모든 시계열이 0부터 있다. 의존성별 degrade 비율은 (stale + 빠짐) ÷ 전체로 계산한다.

#### Scenario: 지표가 있다

- **GIVEN** 하위 서비스 5개가 정상 응답한다
- **WHEN** `GET /feed`를 한 번 부른 뒤 `GET /actuator/prometheus`를 부른다
- **THEN** 의존성 5개마다 영역 결과 네 가지의 시계열이 있고 정상 값이 1, 나머지는 0이다
- **AND** post 캐시 읽기·쓰기 결과와 user 캐시 적중·누락 시계열이 있다

#### Scenario: 대체를 센다

- **GIVEN** 캐시에 작성자가 모두 있고 user-service와 recommendation-service가 503을 준다
- **WHEN** `GET /feed`를 한 번 부른다
- **THEN** user 영역의 "캐시로 채움"과 recommendation 영역의 "빠짐"이 각각 1 늘었다

### Requirement: 대체 설정값은 설정으로 바꾼다 (F-002)

stale 허용 시간, post 캐시 읽기 시간 상한, post 캐시 쓰기 간격, user 캐시의 유효 기간과 최대 크기는 설정 파일에 있고 환경 변수로 덮어쓸 수 있어야 한다(SHALL). 값마다의 근거는 아키텍처 6장에 있다.

#### Scenario: 환경 변수로 stale 허용 시간을 바꾼다

- **GIVEN** stale 허용 시간을 환경 변수로 1분으로 바꿔 띄웠고, 2분 전의 `size=20` 캐시만 있으며 post-service가 503을 준다
- **WHEN** `GET /feed?size=20`을 부른다
- **THEN** 503이 온다
