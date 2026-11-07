# Design

## Context

- 왜 하는지는 proposal의 Why, 지켜야 할 동작은 specs(`call-isolation`, `feed-aggregation`, `fault-experiments` 델타)에 있다.
- 지금 feed-service는 Gateway Bean 다섯 개(`com.weave.feed.gateway`)가 `applicationTaskExecutor`(코어 100, 대기열 무제한) 하나에서 `CompletableFuture.supplyAsync`로 HTTP Interface 클라이언트를 부른다. 타임아웃은 HttpClient 기본값 3분이고, 실패는 `logFailure`가 한 줄 남긴 뒤 그대로 올라가 500이 된다. `FeedAssembler`는 `join()`으로 상한 없이 기다린다.
- 의존성마다 HTTP 서비스 그룹(`post` 등)이 하나씩 있고 그룹마다 커넥션 풀이 따로다(라우트당 20, `weave.http-client.max-connections-per-route`). `ConnectionPoolMetrics`가 그룹 설정으로 요청 팩토리를 다시 만들어 넣으므로, Boot의 그룹 설정(`spring.http.serviceclient.<그룹>.connect-timeout`, `read-timeout`)은 그대로 적용된다.
- 이 change는 아키텍처 12장 표대로 5-2~5-6, 7-3의 벌크헤드 전파, Resilience4j 호환 확인, 가상 스레드 비교를 가져간다. 다르게 정한 것은 결정 1에 적는다.
- 근거로 쓰는 측정값은 [MVP 기준선](../../../docs/experiments/baseline-mvp.md)이다. 실험 RPS 400, 의존성별 평시 호출 p99 51.0~53.7ms, 피드 p99 92ms, 400 RPS에서 의존성별 커넥션 사용 중 최댓값 14~16, 공유 풀 활성 최댓값 52~57(다섯 의존성 합).
- Resilience4j 2.4.0 소스에서 확인한 것: `ThreadPoolBulkheadConfig`는 대기열 0을 받고 그때 `SynchronousQueue`를 쓴다. aspect 기본 순서는 CircuitBreaker(`LOWEST_PRECEDENCE - 4`) → RateLimiter(-3) → TimeLimiter(-2) → Bulkhead(-1)이다. `resilience4j-spring-boot4`는 레지스트리, Micrometer 지표, 헬스 지표 자동 설정과 Boot 버전 검사(`SpringBoot4VerifierAutoConfiguration`)를 싣는다.

## Goals / Non-Goals

**Goals**

- 하위 서비스 호출 경로가 하나뿐이고, 그 경로가 늘 서킷 브레이커 → 시간 상한 → 벌크헤드 → 호출 순서로 감싼다. 감싸지 않은 호출 경로를 만들 수 없게 한다(하네스 규칙 3-3의 3번).
- 설정값마다 기준선 측정이나 계산이 이 문서에 있고, 값을 바꾸는 커밋은 따로 한다.
- 실험 세트가 고도화 3의 개선(p99)을 기준으로 지키고, 다음 단계에서 그 개선이 깨지면 실험 세트가 실패한다.

**Non-Goals**

- 등급별 대체 동작과 응답 계약(F-002). 실패는 모두 500이다.
- 재시도와 ad의 429·Retry-After(F-005). Retry·RateLimiter 모듈은 설정하지 않는다.
- 인스턴스 여러 개에서의 서킷 공유(PRD 8장).

## Decisions

### 1. 격리는 Resilience4j 레지스트리로 한 곳에서 감싼다 (어노테이션을 쓰지 않는다)

Gateway마다 어노테이션을 붙이는 대신, 의존성 이름과 호출 함수를 받아 격리를 씌운 `CompletableFuture`를 돌려주는 컴포넌트 하나(`IsolatedCalls`, `com.weave.feed.gateway`)를 둔다. Gateway는 `isolated.call("post") { client.getLatestPosts(size).posts }`처럼 부르기만 한다. 서킷 브레이커, TimeLimiter, 벌크헤드는 스타터가 `resilience4j.*` 설정으로 만든 레지스트리에서 의존성 이름으로 꺼낸다. 설정 구조(`configs.default`, `instances.<의존성>`)와 지표는 아키텍처 5-3 그대로다.

감싸는 순서는 코드에 한 번만 적는다: `CircuitBreaker.decorateCompletionStage(TimeLimiter.executeCompletionStage(벌크헤드에서 호출))`. 아키텍처 5-4의 순서와 같다.

- **어노테이션 대신 이렇게 한 이유:**
  - 결정 11의 비교 실험은 같은 호출을 ThreadPool 벌크헤드와 Semaphore 벌크헤드로 바꿔 돌려야 한다. `@Bulkhead(type = ...)`는 컴파일 때 정해져 Gateway를 두 벌 만들어야 한다.
  - 시간 상한이 벌크헤드 대기까지 포함하는 것, 열린 서킷이 벌크헤드 자리를 쓰지 않는 것은 감싸는 순서로 정해진다. 어노테이션이면 순서가 aspect 순서 속성에 흩어지고, 같은 클래스 안 호출(self-invocation)이나 프록시를 거치지 않는 호출이 생기면 조용히 빠진다. 한 곳에서 감싸면 "격리를 거치지 않는 호출이 없다"를 Gateway가 `IsolatedCalls`만 쓰는지로 확인할 수 있다.
  - AOP(AspectJ) 의존성을 더하지 않아도 된다.
- **다른 안:** 아키텍처 5-2의 어노테이션 방식. 위 세 가지 때문에 쓰지 않는다. 아키텍처 1장·5-2를 이 결정으로 고친다.
- 실패 로그(`downstream_call_failed`)도 `IsolatedCalls`가 감싼 future의 결과에서 한 번 남긴다. 시간 상한이 먼저 끝낸 호출이 나중에 HttpClient 응답 타임아웃으로 실패해도 그 실패는 버린다(로그를 두 번 남기지 않는다, `feed-aggregation` "호출 시간 상한을 넘긴다").

