# Design

## Context

동기는 proposal의 Why를 본다. 설계를 정하는 현재 상태와 제약은 아래와 같다.

- `LikeService.toggle`은 행을 찾아 넣거나 지우고, `post_like_count`를 엔티티로 읽어 고쳐 저장한다(아키텍처 8장). `post_like`는 `(user_id, post_id)` 유니크다.
- feed-service의 쓰기는 `IsolatedCalls.callBlocking("like", "like-toggle")`로 요청 스레드에서 서킷 → Semaphore 벌크헤드(10)를 거치고, like의 응답 타임아웃(100ms)이 시간 상한 역할을 한다. 재시도는 없다.
- 재시도 루프(`RetryLoop`)는 비동기이고 스레드 하나인 `isolation-retry` 스케줄러에서 다음 시도를 시작한다. 블로킹 쓰기를 그대로 올리면 이 스레드가 HTTP 호출에 묶인다.
- MySQL 9.7, InnoDB, 기본 격리 수준 REPEATABLE READ. Compose에서 like-service와 MySQL은 각각 CPU 1개이고 HikariCP는 기본값(최대 10)이다.
- `experiment` 프로필의 like-service는 요청마다 지연(중앙값 20ms, p99 50ms)을 넣는다. Actuator와 하위 서비스 포트는 Compose 네트워크 안에서만 열린다.
- `experiments/run.sh`(709줄)는 피드 SLO 판정에 맞춰져 있다. S-10, S-11은 피드 SLO로 판정하지 않는다(PRD 4장).

이 change는 아키텍처 12장대로 8장의 고도화 6 항목과 10장의 속성 기반 테스트를 가져간다. 8장과 다르게 정한 곳은 결정 4에 적었다.

## Goals / Non-Goals

**Goals:**

- 재전송, 동시 요청, 순서 뒤바뀜에서 최종 상태와 수가 사용자의 마지막 의도와 같다.
- 정합성을 지키는 잠금 방식과 카운터 방식을 고른 이유가 수치로 남는다.
- 어긋났는지 운영에서 확인할 수 있다.

**Non-Goals:**

- 어긋난 수를 자동으로 고치는 작업. 검사만 한다.
- 비동기 집계(큐, outbox, 모아서 반영)와 인증(PRD 8장). `version` 위조나 잘못된 클라이언트는 막지 않는다.
- 조회 응답(`GET /likes`, 피드)의 계약 변경. `version`을 조회로 내려 주지 않는다.
- 인기 글 몰림의 읽기 캐시. S-11은 쓰기 경합만 본다.

## Decisions

### 1. API는 원하는 상태를 담는 `PUT`이다

`PUT /likes/{postId}`와 `PUT /feed/likes/{postId}`에 `{"liked": 불리언, "version": 양의 정수}`를 보낸다. 사용자·게시글 하나의 상태를 통째로 정하는 요청이라 `PUT`의 뜻과 맞다.

| 대안 | 고르지 않은 이유 |
|---|---|
| `POST`로 누르고 `DELETE`로 취소 | `DELETE`에 순서 값을 담을 자리가 마땅치 않고, 같은 일을 두 경로로 나눈다 |
| `Idempotency-Key` | 같은 요청의 반복만 가려낸다. "좋아요 → 취소" 뒤에 늦게 온 좋아요는 키가 달라 막지 못한다. 키 저장소와 만료도 더 든다 |

### 2. 순서는 클라이언트가 정한 `version`으로 판단한다

사용자·게시글마다 반영한 가장 큰 `version`을 저장하고, 들어온 값이 더 클 때만 바꾼다. 저장된 값 이하면 바꾸지 않고 200과 지금 상태를 돌려준다. 재전송이 성공처럼 보여야 클라이언트가 멈추고, 응답의 상태가 곧 최종 결과라 클라이언트가 화면을 맞출 수 있기 때문이다.

클라이언트 규칙은 "의도를 만들 때 한 번 정하고 재전송에도 그대로 쓴다. 값은 그때의 epoch 밀리초, 그 게시글에 앞서 쓴 값 이하면 앞 값 + 1"이다. 서버는 "클수록 최근"만 보므로 값의 뜻에 기대지 않는다.

