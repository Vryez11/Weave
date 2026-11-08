# Design

## Context

- 왜 하는지는 proposal의 Why, 지켜야 할 동작은 specs(`retry-policy`, `call-isolation`·`graceful-degradation`·`fault-experiments` 델타)에 있다.
- 지금 feed-service의 하위 서비스 조회는 `IsolatedCalls.call`이 서킷 브레이커 → 호출 시간 상한(TimeLimiter) → ThreadPool 벌크헤드로 감싸고, 실패하면 실패 로그를 한 줄 남긴 뒤 실패를 넘긴다. `FeedAssembler`가 요청 시간 예산(`RequestBudget`)으로 기다리고 등급대로 대체한다(아키텍처 5-2, 6장). 재시도는 MVP부터 없고 HttpClient 5의 자동 재시도도 껐다(아키텍처 4-2).
- 아키텍처 12장 표대로 5-5의 429 처리와 Retry·RateLimiter 설정을 가져가고, 13장의 "429와 Retry-After 처리 방식"을 여기서 정한다. 5-4는 순서를 `Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead`로 정해 두었다. Retry-After 대기를 그 사이에 넣는다(결정 4).
- 근거로 쓰는 측정값은 [고도화 4 결과](../../../docs/experiments/graceful-degradation.md)와 [고도화 3 결과](../../../docs/experiments/call-isolation.md)다. 실험 RPS 400, 요청 시간 예산 250ms, 호출 시간 상한 post·user·like 100ms·recommendation·ad 200ms, 기준선 평시 호출 p99 51.0~53.7ms, 하위 서비스 평시 지연 중앙값 20ms·p99 50ms(장애 주입의 기본 분포, 주입한 5xx도 이 지연 뒤에 나간다), 서킷 판단 창 10초·최소 100호출·문턱 50%·열림 5초.
- 고도화 4의 S-07은 ad 호출 실패율 0.4968(요청 배율 0.997, 측정 5분에 429 약 6만 건, `20261107-141250-S-07`), S-09는 user·like 요청 배율 0.895·0.898, like degrade 0.1484(`20261107-142548-S-09`)였다.

## Goals / Non-Goals

**Goals**

- 순간 실패(한 번의 5xx, 연결 리셋)는 재시도로 넘기되, 재시도가 시간 예산을 넘기거나 장애 중 하위 서비스 요청을 정해진 비율 이상 늘리지 않는다.
- 외부 API(ad)는 다시 부르지 않고, 429의 Retry-After를 지키며, 계약 쿼터를 넘기 전에 스스로 멈춘다.
- 재시도와 대기가 로그(traceId)와 지표로 남고, 실험 세트가 두 기준(요청 배율, Retry-After)을 판정한다.

**Non-Goals**

- 좋아요 토글 재시도와 S-10(F-003), 요청 헤징, 인스턴스 사이의 예산·쿼터 나누기(PRD 8장).
- ad-service가 평시에 계약 쿼터를 강제하는 것(proposal "하지 않는 것").

## Decisions

### 1. 재시도는 `IsolatedCalls.call` 안, 가장 바깥에서 직접 돈다

```
Retry(재시도 루프) → Retry-After 대기 → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead → 호출
```

- **재시도 루프는 Resilience4j `Retry.executeCompletionStage`를 쓰지 않고 `IsolatedCalls`가 직접 돈다.** 설정(최대 시도 횟수, 간격 함수)은 스타터가 `resilience4j.retry.*`로 만든 `RetryRegistry`에서 의존성 이름으로 꺼낸다(`retryConfig.maxAttempts`, `retryConfig.intervalBiFunction`). 재시도 인스턴스가 없는 의존성(ad)은 다시 부르지 않는다(`RetryRegistry.find`).
  - **직접 도는 이유:** 다시 부를지는 실패 종류만으로 정해지지 않는다. 그 피드 요청의 남은 시간 예산(결정 3), 의존성의 재시도 예산(결정 5), "그 호출을 이미 다른 쪽이 끝냈는가"(요청 시간 예산)를 시도마다 봐야 한다. Resilience4j Retry의 예외 판정 함수는 요청 문맥을 받지 않고, 판정과 예산 차감을 함께 하면 판정이 부를 때마다 예산을 쓴다. 그리고 다시 부르지 않기로 했을 때 넘길 실패는 마지막 실제 실패여야 대체 로그와 실패 로그의 `cause`가 맞는다.
  - **다른 안:** Resilience4j Retry + 예외 판정 함수에서 ThreadLocal로 마감을 읽기. 판정이 비동기 완료 스레드에서 돌아 ThreadLocal이 없다. 쓰지 않는다.
  - Resilience4j Retry 인스턴스는 설정을 담는 데만 쓰므로 스타터가 내보내는 `resilience4j_retry_calls_total`은 늘 0이다. 재시도 지표는 결정 9의 `weave_retry_calls_total`이다(아키텍처 7-4에 적는다).