### 2. Boot 4.1.2 호환을 첫 구현 작업에서 확인한다

`io.github.resilience4j:resilience4j-spring-boot4:2.4.0`을 feed-service에 더하고(하네스 규칙 2-2 "확인", 이 change 승인 때 함께 확인한다) 아래가 되는지 테스트로 본다. 하나라도 안 되면 멈추고 사람에게 넘긴다.

- 레지스트리 네 개(CircuitBreaker, ThreadPoolBulkhead, Bulkhead, TimeLimiter)가 `resilience4j.*` 설정으로 인스턴스를 만든다.
- `/actuator/prometheus`에 `resilience4j_circuitbreaker_state`, `resilience4j_bulkhead_*`(ThreadPool 포함), `resilience4j_timelimiter_calls_total`이 의존성 이름(`name` 라벨)으로 나온다.
- `SpringBoot4VerifierAutoConfiguration`이 4.1.2에서 시작을 막지 않는다.

**확인 결과 (tasks 2.1, `ResilienceAutoConfigurationTest`):** 모두 된다.

- 레지스트리 네 개가 `instances.<이름>` 설정으로 인스턴스를 시작할 때 미리 만든다(호출 전에 `registry.find(이름)`으로 찾힌다). ThreadPool 벌크헤드는 `queue-capacity: 0`을 받는다.
- Boot 버전 검사 자동 설정이 컨텍스트에 들어 있고 4.1.2에서 시작을 막지 않는다.
- Prometheus 지표 이름(모두 `name` 라벨이 인스턴스 이름이다):

  | 무엇 | 지표 |
  |---|---|
  | 서킷 | `resilience4j_circuitbreaker_state{state}`, `resilience4j_circuitbreaker_not_permitted_calls_total` |
  | ThreadPool 벌크헤드 | `resilience4j_bulkhead_active_thread_count`, `resilience4j_bulkhead_thread_pool_size`, `resilience4j_bulkhead_max_thread_pool_size`, `resilience4j_bulkhead_queue_depth`, `resilience4j_bulkhead_queue_capacity` |
  | Semaphore 벌크헤드 | `resilience4j_bulkhead_available_concurrent_calls`, `resilience4j_bulkhead_max_allowed_concurrent_calls` |
  | TimeLimiter | `resilience4j_timelimiter_calls_total{kind}`. `kind="timeout"`이 호출 전에도 0으로 있다 |

  ThreadPool과 Semaphore 벌크헤드는 같은 `resilience4j_bulkhead_` 접두어를 쓰고 지표 이름으로 갈린다. 같은 의존성 이름의 두 벌크헤드가 함께 있어도 시계열이 겹치지 않는다.
- 벌크헤드 거절 지표는 없다(`resilience4j_bulkhead_*`에 `reject`가 든 이름이 없다). 대기열 0인 ThreadPool 벌크헤드가 차면 `BulkheadFullException`과 함께 `onCallRejected` 이벤트가 온다. 그래서 거절 수는 그 이벤트로 센다(Open Questions).

### 3. 호출 시간 상한과 HTTP 타임아웃

| 값 | post, user, like | recommendation, ad | 근거 |
|---|---|---|---|
| 호출 시간 상한 (TimeLimiter `timeout-duration`) | 100ms | 200ms | 아키텍처 4-3. 기준선 평시 p99(51.0~53.7ms)의 약 2배. 선택 등급인 recommendation·ad는 ①+②가 끝날 때까지만 기다리면 된다 |
| 응답 타임아웃 (`read-timeout`) | 100ms | 200ms | 호출 시간 상한과 같게 둔다. TimeLimiter가 future를 끝내도 블로킹 I/O를 하던 스레드는 풀리지 않으므로, 스레드와 커넥션을 놓는 시점을 HttpClient가 정한다. 이보다 길면 상한이 지난 호출이 벌크헤드 자리를 그만큼 더 잡는다 |
| 연결 타임아웃 (`connect-timeout`) | 100ms | 200ms | 같은 이유. 연결도 호출 시간 안에 든다 |
| 커넥션 대기 (`connectionRequestTimeout`) | 100ms (모든 그룹) | | 벌크헤드 크기 ≤ 커넥션 풀 크기라(결정 5) 평시에는 기다리지 않는다. 기다린다면 설정 오류이므로 가장 짧은 상한으로 빨리 실패한다. Boot 그룹 설정에 없어 `weave.http-client.connection-request-timeout` 하나로 둔다 |