| 대안 | 고르지 않은 이유 |
|---|---|
| 서버가 받은 시각 | 늦게 도착한 재시도가 가장 최근으로 보인다. 풀려는 문제 그 자체다 |
| 서버가 준 버전으로 비교(`If-Match`, `@Version`) | 조회 계약에 버전을 더해야 한다. 응답을 받기 전에 연달아 누르면 뒤 요청이 충돌로 실패하고, `like`가 `UNKNOWN`일 때는 버전을 알 수 없다 |
| 클라이언트 일련번호(기기별 1, 2, 3) | 기기가 여럿이면 서로 비교할 수 없다. 시각 기반 값은 기기 사이에서도 대체로 맞는다 |

기기 사이의 시계 차이로 늦은 쪽의 의도가 질 수 있다(Risks). 좋아요는 "마지막 쓰기 승리"로 충분하다고 판단했다.

### 3. 취소해도 행을 남긴다

`post_like`에 `liked`와 `version`을 더하고 취소는 `liked = false`로 둔다. 행을 지우면 취소 뒤에 늦게 온 좋아요를 가려낼 기록이 사라진다. 사용자·게시글당 행은 하나라 무한히 늘지 않는다. 조회와 "실제 좋아요 행 수"는 `liked = true`만 센다. `(post_id, liked)` 인덱스를 더해 게시글별로 세는 검사가 전체 스캔이 되지 않게 한다.

의도를 별도 테이블에 두는 안은 조회마다 조인이나 두 번 읽기가 생기고 두 테이블을 맞춰야 해서 고르지 않았다. 마이그레이션의 기존 행은 `liked = true`, `version = 0`이라 어떤 실제 요청(`version` ≥ 1)이든 이긴다.

### 4. 사용자·게시글 행 하나를 잠그고 그 안에서 판단한다

READ COMMITTED 트랜잭션 하나에서 아래 순서로 한다.

```
1. INSERT ... (liked=false, version=0) ON DUPLICATE KEY UPDATE version = version   -- 행을 만들거나 X 잠금을 잡는다
2. SELECT liked, version ... WHERE user_id=? AND post_id=?                         -- 잠근 행을 읽는다
3. 요청 version ≤ 저장 version이면 수를 읽어 돌려준다
4. UPDATE post_like SET liked=?, version=?
5. 상태가 바뀌었을 때만 카운터를 ±1 (결정 6)
6. 수를 읽어 돌려준다
```

같은 사용자·게시글의 요청은 1에서 줄을 서고, 잠금 순서는 언제나 `post_like` 행 → 카운터 행이라 순환이 없다.

아키텍처 8장은 "유니크 위반을 이미 반영된 요청으로 처리한다"고 적었지만 따르지 않는다. 행이 취소 기록으로 남으므로(결정 3) 유니크 위반이 "이미 반영됨"을 뜻하지 않는다. 8장의 "영속성 컨텍스트 비우기"는 `@Modifying(clearAutomatically = true, flushAutomatically = true)`로 지킨다.

| 대안 | 고르지 않은 이유 |
|---|---|
| `INSERT IGNORE` 뒤 `SELECT ... FOR UPDATE` | 중복 시 공유 잠금을 잡아, 동시 요청끼리 S→X 승격을 기다리다 교착한다 |
| REPEATABLE READ에서 없는 행을 `FOR UPDATE` | 갭 잠금을 서로 잡고 INSERT에서 교착한다. 그래서 READ COMMITTED다 |
| 조건부 UPDATE 한 문장 | 이전 상태를 알 수 없어 수를 바꿀지 판단하려면 문장이 둘 더 든다 |
| 낙관적 잠금 + 재시도 | 같은 행에 몰리면 재시도가 경합을 키운다 |

교착이 없다는 것은 동시성 테스트와 S-11의 교착 수로 확인한다. 0이 아니면 트랜잭션 재실행으로 덮기 전에 원인을 보고 멈춘다.

### 5. feed-service는 상태 변경을 다시 부르지 않는다

멱등해져 다시 보내도 안전하지만 재시도는 클라이언트 한 층에만 둔다.