- **가장 바깥에 두는 이유(아키텍처 5-4):** 시도 하나하나가 서킷을 거쳐 실패로 집계되고, 서킷이 열리면 다음 시도가 `CallNotPermittedException`(재시도하지 않는 실패)으로 바로 끝나 재시도가 멈춘다. 시간 상한과 벌크헤드는 시도마다 다시 적용된다.
- **기다리는 동안 자원을 잡지 않는다.** 실패한 시도의 future가 끝나면(벌크헤드 스레드나 `isolation-timeout` 스레드) 다음 시도를 전용 스케줄러 스레드 하나(`isolation-retry`)에 간격만큼 뒤로 예약하고 돌아온다. 예약한 일은 부른 스레드의 trace 문맥과 MDC를 펼친 채(`completingIn`과 같은 방법) 다음 시도를 시작하므로, 다시 부른 호출의 `traceparent`와 로그에 같은 traceId가 이어진다(아키텍처 7-3).
- 실패 로그(`downstream_call_failed`)는 지금처럼 `call`이 돌려준 future를 실제로 끝낼 때 한 번만 남긴다. 다시 부른 시도의 실패는 `downstream_call_retried` 줄에 `cause`로 남는다(결정 8).
- 좋아요 토글(`callBlocking`)은 바꾸지 않는다. 다시 보내면 상태가 또 뒤집히는 토글이라 재시도하지 않는다(F-003이 상태 기반 API로 바꾼 뒤 그 change가 정한다).

### 2. 재시도하는 실패

`RetryPolicy.retryable(error)`가 `DownstreamFailure`와 같은 분류로 정한다.

| 실패 | 다시 부르나 | 이유 |
|---|---|---|
| 5xx | 예 | 하위 서비스의 순간 오류. 조회는 멱등하다 |
| `connect_failure` | 예 | 연결을 맺지 못해 요청이 나가지 않았다. 몇 ms 안에 끝나 예산을 거의 쓰지 않는다 |
| `io_failure` (응답 전 연결 끊김 등) | 예 | 같은 이유. HttpClient 기본 재시도도 이 경우(멱등한 요청)만 다시 보냈다(아키텍처 4-2) |
| `io_failure` 중 커넥션 풀 대기 초과(`ConnectionRequestTimeoutException`) | 아니요 | feed 쪽 자원이 모자란 것이라 다시 부르면 더 모자란다 |
| `timeout` (호출 시간 상한·응답 타임아웃) | 아니요 | 느린 의존성에 다시 보내면 부하만 키운다. 시간도 없다: user·like는 post 뒤 시작해 상한 100ms에 끝나면 남은 예산이 최대 150ms − post 지연이라, 다시 부른 시도의 상한(100ms)을 넣을 수 없다(post p99 51ms면 99ms 남음) |
| 4xx (429 포함) | 아니요 | 요청 자체가 거절됐다. 429는 결정 4 |
| `circuit_open`, `bulkhead_full`, `budget_exhausted`, `rate_limited`, `retry_after` | 아니요 | 우리 쪽이 막은 것이다 |

### 3. 남은 시간 예산 안에서만 다시 부른다