- 응답 타임아웃은 "읽기 사이의 무응답" 상한이라, 조금씩 흘리는 응답은 이것만으로 막지 못한다. 총 시간은 TimeLimiter가 막는다. 둘 다 둔다.
- `cancel-running-future: true`. future 취소가 스레드를 깨우지는 못하지만, 벌크헤드 대기열에서 아직 시작하지 않은 일은 빠진다.
- **평시에 상한에 걸리는 비율 확인:** 피드 하나가 호출 다섯 번이라, 호출 하나가 상한에 걸리는 비율이 0.01%면 피드 실패는 최대 약 0.05%로 가용성 예산(0.1%)의 절반이다. 값을 넣기 전에 MVP 구성으로 S-01(400 RPS)을 한 번 돌려 의존성별 호출 지연 히스토그램에서 상한보다 짧은 버킷 경계를 넘는 비율(100ms 상한은 89.48ms 버킷, 200ms 상한은 178.96ms 버킷. 더 짧은 경계를 쓰므로 비율을 크게 잰다)을 구한다. 0.01%를 넘는 의존성이 있으면 값을 넣지 않고 멈춘다. 결과는 이 문서와 아키텍처 4-3에 적는다.
- **측정 결과 (tasks 4.1, 2026-11-07, develop `892c6e9`, S-01 400 RPS, `20261107-033746-S-01`):** S-01은 SLO 충족(p99 90ms, 가용성 1). 의존성마다 측정 구간 약 12만 호출, 토글 제외.

  | 의존성 (상한) | 89.48ms 초과 | 111.85ms 초과 | 67.11ms 초과 (느린 호출 80ms) | 156.59ms 초과 (느린 호출 160ms) | 178.96ms 초과 |
  |---|---|---|---|---|---|
  | post (100ms) | 0.0119% | 0.0017% | 0.149% | | |
  | user (100ms) | 0.0076% | 0 | 0.143% | | |
  | like (100ms) | 0.0136% | 0.0025% | 0.163% | | |
  | recommendation (200ms) | | | | 0 | 0 |
  | ad (200ms) | | | | 0 | 0 |

  post와 like는 보수적인 경계(89.48ms)로 0.01%를 넘었고, 100ms를 넘는 실제 비율은 경계 사이(post 0.0017~0.0119%, like 0.0025~0.0136%)라 정할 수 없어 멈췄다. **사람이 정한 것: 100ms를 유지하고 판단 기준을 피드 단위로 고친다.** 규칙의 목적은 평시 타임아웃으로 인한 피드 실패를 가용성 예산(0.1%)의 절반 이하로 두는 것이고, 피드 하나가 실패할 확률은 의존성별 비율의 합 이하다. 보수적으로 잡은 합(post + user + like, recommendation·ad는 0)이 0.033%라 0.05% 아래다. 120ms로 올리는 안은 post + user·like가 240ms라 예산 250ms의 여유가 10ms뿐이어서 고르지 않았다. 느린 호출 경계(80ms·160ms)를 넘는 비율은 0.17% 이하라 문턱 50%와 거리가 멀다. 값을 넣은 뒤 S-01을 다시 재 실제 타임아웃 수를 적는다(tasks 4.7).

- **값을 넣은 뒤 다시 잰 S-01 (tasks 4.7, 2026-11-07, 이 브랜치, 400 RPS):**
  - **Compose를 막 띄운 직후 (`20261107-040311-S-01`): SLO 미충족**(가용성 0.892, p99 103ms). like-service가 차가워(JIT, MySQL) 호출이 100ms 상한에 걸리고 느린 호출 비율 100%로 like 서킷이 열렸다. 열린 동안 like-service에 트래픽이 가지 않아 데워지지 못했고, 반열림 시험 호출이 다시 느려 열림·닫힘을 되풀이했다. 시작부터 약 100초(준비 60초 + 측정 40초) 동안 이어졌고 그동안 피드가 거의 모두 실패했다. 그 뒤로는 구간 p99 86~127ms, 가용성 0.994~1이다. MVP에서는 같은 콜드 스타트가 지연만 늘렸고 전체 부하로 바로 데워졌다. 서킷의 느린 호출 기준을 풀어도 차가운 like-service는 타임아웃만으로 실패율 100%라 설정으로는 막지 못한다. 실험 절차로 다룬다(결정 10 "준비 실행").
  - **하위 서비스를 데운 뒤 (`20261107-041019-S-01`): SLO 충족**(가용성 0.999492, p99 91ms, 다섯 서킷 모두 `CLOSED`, like 호출 p99 52.8ms). 피드 실패 62건 = 시간 상한 48건(user 35, post 7, like 6) + 벌크헤드 거절 14건(ad 7, recommendation 4, user 3). 실패 비율 0.051%로 가용성 예산(0.1%)의 절반쯤이다. user 타임아웃 비율(0.029%)이 MVP에서 잰 상한 쪽 추정(0.0076%)보다 크다. 벌크헤드 스레드로 넘기는 비용이 더해진 것으로 보인다. 여유가 크지 않아 5.1의 S-01 결과로 다시 본다.

### 4. 요청 시간 예산

- `weave.feed.time-budget: 250ms`(아키텍처 4-3: p99 300ms에 여유 50ms).
- `FeedAssembler`가 요청을 받은 시각에 마감 시각을 정하고, 모든 future를 한 번에 `allOf(...).get(남은 시간)`으로 기다린다. 남은 시간이 0 이하면 기다리지 않는다.
  - 구현(tasks 2.6): `allOf` 대신 post·recommendation·ad를 차례로 `get(그때 남은 시간)`으로 기다린다(`RequestBudget.await`). 마감은 같고, 앞의 것이 실패하면 뒤를 기다리지 않고 바로 실패하던 MVP 동작을 그대로 둔다.
- user와 like는 post가 끝난 뒤 시작할 때 남은 시간이 0 이하면 부르지 않는다. 시작한 뒤에는 각자의 상한(100ms)과 전체 마감 중 먼저 오는 쪽에서 끝난다. 남은 예산을 호출마다 TimeLimiter에 넘기지 않는 이유: TimeLimiter의 상한은 인스턴스 설정이라 호출마다 바꿀 수 없고, 전체 마감 하나로 "남은 예산을 넘겨 기다리지 않는다"를 지킬 수 있다.
- 마감이 지나면 끝나지 않은 future마다(부르지 않은 user·like 포함) `cause` `budget_exhausted` 실패 로그를 남기고 취소한 뒤 500으로 끝낸다(예외는 `FeedController`까지 그대로 올라간다. 응답 형식은 F-002가 정한다). 예산 초과는 서킷에 세지 않는다. 피드 쪽 사정이지 하위 서비스 실패가 아니어서다.
- 지금 값에서는 post 상한(100ms) + user·like 상한(100ms) = 200ms < 250ms라 예산이 먼저 끝나는 일은 벌크헤드 대기나 스레드 전환 지연이 쌓일 때뿐이다. 예산은 그때의 안전장치다.

### 5. 벌크헤드와 커넥션 풀 크기