- 호출과 재시도를 맡은 층이 둘이면 시도 수가 곱해진다.
- 재시도할 수 있는 실패(5xx, 연결 실패)는 애초에 드물고, 정작 "반영됐는지 모르는" 타임아웃은 재시도 정책이 다시 부르지 않는다. 얻는 것이 작다.
- 쓰기를 `RetryLoop`에 올리려면 블로킹 호출을 옮길 스레드 풀과 그 크기의 근거가 더 필요하다.

클라이언트에는 500이 가고, 같은 `version`으로 다시 보내면 된다(S-10). F-005 수용 기준의 "F-003에서 정한다"를 이 결정으로 닫고 PRD를 고친다. `callBlocking` 경로와 서킷 공유는 그대로다. 실패 응답도 500 그대로다.

### 6. 카운터 방식은 전략으로 나누고 모두 `(post_id, shard)` 행에 쓴다

`post_like_count`의 기본 키를 `(post_id, shard)`로 바꾼다. 원자적 UPDATE와 읽고 고쳐 저장하기는 `shard = 0`만 쓰고, 카운터 분할은 `floorMod(user_id, N)` 칸에 쓴다. 조회는 방식과 관계없이 `SUM ... GROUP BY post_id`라, 방식을 바꿔도 수가 이어진다.

| 방식 (`weave.like.counter.strategy`) | 갱신 | 보장 |
|---|---|---|
| `read-modify-write` | 엔티티를 읽고 더해 저장 | 없음. 비교 기준선 |
| `atomic` | `INSERT ... ON DUPLICATE KEY UPDATE like_count = like_count + ?` | 있음. 게시글당 행 하나에 잠금이 몰린다 |
| `sharded` (`weave.like.counter.shards`) | 같은 문장을 칸 하나에 | 있음. 잠금을 N칸에 나눈다 |

- 칸을 사용자 ID로 고르면 같은 사용자의 +1과 -1이 같은 칸에 들어가 칸의 값이 음수가 되지 않는다. 무작위 선택은 분포가 더 고르지만 칸이 음수가 될 수 있어 검사와 추론이 어려워진다.
- 읽고 고쳐 저장하기를 운영 코드에 남기는 것은 S-11의 기준선이 필요해서다. 기본값이 될 수 없게 설정을 명시해야만 켜지고, 고도화가 끝난 뒤 지울 후보로 문서에 적는다.
- 기본값은 일단 `atomic`이고, S-11 측정 뒤 설정만 고치는 커밋으로 정한다.

### 7. 불일치 검사는 Actuator 엔드포인트다

`GET /actuator/likeconsistency[?postId=]`가 행 수와 저장된 수를 게시글별로 비교해 다른 것만 돌려준다. 양쪽에 없는 게시글도 찾도록 두 집계를 합친다(MySQL에 FULL JOIN이 없어 `UNION`으로 게시글 ID를 모은다). 전체 검사가 무거울 수 있어 결과는 100건으로 자른다. 노출은 `experiment` 프로필의 Actuator 목록에 더하고, 운영에서는 필요할 때 노출 설정으로 연다. 값을 고치지 않으므로 잘못 불러도 해가 없다. 스크립트에서만 쓰는 SQL로 두는 안은 PRD의 "검사하는 방법"을 코드로 고정하지 못해 고르지 않았다.

### 8. 토글 재현은 지우기 전에 한 커밋으로 남긴다

토글을 남기지 않으므로(PRD) 재현 테스트도 토글과 함께 사라진다. 그래서 재현 테스트를 커밋해 돌리고 수치를 결과 문서에 적은 뒤, 다음 커밋에서 지운다. 재현 커밋 해시를 문서에 적어 다시 돌려 볼 수 있게 한다. S-10 스크립트를 토글에 먼저 돌리는 안은 새 API로 다시 써야 해서 고르지 않았다.

### 9. 쓰기 상한은 S-11 측정으로 다시 계산한다

Semaphore 이름을 `like-toggle`에서 `like-write`로 바꾸고, 값은 S-11이 잰 쓰기 처리량과 지연으로 리틀의 법칙에 맞춰 다시 정한다. like 커넥션 풀은 "읽기 40 + 쓰기 상한"을 유지한다(아키텍처 5-6). 측정 결과 10이 맞으면 근거만 고친다.