- 다시 부르기 전에 `남은 예산 ≥ 그 의존성의 호출 시간 상한 + 기다릴 시간`을 본다. 상한은 TimeLimiter 인스턴스 값(`timeLimiterConfig.timeoutDuration`)이다. 모자라면 다시 부르지 않고 결정 `deadline`으로 센다. 이 조건이면 다시 부른 시도도 마감 전에 끝나므로, 재시도가 피드 응답보다 늦게 하위 서비스를 부르지 않는다.
- **마감을 아는 방법:** `RequestBudget.call`이 `start()`를 부르는 동안만 요청의 마감을 ThreadLocal(`RequestDeadline`)에 둔다. Gateway → `IsolatedCalls.call`은 `start()` 안에서 동기로 불리므로, `call`이 시작할 때 마감을 읽어 그 호출의 재시도 루프에 들고 간다. Gateway 메서드 시그니처를 바꾸지 않는다(커밋된 테스트가 Gateway를 직접 부르고 목으로 만든다). 마감이 없으면(예산 없이 부른 경우) 시간 조건은 보지 않는다.
- 예약한 재시도가 돌기 전에 `call`이 돌려준 future가 이미 끝났으면(요청 시간 예산이 `TimeBudgetExhaustedException`으로 먼저 끝냈다) 다시 부르지 않는다.

### 4. Retry-After 대기 (`RetryAfterHolds`)

- **어디:** 재시도 루프 바로 안, 서킷 브레이커 바깥. 대기 중이면 서킷도 RateLimiter도 거치지 않고 `RetryAfterHoldException`(`cause` `retry_after`)으로 바로 끝난다. 서킷에 닿지 않으니 세지도 않는다.
- **언제 시작하나:** 시도가 429로 끝나면 응답 머리글 `Retry-After`를 읽는다(`RestClientResponseException.responseHeaders`). 초(0 이상의 정수)나 HTTP 날짜(RFC 1123)를 받는다. 대기 끝 시각은 `now + min(Retry-After, 최대 대기)`, 머리글이 없거나 읽지 못하면 `now + 기본 대기`다. 이미 대기 중이면 더 늦은 시각으로만 늘린다.
- **모든 의존성에 같은 규칙이다.** 429를 주는 것은 ad뿐이지만(PRD 3장) 의존성 이름으로 가르지 않는다. 내부 서비스가 429를 주게 되면 같은 이유로 지켜야 한다.
- **다른 안:** 서킷을 그 시간만큼 강제로 열기(`transitionToForcedOpenState`). 강제 열림은 시간이 지나도 저절로 돌아오지 않아 따로 되돌려야 하고, 되돌릴 때 서킷의 판단 창이 지워진다. 서킷 지표와 전이 로그에 429가 "장애"로 보이는 것도 맞지 않는다(5-5에서 429는 서킷에 세지 않기로 했다). 쓰지 않는다.
- **값(결정 11):** 기본 대기 1초, 최대 대기 60초.

### 5. 재시도 예산 (`RetryBudget`)

- 의존성마다 하나. 최근 창(10초)을 100ms 칸 100개로 나눠 칸마다 원래 호출 수와 보낸 재시도 수를 센다(`Clock` Bean으로 칸을 정한다). 원래 호출은 `call`을 부를 때마다 하나(서킷이 막은 호출 포함), 재시도는 실제로 다시 부를 때 하나다.
- 다시 부를 수 있는 조건: `창 안의 재시도 수 < max(최솟값, floor(비율 × 창 안의 원래 호출 수))`. 확인과 차감을 한 번에 한다(의존성마다 잠금 하나. 400 RPS × 의존성 4개에서 다툼은 무시할 만하다).
- **창 10초:** 서킷 판단 창과 같다. 예산이 장애를 보는 시간 단위가 서킷과 같아, 장애 초반 서킷이 열리기 전(약 5초)에 쓸 수 있는 재시도가 "그 전 10초 원래 호출의 10%"로 묶인다.
- **칸 100ms:** 칸 경계 때문에 창이 실제보다 최대 한 칸(1%) 길어지는 오차만 남긴다.
- **다른 안:** 토큰 버킷(Finagle `RetryBudget`처럼 원래 호출마다 토큰을 넣고 만료). 같은 성질이지만 "최근 10초 원래 호출의 몇 %"를 직접 세는 쪽이 판정(요청 배율)과 같은 말이라 설명과 테스트가 쉽다.
- **값(결정 11):** 비율 0.1, 최솟값 10(창마다).

### 6. ad 계약 쿼터와 RateLimiter