| 값 | 크기 | 근거 |
|---|---|---|
| 의존성별 ThreadPool 벌크헤드 (`core-thread-pool-size` = `max-thread-pool-size`) | 40 | 리틀의 법칙(아키텍처 5-6): 400 RPS × 평시 p99 51ms ≈ 20.4, 여유 2배 ≈ 41 → 40. 기준선 400 RPS의 의존성별 커넥션 사용 중 최댓값 14~16, 공유 풀 활성 최댓값 52~57(다섯 합)과 비교해도 2.5배 이상이다 |
| 대기열 (`queue-capacity`) | 0 | 아키텍처 5-6 "넘는 요청은 기다리지 않고 바로 거절한다". 0이면 `SynchronousQueue`라 빈 스레드가 없으면 바로 거절된다 |
| `keep-alive-duration` | 기본값(20ms) 그대로 | core = max라 쓰이지 않는다 |
| 커넥션 풀 라우트당 최대 (post, user, recommendation, ad) | 40 (20에서 올림) | 벌크헤드 크기와 같게 둬 커넥션을 기다리는 일이 없게 한다. 기다림은 벌크헤드 한 곳에서만 일어난다 |
| like 커넥션 풀 | 50 | 읽기 벌크헤드 40 + 토글 상한 10(결정 6) |

- recommendation이 S-02처럼 상한까지 끄는 동안 필요한 동시 호출은 400 × 0.2s = 80이라 절반은 `bulkhead_full`로 바로 거절된다. 거절은 서킷에 세지 않지만, 벌크헤드에 들어간 호출의 타임아웃만으로 서킷이 열린다(결정 7).
- 스레드 수: 벌크헤드 5 × 40 = 200 + Tomcat 200. 기준선의 공유 풀 100보다 많지만 평시에는 대부분 놀고, feed-service 메모리 상한 1GB에서 스레드 스택(기본 1MB 예약, 실제 사용은 훨씬 적다)으로 문제 될 크기가 아니다. 실험 리포트의 메모리·CPU로 확인한다.
- 의존성별 풀 크기를 바꾸려면 `weave.http-client.max-connections-per-route`(기본)와 `weave.http-client.groups.<그룹>.max-connections`(그룹별 덮어쓰기)를 둔다.
- MVP의 공유 풀 설정 `spring.task.execution.pool.core-size: 100`은 하위 서비스 호출에 더는 쓰이지 않으므로 지운다(따로 커밋).

### 6. 좋아요 토글

- 토글은 요청 스레드에서 동기로 부른다(지금처럼). 같은 `like` 서킷 브레이커를 거치고, 벌크헤드는 Semaphore `like-toggle`(최대 동시 10, 기다리지 않음 `max-wait-duration: 0`)이다. TimeLimiter는 쓰지 않는다. 동기 호출이라 상한을 걸려면 다른 스레드로 넘겨야 하는데, 응답 타임아웃(like 그룹 100ms)이 이미 같은 역할을 한다.
- 서킷을 읽기와 같이 쓰는 이유: 같은 like-service의 건강 상태다. 읽기가 실패해 서킷이 열렸는데 토글은 보내는 것은 PRD 3장 like의 `UNKNOWN`(누를 수 없게 한다)과도 어긋난다.
- **토글 상한 10의 근거:** 실험 세트(S-01~S-09)는 토글에 부하를 주지 않아 측정값이 없다. 토글이 like 커넥션을 읽기 몫(40)까지 쓰지 못하게 커넥션 풀을 10 더 두고 그만큼만 허용한다. 토글 부하는 F-003의 S-11에서 처음 재므로 그때 다시 정한다.
- **응답 타임아웃 100ms가 토글에 충분한지:** 토글은 MySQL에 쓴다. 구현 작업에서 Compose 환경에 토글을 1,000번 보내 p99를 잰다. 50ms를 넘으면(상한의 절반) 값을 넣지 않고 멈춘다. 토글만 다른 타임아웃이 필요하면 그룹을 나눠야 해서 범위가 바뀌기 때문이다.
- **측정 결과 (tasks 4.1, 같은 환경, S-01 뒤 무부하로 1,000번 순차, 사용자 1~1,000·게시글 1~20):** 1,000번 모두 200. feed-service가 잰 like-service 토글 호출은 p50 25ms, p99 약 60ms(히스토그램 보간), 89.48ms 초과 1건, 111.85ms 초과 0건이다. 클라이언트 쪽에서 잰 값은 p50 26.5ms, p99 61.6ms, 최대 135ms. p99가 50ms를 넘어 멈췄다. **사람이 정한 것: 100ms를 유지하고 판단 기준을 "상한에 걸리는 비율 0.1% 이하"로 고친다.** 토글 실패는 피드 SLO에 들지 않고, 그룹을 나누면 이 change의 범위가 커진다. 토글은 MySQL에 쓰므로 부하가 있을 때는 더 느릴 수 있다. F-003의 S-11(좋아요 몰림 부하)에서 다시 재고, 넘으면 그때 쓰기 그룹을 나눈다.
- 토글은 멱등하지 않다. 응답 타임아웃으로 feed-service는 실패를 돌려줬지만 like-service는 반영했을 수 있다(PRD S-10). 이 change는 재시도를 하지 않아 상태가 두 번 뒤집히지는 않는다. 정합성은 F-003이 다룬다.

### 7. 서킷 브레이커 설정

