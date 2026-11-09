# Proposal

- **기능:** F-003 좋아요 상태 기반 API와 멱등성, 인기 글 몰림의 카운터 갱신
- **단계:** 고도화 6 ([PRD](../../../docs/prd.md) 1장, 4장 S-10·S-11, 6장 F-003, 10장 "남은 것"의 순서 판단과 S-11 부하 크기). F-005에 남겨 둔 "상태 기반 좋아요의 재시도"도 여기서 정한다.

## Why

MVP의 좋아요는 누를 때마다 상태가 뒤집히는 토글이다. 요청이 어떤 상태를 원하는지 담고 있지 않아, 네트워크가 불안하면 사용자의 의도와 다른 결과가 남는다.

- **재전송이 의도를 뒤집는다.** like-service는 반영했는데 feed-service가 타임아웃(100ms)으로 500을 돌려준 요청을 클라이언트가 다시 보내면 좋아요가 취소된다. 고도화 3의 타임아웃이 이 상황을 실제로 만든다. 그래서 고도화 5는 토글을 재시도에서 뺐다.
- **동시 요청에서 수가 어긋난다.** 수를 읽고 고쳐 저장해 갱신이 유실되고(`like-service/.../LikeService.kt`), 같은 사용자의 동시 첫 요청은 유니크 위반으로 실패한다.
- **순서가 바뀌면 지난 의도가 이긴다.** "좋아요 → 취소" 뒤에 첫 요청의 재시도가 도착해도 그것을 가려낼 정보가 없다. 취소하면 행을 지우므로 "이미 더 최근 요청을 처리했다"는 기록도 남지 않는다.
- **인기 글에는 한 행에 갱신이 몰린다.** 원자적 UPDATE로 정합성을 맞춰도 게시글당 카운터 행 하나에 잠금이 몰린다. 어느 방식을 쓸지 수치로 고른 적이 없다.

## What Changes

- **토글 문제를 먼저 재현해 기록한다.** 재전송, 동시 요청의 갱신 유실, 동시 첫 요청의 실패를 테스트로 재현하고 결과를 [결과 문서](../../../docs/experiments/idempotent-like.md)(이 change에서 만든다)에 남긴다.
- **BREAKING: 토글 API를 없애고 상태 지정 API로 바꾼다.** `POST /likes/{postId}/toggle`, `POST /feed/likes/{postId}/toggle`을 지우고 `PUT /likes/{postId}`, `PUT /feed/likes/{postId}`(본문 `{"liked": true|false, "version": N}`)를 둔다. 응답은 처리 뒤의 `postId`, `liked`, `likeCount`다.
- **같은 요청을 몇 번 받아도 결과가 같다.** 같은 사용자의 같은 요청이 차례로든 동시에든 N개 와도 좋아요는 한 번만 반영된다.
- **늦게 도착한 요청은 최근 결과를 덮어쓰지 못한다.** 요청의 `version`(클라이언트가 의도를 만들 때 정하고 재전송에도 그대로 쓰는 값)이 사용자·게시글별로 저장된 값보다 크지 않으면 바꾸지 않고 지금 상태를 200으로 돌려준다. 취소해도 기록을 지우지 않는다.
- **좋아요 수와 행 수가 어긋났는지 검사한다.** like-service에 불일치를 돌려주는 운영 엔드포인트를 둔다.
- **카운터 갱신 방식 세 가지를 설정으로 고르고 같은 몰림 부하로 비교한다.** 읽고 고쳐 저장하기(깨지는 기준선), 원자적 UPDATE, 카운터 분할. 고른 방식과 분할 수, S-11의 부하 크기는 측정 뒤 근거와 함께 정한다.
- **feed-service는 상태 변경을 다시 부르지 않는다.** 멱등해져 안전해졌지만, 재시도는 같은 `version`으로 다시 보내는 클라이언트 한 층에만 둔다(design 결정 5).
- **쓰기의 동시 호출 상한을 S-11 측정으로 다시 정한다.** 고도화 3에서 측정이 없어 미뤄 둔 값이다. 벌크헤드 이름은 `like-toggle`에서 `like-write`로 바꾼다.
- **S-10, S-11 실험 스크립트를 만든다.** 명령 하나(`experiments/run-like.sh`)로 돌려 리포트를 남긴다. 피드 실험 세트(S-01~S-09)와는 판정 방식이 달라 나눈다.

## Capabilities

### New Capabilities

없음. 좋아요 동작은 `post-like`에, 실험은 `fault-experiments`에 더한다.

### Modified Capabilities

- `post-like`: 토글을 없애고 상태 지정, 멱등, 동시성, 순서 뒤바뀜, 불일치 검사, 카운터 방식 요구사항을 더한다.
- `feed-aggregation`: 좋아요 토글 전달을 상태 전달로 바꾸고, 종단 간 동작과 실패 로그의 시나리오를 새 API로 고친다.
- `call-isolation`: 좋아요 토글의 격리를 상태 변경의 격리로 바꾼다.
- `retry-policy`: 재시도하지 않는 쓰기를 토글에서 상태 변경으로 바꾸고 이유를 고친다.
- `trace-logging`: 토글을 부르는 시나리오를 새 API로 고친다.
- `fault-experiments`: S-10, S-11 실행과 리포트를 더하고 지금 단계의 기대를 고도화 6으로 고친다.

## Impact

- **API(BREAKING):** 토글 경로 둘이 사라지고 `PUT` 경로 둘이 생긴다. 클라이언트는 이 저장소에 없고, 저장소 안의 호출자는 feed-service, 테스트, k6다.
- **데이터:** Flyway 마이그레이션으로 `post_like`에 `liked`, `version`을, `post_like_count`에 `shard`를 더한다. 기존 행은 `liked = true`, `version = 0`, `shard = 0`으로 이어진다.
- **코드:** `like-service`(저장, API, 카운터, 검사 엔드포인트), `feed-service`(`FeedController`, `LikeClient`, `LikeGateway`, `IsolatedCalls`, `application.yml`).
- **테스트:** 토글을 부르는 like-service, feed-service, e2e 테스트를 새 API로 옮긴다. 커밋된 테스트를 고치므로 대화형으로 먼저 끝낸다(tasks 1장).
- **실험:** `experiments/run-like.sh`와 k6 스크립트를 새로 만든다. `compose.yaml`의 like-service에 설정 덮어쓰기 변수를 더한다. 새 Compose 서비스나 라이브러리는 없다.
- **영향받는 시나리오:** 조회 쿼리와 like 쓰기 벌크헤드가 바뀌므로 S-01~S-09 실험 세트를 다시 돌린다.