- ad 호출에만 Resilience4j RateLimiter(`resilience4j.ratelimiter.instances.ad`)를 둔다. 서킷 안, TimeLimiter 바깥이라(아키텍처 5-4) 열린 서킷이 막은 호출은 쿼터를 쓰지 않는다. 기다리지 않는다(`timeout-duration: 0`). 요청 스레드에서 ad 호출을 시작하므로 기다리면 요청 스레드가 묶인다.
- 막은 호출은 `RequestNotPermitted`(`cause` `rate_limited`)다. 서킷 안에서 나는 예외라 `ignore-exceptions`에 더해 성공으로도 세지 않는다(벌크헤드 거절과 같은 이유, 5-5).
- **계약 쿼터(가정, PRD 3장·10장에 적는다):** 클라이언트(feed-service 인스턴스)당 초당 600건, 1초 고정 창. 외부 API 쿼터는 우리 피크에 여유를 두고 계약한다고 보고, 실험 피크 400 RPS의 1.5배로 둔다.
- **한도 계산:** Resilience4j RateLimiter는 자기 시작 시각에 맞춘 주기마다 한도를 채운다. 제공자의 1초 창과 경계가 맞지 않으므로, 제공자의 어느 1초 창에도 주기가 최대 `1s ÷ 주기 + 1`개 걸친다. 주기 100ms면 11개라 `한도 × 11 ≤ 600` → **주기당 54개**(어느 1초 창에도 최대 594건). 평균으로는 초당 540건이라 피크 400 RPS의 1.35배이고, 주기마다로는 수요 40건에 54건이라 GC 멈춤 뒤에 몰린 요청 14건까지는 막지 않는다.
  - 주기 1초(한도 300, 평균 300건/초)는 피크를 받지 못하고, 주기 50ms(한도 28, 어느 1초 창에도 588건)는 100ms와 거의 같으면서 주기 하나에 몰린 요청을 받을 여유가 같은 비율로 줄어 이점이 없다.
- 평시(S-01)에 상한으로 막히는 ad 호출이 있는지는 ad degrade 비율로 본다(S-01의 degrade 기준이 판정한다, tasks 4.4).

### 7. 실패 분류 (`DownstreamFailure`)

| `cause` | 예외 | 서킷 | 재시도 |
|---|---|---|---|
| `rate_limited` | `io.github.resilience4j.ratelimiter.RequestNotPermitted` | 세지 않는다(`ignore-exceptions`) | 안 한다 |
| `retry_after` | `RetryAfterHoldException`(feed-service) | 닿지 않는다(서킷 바깥) | 안 한다 |

나머지 분류와 서킷 판정(`countsAsCircuitFailure`)은 그대로다. 429는 지금처럼 `http_429`이고 서킷에 세지 않는다.

### 8. 로그

| `event` | 언제 | 필드 | 스레드 |
|---|---|---|---|
| `downstream_call_retried` (WARN) | 다시 부르기로 정할 때마다 | `dependency`, `attempt`(다음 시도 번호, 2부터), `cause`(앞 시도), `waitMs` | 앞 시도를 끝낸 스레드. 부른 스레드의 MDC를 펼쳐 `traceId`가 붙는다 |
| `retry_after_hold_started` (WARN) | 대기 중이 아니던 의존성이 대기를 시작할 때 | `dependency`, `holdMs`, `holdUntil` | 429를 받은 시도를 끝낸 스레드. 같은 방법으로 `traceId` |
| `downstream_call_failed` | 지금과 같다(마지막 실패에 한 번). 막은 호출은 `cause` `retry_after`·`rate_limited` | | |

- 대기 중 막은 호출마다 대기 로그를 다시 남기지 않는다. 400 RPS에서 대기 동안 초당 수백 줄이 된다. 막은 호출은 실패 로그(서킷 열림과 같은 방식)와 지표로 센다.
- 재시도 로그 수는 예산이 묶는다(400 RPS에서 의존성마다 최대 초당 약 40줄).

### 9. 지표

| 지표 | 태그 | 언제 센다 |
|---|---|---|
| `weave.retry.calls` | `name`(의존성 5개), `outcome`(`retried`, `budget_exhausted`, `deadline`, `attempts_exhausted`) | 재시도할 수 있는 실패가 날 때마다 결정 하나. 재시도하지 않는 실패는 세지 않는다 |
| `weave.retry.after.rejected.calls` | `name` | 대기 중이라 막은 호출마다 |
| `weave.retry.after.hold.remaining` (게이지, 초) | `name` | 긁을 때의 남은 대기 시간. 대기 중이 아니면 0 |
| `weave.ratelimiter.rejected.calls` | `name`(RateLimiter가 있는 의존성, ad) | 상한으로 막은 호출마다 |