| 값 | 설정 | 근거 |
|---|---|---|
| `sliding-window-type`, `sliding-window-size` | COUNT_BASED, 100 | 400 RPS에서 의존성마다 0.25초 분량이다. S-02에서 벌크헤드에 들어간 호출만 세도 초당 40 ÷ 0.2s = 200번이 상한에 걸려 0.5초 안에 창이 찬다. 시간 기반보다 RPS에 덜 기대고, 창이 작을수록 묶이는 시간이 짧다 |
| `minimum-number-of-calls` | 100 | 창과 같게 둬 창이 차기 전에는 열지 않는다. 시작 직후(JIT 준비 전) 첫 호출 몇 개의 타임아웃으로 열리지 않게 한다 |
| `failure-rate-threshold` | 50% | 기준선 S-01의 의존성별 호출 실패율은 0이다. 평시에 열릴 일이 없고, S-05(like 에러율 50%)에서 "실패가 이어지면 열린다"(PRD 4장)가 문턱 근처에서 열렸다 닫혔다 하는 모습으로 보인다 |
| `slow-call-duration-threshold`, `slow-call-rate-threshold` | 상한의 80%(80ms, 160ms), 50% | 아키텍처 5-6 "응답 타임아웃보다 조금 짧게". 상한에 걸리기 전에 느려지는 중을 잡는다. 결정 3의 측정에서 이 경계를 넘는 평시 비율도 함께 확인한다 |
| `wait-duration-in-open-state` | 5s | S-08 복구 시간 목표 20초(결정 10)에서 거꾸로 정했다. 장애를 거둔 뒤 최대 5초 열려 있고 반열림 시험 호출이 바로 이어져, 거둔 뒤 첫 10초 구간 안에 닫힌다. 열린 동안 죽은 서비스로 가는 호출은 5초에 시험 호출 10개뿐이다 |
| `permitted-number-of-calls-in-half-open-state` | 10 | 400 RPS에서 25ms 분량이라 복구를 늦추지 않으면서, 문턱 50%를 우연이 아니라 다섯 번 이상의 실패로 판단한다 |
| `automatic-transition-from-open-to-half-open-enabled` | true | 요청이 없어도 열림 유지 시간이 지나면 반열림이 된다(`call-isolation` "복구하면 서킷이 닫힌다") |

**실패로 세는 것**(`record-exceptions`, `ignore-exceptions`):

| 예외 | 서킷 | `cause` |
|---|---|---|
| `TimeoutException`(TimeLimiter), 응답 타임아웃(`SocketTimeoutException`이 원인인 `ResourceAccessException`) | 실패 | `timeout` |
| 연결 실패(`DownstreamFailure`의 `connect_failure`와 같은 판단) | 실패 | `connect_failure` |
| 그 밖의 `ResourceAccessException` | 실패 | `io_failure` |
| `HttpServerErrorException`(5xx) | 실패 | `http_<상태 코드>` |
| `HttpClientErrorException`(4xx, 429 포함) | 세지 않음 | `http_<상태 코드>` |
| `BulkheadFullException` | 세지 않음 | `bulkhead_full` |
| `CallNotPermittedException` | (서킷이 막은 호출) | `circuit_open` |

- 분류는 `DownstreamFailure.of`(실패 로그)와 서킷의 예외 판정이 같은 함수를 쓴다. 로그의 `cause`와 서킷이 센 것이 어긋나지 않게 한다. 서킷에는 `record-failure-predicate`로 그 함수를 넘긴다(`CircuitFailurePredicate`, tasks 2.4).
- Resilience4j는 실패 판정이 거짓인 예외를 성공으로 센다. 4xx는 하위 서비스가 응답한 것이라 성공으로 세도 되지만, 벌크헤드 거절은 부르지도 않은 호출이라 성공으로 세면 S-02처럼 절반이 거절될 때 실패율이 묽어진다. 그래서 `BulkheadFullException`은 `ignore-exceptions`로 아예 세지 않는다(tasks 2.4).
- 429는 아키텍처 5-5에서 "F-005 설계에서 정한다"였다. F-001에서는 PRD F-001 수용 기준("4xx는 세지 않는다")대로 세지 않고, F-005가 Retry-After 처리와 함께 다시 정한다.
- 커넥션 대기 시간 초과(`ConnectionRequestTimeoutException`)는 feed 쪽 자원 문제라 `io_failure`로 두되(feed-service `CLAUDE.md`), 결정 5대로라면 일어나지 않는다.

### 8. 벌크헤드 스레드의 traceId

- `ContextPropagator` 구현 하나(`TraceContextPropagator`)를 둔다. `retrieve`는 Micrometer Context Propagation의 `ContextSnapshotFactory`로 호출 스레드의 스냅샷을 뜨고, `copy`는 벌크헤드 스레드에서 `setThreadLocals()`로 연 scope를 스레드 로컬에 두고, `clear`는 그 scope를 닫는다. 관찰(스팬)과 MDC가 함께 넘어간다(아키텍처 7-3의 `applicationTaskExecutor`와 같은 수단).
- `resilience4j.thread-pool-bulkhead.configs.default.context-propagators`에 이 클래스를 등록한다. Resilience4j는 설정의 클래스를 인자 없는 생성자로 만든다.
- 먼저 전파 없이 끊기는 것을 재현한다: `context-propagators`를 빈 목록으로 덮어쓴 테스트(`FeedBulkheadTracePropagationOffTest`)에서 하위 서비스 요청의 `traceparent` traceId가 `X-Trace-Id`와 다른 것을 본다(F-004의 `FeedTracePropagationOffTest`와 같은 방식). 고친 뒤에도 이 테스트를 남긴다.
- Semaphore 모드(결정 11)는 호출을 `applicationTaskExecutor`에서 돌려 기존 `propagate-context`가 그대로 잇는다.
- 기존 `FeedTraceLogScenarioTest`(다섯 호출, post 뒤의 호출)와 `FeedCallFailureLogScenarioTest`(풀 스레드의 실패 로그)가 바뀐 경로에서 그대로 통과해야 한다.

### 9. 장애 대응 이벤트