### 10. S-10, S-11은 `experiments/run-like.sh`로 따로 돌린다

`run.sh`의 피드 판정, 서킷, degrade 계산을 쓰지 않으므로 나눈다. `run-all.sh`는 그대로 S-01~S-09다. `experiments/CLAUDE.md`의 bash 3.2, 신호, 표준 출력 규칙을 지킨다.

- **S-10**(`k6/like-s10.js`): 부하 없이 네 경우를 차례로 돈다. 중복과 순서 뒤바뀜은 feed-service를 거치고, 타임아웃 뒤 재전송은 like 프록시에 응답 지연(like 응답 타임아웃 100ms보다 긴 값)을 넣어 만든다. "이미 반영됐다"와 최종 상태는 k6가 Compose 네트워크 안에서 like-service의 `GET /likes`와 불일치 검사로 직접 본다.
- **S-11**(`k6/like-s11.js`): like-service를 직접 부른다. feed-service의 100ms 상한과 쓰기 상한이 비교할 대상을 잘라 버리기 때문이다. constant-arrival-rate로 반복마다 새 사용자(`exec.scenario.iterationInTest`)가 누르게 해 모든 요청이 실제 수 갱신이 되게 한다. 실행마다 새 게시글을 쓰고 측정 전에 한 번 눌러 행을 만들어, 첫 INSERT 경쟁을 비교에서 뺀다. 앱 수준 지연은 0으로 두고 끝나면 되돌린다. DB 지연만 보려는 것이다.
- **방식 바꾸기:** `compose.yaml`에 `LIKE_SPRING_APPLICATION_JSON`을 더하고(feed-service와 같은 방식) 방식마다 like-service를 새로 띄운다.
- **잠금 대기:** 실행 앞뒤로 `SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_%'`와 `information_schema.INNODB_METRICS`의 `lock_deadlocks`를 읽어 뺀다. 계정은 `docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot ...'`로 컨테이너 환경에서 쓰고 `.env`를 읽지 않는다.
- **판정:** `lib/like-verdict.sh`가 `atomic`, `sharded`의 불일치나 실패가 있으면 1을 낸다. `read-modify-write`의 불일치는 리포트에만 적는다.
- **리포트:** `experiments/results/<UTC 시각>-S-1x/report.md`와 `report.json`. 기본값은 `experiments/like-config.json`이다.

**측정으로 정할 값(코드에 미리 넣지 않는다):**

| 값 | 정하는 법 |
|---|---|
| S-11 RPS | `atomic`으로 RPS를 단계적으로 올려, 행 잠금 대기가 뚜렷하고 실패가 없는 가장 높은 단계. 처음에는 "p99가 꺾이는 지점의 바로 위"로 정했지만 p99가 단계를 따라 고르게 오르지 않아(150·200·250·300 RPS에서 87·316·482·360ms) 잠금 대기와 실패로 바꿨다. 경합이 보여야 비교가 되고, like-service나 MySQL의 CPU 1개가 먼저 포화되면 잠금이 아니라 CPU를 재는 것이므로 CPU 사용률을 함께 본다 |
| 분할 수 | 4, 8, 16을 돌려 잠금 대기가 더 줄지 않는 가장 작은 값. 동시 트랜잭션이 HikariCP 최대 10을 넘지 못해 16 위로는 볼 이유가 적다 |
| 기본 방식 | 경합 수치, 조회 비용, 복잡도를 함께 보고 정한다 |
| `like-write` 상한 | 결정 9 |

### 11. 테스트 배치