- 모두 시작할 때 0으로 등록한다(대시보드 질의와 판정이 장애 전에도 시계열을 본다, 고도화 3·4와 같은 방식). ad는 재시도 인스턴스가 없어 `weave.retry.calls`가 늘 0이다.
- Resilience4j RateLimiter 지표(`resilience4j_ratelimiter_available_permissions`, `..._waiting_threads`)는 스타터가 그대로 낸다.

### 10. 실험 세트

- **요청 배율 최댓값(`run.sh`):** 의존성마다 `max_over_time((sum by (instance) (increase(<하위 서비스 업무 요청>[20s])) / scalar(sum(increase(<feed /feed 요청>[20s]))))[<측정 시간 − 20>s:5s])`를 측정 끝 시각으로 질의한다. 측정이 40초보다 짧으면 측정 구간 전체의 요청 배율 하나로 둔다.
  - **20초 창인 이유:** 재시도 폭주는 장애 초반 서킷이 열리기 전 몇 초에만 생긴다. 서킷이 열린 동안은 요청이 거의 없어, 창이 길면 열린 구간이 폭주를 지운다(30초 창에서 예산 없는 S-09의 배율을 계산하면 약 1.0). 짧으면 Prometheus 수집 간격(5초) 때문에 `increase`가 표본 둘을 늘여 잡아, 예산 창(10초)의 재시도가 5초 안에 몰린 것을 1.2로 보게 된다. 20초 창이면 표본 4~5개(실제 15초 이상의 차)라 예산 창보다 길다.
  - **계산:** 400 RPS, user 장애 시작 시각을 0이라 하자. 예산이 없으면 시도마다 3번이라 실패가 초당 1,200건 쌓여 서킷이 약 2.5초에 열린다(창 10초의 성공 4,000건 − 400 × t = 1,200 × t). 0초에 끝나는 15초 구간의 하위 요청은 12.5초 × 400 + 2.5초 × 1,200 = 8,000건, 피드는 6,000건 → **1.33**. 예산이 있으면 그 전 10초 원래 호출의 10%(400건)만 더해져 (6,000 + 400) ÷ 6,000 = **1.07**.
- **요청 배율 상한 1.2 (PRD 10장에 정한다):** 재시도로 늘어나는 하위 서비스 요청을 20% 안으로 둔다(Envoy 재시도 예산의 기본값 20%와 같다). 구현의 예산(10%, Google SRE 책의 클라이언트 재시도 예산)은 그 절반이라, 위 계산의 1.07에 수집 간격과 칸의 오차를 더해도 상한 안이고, 예산이 없을 때의 1.33은 상한을 넘는다.
- **Retry-After 기준:** ad-service가 측정 구간에 돌려준 429 수(`http_server_requests_seconds_count{instance=~"ad-service:.*",status="429"}`의 증가)가 `(ceil(측정 시간 ÷ 시나리오의 요청 한도 창) + 1) × ad 동시 호출 상한`(`resilience4j_bulkhead_max_thread_pool_size{name="ad"}`) 이하. S-07(측정 5분, 창 10초, 상한 40)은 31 × 40 = 1,240이다. 고도화 4의 약 6만 건과 크게 갈린다.
  - 한도가 넘친 순간 이미 나가 있던 호출은 모두 429를 받을 수 있지만, 그 수는 동시 호출 상한(벌크헤드) 이하다. `Retry-After`는 창이 끝날 때까지 남은 초를 올림한 값이라 대기가 창 끝보다 먼저 풀리지 않는다(ad-service `AdQuota.tryAcquire`).