- **실패 로그:** `downstream_call_failed`의 `cause`에 `timeout`, `circuit_open`, `bulkhead_full`, `budget_exhausted`를 더한다(결정 7 표). 필드와 남기는 곳은 그대로다.
- **서킷 상태 전이:** 레지스트리의 서킷마다 `eventPublisher.onStateTransition`에 `circuit_state_changed`(`dependency`, `from`, `to`) WARN 한 줄을 건다. 레지스트리에 나중에 생기는 서킷도 받도록 `onEntryAdded`에서 건다. 전이는 실패를 기록한 호출 스레드에서 일어나 그 요청의 MDC(`traceId`)가 있다. 열림 유지 시간이 지나 저절로 반열림이 되는 전이는 Resilience4j의 스케줄러 스레드라 `traceId`가 없다(스펙이 허용한다).
- 벌크헤드 거절과 타임아웃은 실패 로그의 `cause`로 남고 따로 이벤트 줄을 두지 않는다. 같은 일을 두 줄로 남기지 않는다.
- **지표:** 스타터의 Micrometer 연동(결정 2)을 쓴다. 지표를 직접 만들지 않는다.

### 10. 실험 세트의 바뀌는 곳

- **기대 결과 형식:** `expectations.json`의 시나리오 값은 `"meet"`, `"miss"`(두 기준 모두) 또는 `{"availability": "meet|miss", "p99": "meet|miss"}`다. 고도화 3:

  ```json
  { "stage": "고도화 3",
    "scenarios": { "S-01": "meet",
                   "S-02": { "availability": "miss", "p99": "meet" }, ... "S-09": { ... } } }
  ```

  S-02~S-09가 모두 같은 이유: F-001만으로는 실패한 의존성이 피드를 500으로 만든다(가용성은 기준선과 비슷하다). 대신 모든 실패가 시간 상한과 예산 안에서 끝나 p99는 300ms 안이어야 한다. 기준선에서 이미 p99를 지키던 S-04~S-07, S-09도 `meet`으로 적어, 다음 단계에서 p99가 나빠지면 실험 세트가 잡게 한다.
- **판정(`lib/verdict.sh`):** k6 threshold 이름으로 기준을 가른다(`availability{scenario:measure}`와 보내지 못한 요청을 넣은 가용성은 가용성, `feed_latency{scenario:measure}: p(99)<…`는 p99). 출력 JSON에 `criteria: {availability: {met, expectation}, p99: {met, expectation}}`와 기대와 다른 기준 목록 `mismatchedCriteria`(그 밖의 측정 구간 threshold가 넘으면 `other`)를 더하고 `sloMet`, `expectation`(문자열이면 그대로, 객체면 그대로 객체), `matchesExpectation`, `verdict`, `failedThresholds`는 지금 뜻 그대로 둔다. `run-all.sh` 판정 표의 "기대" 열은 객체면 `가용성 miss · p99 meet`처럼 적는다.
- **리포트:** "공유 풀 활성·대기열"을 의존성별 벌크헤드 사용 중 스레드·대기열(`resilience4j_bulkhead_thread_pool_size`와 활성 스레드, `..._queue_depth`. 이름은 결정 2의 확인 결과)로 바꾼다. 서킷 상태는 `resilience4j_circuitbreaker_state`의 `query_range`(5초 간격)에서 값이 1인 상태를 시간순으로 읽어 바뀔 때만 이어 적는다(`CLOSED → OPEN → HALF_OPEN → CLOSED`). 5초보다 짧게 머문 상태는 놓칠 수 있다고 리포트 설명에 적는다. 리포트 로직의 이 부분은 `lib/circuits.sh`로 떼어 미리 만든 질의 결과로 테스트한다(인자와 출력은 `ExperimentCircuitStateScenarioTest` KDoc)(add-fault-experiments 결정 13과 같은 방식).
- **대시보드:** "서킷·벌크헤드" 행(아키텍처 7-4)에 의존성별 서킷 상태, 벌크헤드 사용 중·최대 스레드, 벌크헤드 거절 수(`weave_bulkhead_rejected_calls_total{name}`, 아래 Open Questions에서 정한 것), 시간 상한 초과 수를 더한다. 자원 사용량 행의 공유 풀 패널 두 개는 지운다.
- **S-08 복구 시간 목표 20초:** 기준선 40초의 절반. 결정 7의 열림 유지 5초 + 시험 호출로 거둔 뒤 첫 10초 구간 안에 닫힐 것으로 보고, 10초 구간 하나만큼 여유를 둔다. PRD 10장 "남은 것"에서 정한 것으로 옮긴다. 실험 세트가 복구 시간을 기준으로 판정하지는 않는다(SLO만 본다). 결과 문서에 목표와 실제 값을 함께 적는다.
- **MVP를 전제로 한 Compose 시나리오:** 스펙 델타대로 바꾼다. "Tomcat 스레드가 모두 묶여도 긁힌다"는 격리를 넓힌 설정이 있어야 만들 수 있어, feed-service에 `SPRING_APPLICATION_JSON: ${FEED_SPRING_APPLICATION_JSON:-{}}`를 넘겨 둔다(compose.yaml). `run.sh`는 feed-service를 `docker compose restart`로 다시 띄워 컨테이너의 환경을 그대로 두므로, 변수는 컨테이너를 새로 만들 때(`up -d --force-recreate --no-deps feed-service`) 들어간다. 테스트는 변수를 주고 feed-service를 새로 만든 뒤 `run.sh`를 돌리고, 끝나면 변수 없이 다시 만든다(`ComposeEnvironment.recreateFeedService`). 이 변수는 결정 11의 비교 실험에도 쓴다. "시간 초과된 요청"은 `docker compose pause feed-service`로 만든다.
- **준비 실행 (tasks 4.7에서 더함):** 막 띄운 하위 서비스가 느려 서킷이 열리면, 열린 동안 트래픽이 가지 않아 데워지지 못한 채 열림·닫힘을 되풀이한다(결정 3의 4.7 측정, 400 RPS에서 약 100초). `run-all.sh`는 첫 시나리오 전에 `run.sh S-01`을 같은 `--rps`·`--warmup`과 측정 1분으로 한 번 돌려 하위 서비스를 데운다. 판정 표와 종료 코드에 넣지 않고 결과 폴더만 남는다. `run.sh`는 feed-service만 다시 띄우므로 데운 하위 서비스는 남는다. 이미 데운 환경이면 `--skip-warm-up`. 운영에서 같은 고리를 푸는 방법(서킷을 닫힌 채 부하를 천천히 올리는 slow-start 등)은 이 change에서 다루지 않고 이후 단계의 후보로 남긴다.
- **Compose 테스트의 첫 요청:** 막 뜬 feed-service는 첫 호출이 100ms 상한에 걸릴 수 있다(연결 수립, 클래스 로딩). 시나리오를 확인하기 전에 준비 요청을 몇 번 보내는 도우미를 Compose 테스트 지원 코드에 둔다. `run.sh`는 준비 구간이 같은 역할을 한다.