| 대상 | 위치 | 방식 |
|---|---|---|
| 상태 전이 판단(`version` 비교, 수 변화량) | `like-service` `LikeIntentTest` | 단위. `checkAll`로 임의 순서·중복 |
| 상태 지정, 반복, 늦은 도착 | `PostLikeStateScenarioTest` | Integration(Testcontainers). 임의 순서·중복은 `checkAll`로 게시글을 바꿔 가며 HTTP까지 |
| 동시 요청 | `PostLikeConcurrencyScenarioTest` | Integration. 실제 HTTP 포트와 스레드 |
| 불일치 검사 | `PostLikeConsistencyScenarioTest` | Integration. 어긋남은 SQL로 직접 만든다 |
| 카운터 방식 | `PostLikeCounterScenarioTest` | Integration. 방식마다 애플리케이션을 띄운다 |
| feed-service 전달, 격리, 재시도 없음, 로그 | 기존 feed-service 시나리오 테스트 | Integration(WireMock) |
| 종단 간 | `FeedEndToEndScenarioTest` | Integration(e2e) |
| 판정 스크립트 | `ExperimentLikeVerdictScenarioTest` | 픽스처로 `lib/like-verdict.sh` |
| S-10, S-11 실행 | `ExperimentLikeRunComposeScenarioTest` | Compose 태그, 짧은 실행 |

테스트 이름에 `F-003`을 넣는다. 다른 기능의 요구사항을 옮긴 것은 그 기능 ID도 함께 넣는다.

### 12. 영향받는 장애 시나리오

| 바뀌는 것 | 영향 | 확인 |
|---|---|---|
| like 조회 쿼리(`SUM`, `liked` 조건) | S-01, S-05, S-09의 like 지연과 degrade 비율 | S-01~S-09 전체를 돌려 고도화 5 결과와 비교 |
| `like-write` 상한과 like 커넥션 풀 | 읽기 40의 몫은 유지. 쓰기 부하는 실험 세트에 없다 | 같은 실행, 설정 커밋을 따로 |
| S-10 | 새 시나리오 | `run-like.sh S-10` |
| S-11 | 새 시나리오 | `run-like.sh S-11` |

## Risks / Trade-offs

- [기기 사이 시계 차이로 나중 의도가 질 수 있다] → 좋아요는 마지막 쓰기 승리로 충분하다고 보고 받아들인다. 응답이 최종 상태라 클라이언트가 바로 알 수 있다.
- [아주 큰 `version` 하나가 이후 요청을 모두 막는다] → 인증을 제외했고(PRD 8장) 클라이언트를 믿는 범위다. 상한을 두려면 근거 없는 값이 하나 더 생겨 두지 않는다. 문서에 적는다.
- [취소 행이 남아 저장 공간을 쓴다] → 사용자·게시글당 한 행으로 묶인다. 정리는 하지 않는다.
- [S-11의 병목이 행 잠금이 아니라 redo 로그 flush나 CPU일 수 있어 카운터 분할의 이득이 작게 나올 수 있다] → 그 자체가 결과다. 행 잠금 대기, CPU를 함께 기록해 어느 쪽인지 가른다.
- [몰림에서 쓰기가 like 응답 타임아웃(100ms)을 넘으면, 반영됐는데 500이 가는 일이 늘어난다] → 멱등이라 정합성은 깨지지 않는다. S-11의 p99로 크기를 보고, 타임아웃은 근거 없이 바꾸지 않는다.
- [잠금 대기가 길어지면 like-service의 Tomcat 스레드와 커넥션 풀이 찬다] → S-11에서 HikariCP 대기와 실패 수를 기록한다. 쓰기 상한(결정 9)이 feed-service 쪽 유입을 막는다.
- [READ COMMITTED에서 `likeCount` 응답은 다른 트랜잭션이 커밋한 만큼만 본다] → 응답 수는 그 순간의 값이고, 최종 일치는 행 수와 불일치 검사로 본다.
- [읽고 고쳐 저장하기가 운영 코드에 남는다] → 명시한 설정에서만 켜진다. 측정이 끝나면 지울 후보로 적는다.

## Migration Plan

1. Flyway `V2`로 열을 더하고 기본 키를 바꾼다. 기존 데이터는 그대로 뜻이 이어진다.
2. API가 바뀌므로 feed-service와 like-service를 함께 배포한다. 저장소 밖 클라이언트는 없다.
3. 되돌릴 때는 두 서비스를 함께 되돌린다. `V2` 뒤의 취소 행은 옛 코드가 좋아요로 읽으므로, 되돌리기 전에 `liked = false` 행을 지운다.