- **판정 입력 파일:** `run.sh`가 위 값을 결과 폴더의 `verdict-input.json`에 쓰고 `lib/verdict.sh`의 넷째 인자로 넘긴다. 형식은 e2e `ExperimentVerdictScenarioTest` KDoc에 정한다. 기대에 `requestRatio`나 `retryAfter`가 있는데 파일이 없으면 판정하지 못함(2)이다.
- **기대(고도화 5):** S-01 `{availability, p99, degradeRatio, requestRatio: meet}`, S-07 `{availability, p99, requestRatio, retryAfter: meet}`, 나머지 `{availability, p99, requestRatio: meet}`.
- **리포트:** `report.json`의 `dependencies.<의존성>`에 `requestRatioMax`와 `retries`(결정 네 가지), 최상위에 `adRejections`(`{"rejected": n, "allowed": n | null}`). `report.md`에 같은 값.
- **대시보드 행 "재시도·외부 API":** 의존성별 재시도 결정(초당, `outcome`별), 의존성별 요청 배율(20초, 1.2 선), ad Retry-After 남은 대기·막은 호출(초당), ad 상한으로 막은 호출(초당), ad-service가 돌려준 429(초당, 없으면 0으로 채운다).
- **문제 재현(비교 실행, tasks 5.2):**
  - 예산 없는 재시도: `FEED_SPRING_APPLICATION_JSON='{"weave.retry.budget.ratio":100}'`로 S-09를 돌려 요청 배율 최댓값과 재시도 수를 예산 있는 결과와 비교한다.
  - 스스로 멈추기: `{"resilience4j.ratelimiter.instances.ad.limit-for-period":18}`(어느 10초 창에도 18 × 101 = 1,818 ≤ S-07 한도 2,000)로 S-07을 돌려 ad-service의 429가 0건인지 본다. 고도화 4(Retry-After도 없음)와 기본 구성(Retry-After만)과 나란히 적는다.

### 11. 설정값

| 속성 | 값 | 근거 |
|---|---|---|
| `resilience4j.retry.configs.default.max-attempts` | 3 (post, user, like, recommendation) | Google SRE 책 22장의 "요청 하나에 최대 3번". 예산 계산: user·like가 post 중앙값(20ms) 뒤 시작해 빠른 실패(중앙값 20ms)를 두 번 겪어도 세 번째 시도의 상한(100ms)까지 약 190ms라 250ms 안이다. 느린 경우는 결정 3의 조건이 막는다 |
| `wait-duration`, `exponential-backoff-multiplier` | 10ms, 2 | 기다림 합(10 + 20ms, jitter를 넣어 최대 45ms)이 하위 서비스 평시 중앙값(20ms) 두 번 남짓이라 남은 예산을 시도 자체에 쓴다. 요청 안의 재시도는 순간 실패만 넘기고, 몇 초짜리 장애는 서킷(열림 5초)과 대체가 맡는다 |
| `randomized-wait-factor` | 0.5 | 같은 순간 실패한 요청(400 RPS에서 10ms에 4건, 장애 시작이면 수백 건)이 같은 순간 다시 몰리지 않게 기다림을 ±50% 펼친다 |
| ad 재시도 | 없음(인스턴스를 두지 않는다) | PRD 3장 |
| `weave.retry.budget.ratio`, `window`, `min-retries` | 0.1, 10s, 10 | 결정 5·10. 최솟값은 트래픽이 적을 때(초당 100건 미만)만 쓰인다. 창마다 10건(초당 1건)은 하위 서비스 처리량의 0.25%다 |
| `resilience4j.ratelimiter.instances.ad` | 주기 100ms, 주기당 54, 기다림 0 | 결정 6 |
| `weave.retry-after.default-hold` | 1s | 머리글이 없는 429는 쿼터 창 하나(계약 1초 창)만 쉰다 |
| `weave.retry-after.max-hold` | 60s | 이보다 긴 Retry-After는 쿼터 창이 아니라 일일 한도 소진이나 제공자 장애다. 그때는 1분마다 한 번 확인해 보는 비용(분당 요청 1건)이 광고를 오래 잃는 비용보다 작다고 본다. 측정이 아니라 판단이고 change 승인 때 확인한다 |

값은 근거 주석과 함께 `application.yml`에 넣고, 값마다 따로 커밋한다(커밋 컨벤션). 구조(속성 이름, 기본 구성)는 2장에서 먼저 두되, 장애 대응 값은 4장에서 넣는다.

### 12. 테스트