### 11. 가상 스레드 비교 (S-02 한 번)

- `weave.isolation.bulkhead-type: thread-pool`(기본) | `semaphore`. `semaphore`면 `IsolatedCalls`가 호출을 `applicationTaskExecutor`에서 돌리고 Semaphore 벌크헤드(`resilience4j.bulkhead.instances.<의존성>`, 최대 동시 40, 기다리지 않음)로 감싼다. 같이 `spring.threads.virtual.enabled: true`를 주면 Boot가 Tomcat과 `applicationTaskExecutor`를 가상 스레드로 바꾼다.
- 상한을 같은 40으로 두는 이유: 비교하려는 것은 격리 방식(전용 플랫폼 스레드 풀 vs 가상 스레드 + 허가 수)이지 크기가 아니다.
- 두 구성으로 `experiments/run.sh S-02`를 기본 부하(400 RPS, 준비 1분, 측정 5분)로 돌린다. 가상 스레드 구성은 `FEED_SPRING_APPLICATION_JSON='{"weave.isolation.bulkhead-type":"semaphore","spring.threads.virtual.enabled":true}' docker compose up -d --force-recreate --no-deps --wait feed-service`로 만든 뒤 돌리고(결정 10), 끝나면 변수 없이 다시 만든다.
- 비교 항목: 피드 p50·p99, post·user·like 호출 p99(격리 효과), Tomcat 사용 중 스레드(가상 스레드면 의미가 달라 적는 방식을 결과 문서에 밝힌다), feed-service CPU·메모리(`jvm_memory_used_bytes`), 벌크헤드 거절 수. 결과는 `docs/experiments/call-isolation.md`의 한 절에 적는다. 기본 구성은 바꾸지 않는다(proposal).
- 가상 스레드에서 블로킹 I/O가 캐리어 스레드를 고정(pinning)하는지는 JDK 25에서 `synchronized` 고정이 풀렸으므로 따로 다루지 않는다. 결과가 이상하면 `jdk.VirtualThreadPinned` 이벤트를 본다.

### 12. 설정값 위치와 덮어쓰기

- 모든 값은 feed-service `application.yml`에 근거 주석과 함께 둔다(`resilience4j.*`, `spring.http.serviceclient.<그룹>.*`, `weave.http-client.*`, `weave.feed.time-budget`, `weave.isolation.*`). Boot의 느슨한 바인딩으로 환경 변수(예: `RESILIENCE4J_TIMELIMITER_INSTANCES_RECOMMENDATION_TIMEOUTDURATION`)나 `SPRING_APPLICATION_JSON`으로 덮어쓴다. 아키텍처 11장 표를 이 change에서 채운다.
- 값을 넣는 커밋은 의존성별·종류별로 나눈다: 타임아웃, 예산, 벌크헤드와 커넥션 풀, 서킷 문턱. 커밋 본문에 근거(위 표의 측정값과 계산)를 적는다(커밋 컨벤션).

### 13. 테스트를 두는 곳

| 무엇 | 어디 | 방식 |
|---|---|---|
| 실패 분류(`cause`, 서킷이 세는지) | `DownstreamFailureTest`(기존)에 더한다 | 단위 |
| 예산 계산(남은 시간, 0 이하에서 부르지 않음) | `FeedAssembler` 단위 테스트 | 단위, 시계를 받는다 |
| `call-isolation` 시나리오(상한, 예산, 벌크헤드, 서킷 전이, 토글, 지표, 환경 변수 덮어쓰기) | feed-service `FeedCallIsolationScenarioTest` 등 | `Integration`. WireMock(지연, 무응답 `withFault`·고정 지연, 503·429·404), 시나리오마다 `@SpringBootTest(properties)`로 값을 정한다. 서킷이 저절로 반열림·닫힘이 되는 것은 `eventually` |
| 벌크헤드 traceId(이어짐·끊김) | `FeedBulkheadTracePropagationOffTest`, 기존 trace 테스트 | `Integration` |
| `feed-aggregation` 델타 | 기존 `FeedAggregationScenarioTest`, `FeedCallFailureLogScenarioTest` | `Integration` |
| 판정·리포트 계산(기준별 기대, 서킷 상태 이어 적기) | e2e `ExperimentVerdictScenarioTest`(기존) 등 | `Integration`, 픽스처 |
| 프록시 경로, 짧게 돌린다, 시간 초과된 요청, Tomcat, 대시보드 | e2e `Compose` 태그 테스트(기존) | `./gradlew :e2e:composeTest` |

- **테스트 환경의 기본값:** 격리 값을 단언하지 않는 기존 테스트(WireMock 첫 호출, 400ms 지연을 쓰는 "서로 기다리지 않는다" 등)가 100ms 상한에 흔들리지 않게 `feed-service/src/test/resources/config/application.yml`에 넉넉한 값(상한·응답 타임아웃 2초, 예산 5초)을 둔다. Boot는 `classpath:/config/`를 `classpath:/`보다 우선해 함께 읽으므로 `src/main/resources/application.yml`을 가리지 않고 값만 덮는다. 격리를 확인하는 테스트는 `@SpringBootTest(properties)`로 값을 직접 준다(가장 우선한다). 실제 값은 실험 세트가 확인한다.
- e2e의 종단 간 테스트(`FeedEndToEndScenarioTest`, bootJar 프로세스)도 막 뜬 JVM의 첫 호출이 흔들리지 않게 feed-service 프로세스에 같은 넉넉한 값을 명령행 인자로 준다(`ServiceProcesses`).
- **기존 테스트를 고치는 작업:** 하네스 훅이 Ralph 루프 중에는 이미 커밋된 테스트를 못 고치게 막는다. tasks 1장 중 기존 테스트를 고치는 작업은 대화형 진행(`/opsx:apply`)으로 하고, 나머지는 Ralph로 돌린다.
- 테스트 이름에 `F-001`을 넣는다. F-004 수용 기준을 함께 채우는 traceId·이벤트 로그 테스트는 `F-001`과 `F-004`를 함께 넣는다.

### 14. 영향을 받는 시나리오와 확인 방법

| 시나리오 | 기준선 | 고도화 3에서 기대하는 것 | 확인 |
|---|---|---|---|
| S-01 | 모두 지킴 | 그대로 지킨다. 평시 서킷 전이 0, 타임아웃 거의 0 | 실험 세트, 리포트의 서킷 상태(`CLOSED`만) |
| S-02, S-03 | p99 5,002ms, Tomcat 200 고갈 | p99 300ms 안, Tomcat 사용 중 스레드가 평시 수준, post·user·like 호출 p99가 기준선 S-01 근처, recommendation 서킷 열림. 가용성은 0 근처(F-002 전) | 실험 세트, `call-isolation.md` 비교 표 |
| S-04, S-05, S-06 | p99는 지킴, 가용성 0~0.5 | p99 유지. S-05는 like 서킷이 열렸다 닫혔다 한다 | 실험 세트 |
| S-07, S-09 | p99는 지킴 | p99 유지, ad 서킷은 닫혀 있다(429는 세지 않는다) | 실험 세트 |
| S-08 | 복구 40초 | p99 유지, 복구 20초 이하(목표) | 리포트의 복구 시간 |

## Risks / Trade-offs

- [평시 꼬리 지연이 100ms 상한에 걸려 S-01 가용성이 깎인다] → 결정 3의 사전 측정(0.01% 문턱)으로 값을 넣기 전에 확인하고, 넘으면 멈춘다. 값을 넣은 뒤 S-01을 다시 돌려 타임아웃 수를 적는다.
- [시간 상한이 끝낸 호출의 스레드는 응답 타임아웃까지 남는다] → 응답·연결 타임아웃을 상한과 같게 둬 최대 약 2배(상한 + 응답 타임아웃)로 묶는다. S-03에서 벌크헤드 사용 중 스레드가 40을 넘지 않는 것으로 확인한다.
- [S-05처럼 문턱 근처의 에러율에서 서킷이 열렸다 닫혔다 한다] → 기대한 모습이다(PRD 4장). 열린 동안은 like를 부르지 않아 오히려 실패가 빨라진다. 등급별 처리(F-002)가 들어와야 사용자에게 의미가 생긴다.
- [S-09의 10초 장애 뒤 서킷이 최대 5초 더 열려 있다] → 고도화 3에서는 가용성이 어차피 `miss`다. F-005(재시도)가 이 구간을 다시 다룬다.
- [Resilience4j 2.4.0이 Boot 4.1.2에서 안 될 수 있다] → 결정 2를 첫 작업으로 해 막히면 바로 멈춘다.
- [벌크헤드 스레드가 200개로 늘어 메모리·CPU를 더 쓴다] → 리포트의 CPU와 비교 실험의 메모리로 확인한다.
- [리포트의 서킷 상태가 5초 간격 표본이라 짧은 상태를 놓친다] → 리포트에 그렇게 적고, 정확한 전이는 `circuit_state_changed` 로그(Loki)로 본다.
- [테스트 기본값을 넉넉히 덮어 실제 값의 버그를 테스트가 못 잡는다] → 실제 값은 `application.yml` 바인딩 테스트(레지스트리의 인스턴스 설정을 읽는다)와 실험 세트로 확인한다.

## Migration Plan

- 배포 대상은 로컬 Compose뿐이다. `./gradlew bootJar` 뒤 `docker compose up -d --build --wait`로 바뀐 feed-service를 띄운다.
- 되돌리기: 값은 환경 변수로 넓힐 수 있고(결정 12), 기능 전체는 change 브랜치의 커밋을 되돌린다. 하위 서비스와 데이터는 바뀌지 않는다.
- 실험 세트의 기대 결과(`expectations.json`)는 격리 구현과 같은 PR에서 고도화 3으로 바꾼다. 앞서 바꾸면 develop의 실험 세트가 실패한다.

## Open Questions

- (정함, tasks 1.2) 벌크헤드 거절 수는 Resilience4j 지표에 없다(`ThreadPoolBulkheadMetricNames`, `BulkheadMetricNames`에 거절 카운터가 없다). feed-service가 벌크헤드의 `onCallRejected` 이벤트로 Micrometer 카운터 `weave.bulkhead.rejected.calls`(태그 `name`, Prometheus `weave_bulkhead_rejected_calls_total`)를 센다. 대시보드 질의가 장애 없이도 시계열을 돌려주게 의존성마다 0부터 등록한다. 구현은 tasks 2.4다. tasks 2.1에서 Prometheus에 거절 지표가 없는 것과 대기열 0인 ThreadPool 벌크헤드가 차면 `onCallRejected`가 오는 것을 확인했다(결정 2의 확인 결과).