- **테스트 기본 설정은 재시도를 끈다.** `feed-service/src/test/resources/config/application.yml`에 재시도 인스턴스 넷의 `max-attempts: 1`을 둔다. 하위 서비스 실패 시 요청 수를 단언하는 기존 테스트(서킷, 대체, 실패 로그)가 재시도와 예산 상태(컨텍스트에 남는다)에 흔들리지 않게 한다. 재시도를 보는 시나리오만 `@SpringBootTest(properties)`로 켠다. 실제 값은 `RetrySettingsTest`(main 설정만 읽는다, `IsolationSettingsTest`와 같은 방식)와 실험 세트가 확인한다.
- **Retry-After 대기는 컨텍스트에 남는다.** 429를 주는 시나리오가 있는 클래스는 시나리오마다 대기를 지운다(`RetryAfterHolds.clear()`, 서킷 `reset()`과 같은 방식). 테스트 도구에서만 쓴다.
- 단위(Spring 없음): `RetryBudget`(창, 칸, 최솟값, `Clock`), `RetryAfter` 머리글 읽기(초, HTTP 날짜, 없음, 음수, 상한), `RetryPolicy.retryable`, `DownstreamFailure`의 새 분류, 재시도 루프("기다리는 동안 호출이 먼저 끝났다": 가짜 시도 함수와 스케줄러로).
- 통합(`@Tags("Integration")`, WireMock): `retry-policy` 시나리오 대부분. 첫 요청만 503인 것은 WireMock 시나리오 상태(`inScenario`)로 만든다. 간격은 WireMock이 기록한 요청 시각으로 잰다. 연결 실패는 듣지 않는 포트.
- 테스트 이름에 `F-005`를 넣고, 로그 시나리오는 `F-004`도 넣는다.
- e2e: 판정 픽스처(요약 + 판정 입력), Compose 테스트("리포트 항목", "광고 요청 한도를 넘는 짧은 실행", 대시보드 패널).

### 13. 영향받는 시나리오

| 시나리오 | 예상 |
|---|---|
| S-01 | 평시에는 재시도할 실패가 거의 없다. 요청 배율 ≤ 1, ad 상한으로 막히는 호출 0에 가깝다(degrade 기준으로 판정) |
| S-04, S-06 | 프록시를 끈 user·post는 `connect_failure`라 재시도하지만, 예산과 서킷이 묶는다. 요청 배율 ≤ 1.2 |
| S-05 | like 에러율 50%. 서킷이 닫힌 동안의 실패 일부를 재시도가 채워 like degrade가 조금 줄 수 있다(예산 10%만큼) |
| S-07 | 창마다 첫 429 뒤 창 끝까지 ad를 부르지 않는다. 429 수가 약 6만 → 수십~수백. ad degrade는 0.5 근처 그대로 |
| S-09 | 장애 초반 재시도가 예산만큼 나간 뒤 서킷이 연다. 요청 배율 최댓값 ≤ 1.2 |
| S-02, S-03, S-08 | 시간 상한 초과는 재시도하지 않아 고도화 4와 같다 |

## Risks / Trade-offs

- **재시도의 이득이 실험 세트에서는 작게 보인다.** 장애 시나리오의 실패는 대부분 몇 초 이상 이어지고 서킷이 금방 열려, 재시도가 살리는 응답은 예산(10%)만큼이다. 이득은 순간 실패를 흉내 낸 통합 테스트로 보이고, 실험 세트는 "늘어나는 요청을 묶었다"를 보인다. 결과 문서에 그대로 적는다.
- **예산과 쿼터는 인스턴스마다다.** 인스턴스가 N개면 하위 서비스가 받는 재시도는 인스턴스마다의 10%를 합친 것이라 비율은 같지만, ad 쿼터는 N배를 쓴다. 계약 쿼터가 클라이언트(인스턴스)당이라는 가정에 기댄다(PRD 8장).
- **Retry-After 동안 광고가 빠진다.** S-07에서 ad degrade는 0.5 그대로다. 쿼터를 지키는 대가이고, 광고는 선택 등급이다.
- **RateLimiter 주기 경계의 몰림.** GC 멈춤 뒤 주기 하나에 54건이 넘게 몰리면 그만큼 광고가 빠진다. S-01 degrade로 본다.
- **`RequestDeadline` ThreadLocal.** `start()` 안에서 동기로 `IsolatedCalls.call`을 부르는 구조에 기댄다. Gateway가 호출을 다른 스레드로 넘기게 바뀌면 마감을 잃는다(마감 없이 시도 횟수만 본다). feed-service `CLAUDE.md`에 적는다.

## Migration Plan

- 응답 계약은 그대로라 클라이언트 영향이 없다. 설정으로 끄려면 재시도 인스턴스의 `max-attempts: 1`, RateLimiter 주기당 한도를 크게, `weave.retry-after.max-hold: 0s`.

## Open Questions

- 없음. 계약 쿼터(초당 600건)와 Retry-After 최대 대기(60초), 요청 배율 상한(1.2)은 change 승인 때 확인한다.
