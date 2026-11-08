# 진행 메모: add-retry-policy

## 알아 둘 패턴
- 셸의 `ls`는 eza 별칭이라 경로 인자에서 깨진다. `command ls`를 쓴다. `grep --include=*.kt`는 zsh glob으로 깨진다. 따옴표로 감싼다.
- 승인(2026-11-08): proposal·design 그대로. ad 계약 쿼터 초당 600건(가정), Retry-After 최대 대기 60초, 요청 배율 상한 1.2. 끝나면 push·PR·릴리스(v0.6.0)까지 한다.
- 테스트 기본 설정(`feed-service/src/test/resources/config/application.yml`)은 재시도를 끈다(재시도 인스턴스 넷 `max-attempts: 1`, design 결정 12). 재시도를 보는 시나리오만 `@SpringBootTest(properties)`로 켠다. main 설정에 재시도 인스턴스 구조는 2.4에서, 값은 4.1에서 넣는다.
- 테스트는 feed-service 내부 클래스(예: `RetryAfterHolds`)를 쓰지 않고 HTTP·로그·지표로만 본다. Ralph 중에는 커밋된 `src/test/` 파일을 훅이 막으므로, 2장 구현이 따라야 할 이름(속성, 지표, 로그 필드)은 테스트 KDoc과 design 결정 4·5·8·9·11에 있는 이름을 그대로 쓴다.
  - 속성: `resilience4j.retry.instances.<의존성>.*`(Resilience4j 스타터 이름), `weave.retry.budget.ratio`·`min-retries`·`window`, `weave.retry-after.default-hold`·`max-hold`, `resilience4j.ratelimiter.instances.ad.*`.
  - 지표: `weave_retry_calls_total{name,outcome=retried|budget_exhausted|deadline|attempts_exhausted}`, `weave_retry_after_rejected_calls_total{name}`, `weave_retry_after_hold_remaining_seconds{name}`, `weave_ratelimiter_rejected_calls_total{name="ad"}`. 모두 시작할 때 0으로 등록(대시보드 Compose 테스트가 기대한다).
  - 로그: `downstream_call_retried`(`dependency`, `attempt`, `cause`, `waitMs`), `retry_after_hold_started`(`dependency`, `holdMs`, `holdUntil`). 실패 원인 `rate_limited`, `retry_after`.
- Retry-After 대기는 Spring 컨텍스트에 남는다. 429를 주는 시나리오는 끝에 대기가 풀릴 때까지 기다린다(정상 ad 스텁으로 광고가 채워질 때까지 `eventually`). 최대 대기 시간이 다른 시나리오는 다른 클래스로 나눈다.
- 실험 스크립트 형식의 기준(1.5·1.6에서 정했다): 판정 입력 파일(`verdict.sh` 넷째 인자, `verdict-input.json`)과 두 기준의 `failedThresholds` 문구는 `ExperimentVerdictScenarioTest` KDoc, `report.json`의 `requestRatioMax`·`retries`·`adRejections`는 `ExperimentRunComposeScenarioTest` KDoc, 판정 표 기대 열(`… · 요청 배율 meet · Retry-After meet`)은 `ExperimentRunAllComposeScenarioTest` KDoc. 픽스처 `expectations-stage5.json`, `verdict-input-S-0{7,9}-*.json`.
- Ralph 세션 권한에서는 셸 변수가 든 명령, `/bin/bash -n`, 실험 스크립트 직접 실행, 인라인 jq, `VAR=값 명령`이 막힌다(고도화 3·4 progress.md). 스크립트는 e2e `integrationTest`(`ExperimentScripts.run`)로 확인한다. `docker compose ...`는 된다. 구문 검사는 experiments/CLAUDE.md의 컨테이너 bash 방법.
- 결과는 `<모듈>/build/test-results/<태스크>/TEST-*.xml`에서 본다. 클래스 몇 개만 돌리려면 `--tests 'com.weave.feed.<클래스>'`.
- 전체 `:feed-service:integrationTest`에서 `ResilienceAutoConfigurationTest`가 `parsing HTTP/1.1 status line`으로 실패할 때가 있다(포트 겹침). 혼자 돌리면 통과한다.

- 재시도 시나리오 클래스는 재시도 예산을 넉넉히 준다(`weave.retry.budget.ratio=1`, `min-retries=1000`, `window=10s`). 예산 상태가 컨텍스트에 남아 4.2의 main 값이 들어가도 흔들리지 않게 하려는 것이다. 2.2 구현은 세 속성이 모두 있으면 예산을 켜고, 없으면 보지 않으면 된다.
- `weave_retry_calls_total{name,outcome}`은 피드를 부르기 **전에도** 표본이 있어야 한다(`FeedRetryDeadlineScenarioTest`, `FeedRetryBudgetScenarioTest`가 피드 전 값을 `shouldNotBeNull`로 읽는다). 2.4에서 결정을 세면서 0으로 등록하지 않으면 두 테스트가 2.6까지 실패한다. 0 등록은 2.4에서 같이 하는 게 낫다.
- 재시도 간격 시나리오는 Resilience4j 스타터의 `enable-exponential-backoff` + `enable-randomized-wait`(합치면 `ofExponentialRandomBackoff`)를 쓴다. 2.4의 재시도 루프는 `retryConfig.intervalBiFunction`으로 간격을 꺼내야 이 속성이 먹는다.

- ad RateLimiter 인스턴스는 속성(`resilience4j.ratelimiter.instances.ad.*`)만 주면 스타터가 만들고 `resilience4j_ratelimiter_available_permissions{name="ad"}`를 낸다(구현 전에도). 주기는 인스턴스를 만든 시각부터 센다. 2.5는 이 인스턴스를 `RateLimiterRegistry.find`로 꺼내 쓰면 된다(새로 만들면 지표와 주기가 어긋난다).

## 지금 실패하는 것이 정상인 테스트 (구현 전)
- **2.6 뒤(최신):** feed-service `test`·`integrationTest`는 모두 통과한다. 남은 실패는 e2e 쪽(`ExperimentVerdictScenarioTest`의 F-005, 3.2·4.5)과 Compose 테스트(5.3)뿐이다.
- **2.5 뒤(최신):** feed-service `integrationTest`에서 실패하는 것은 `FeedRetryLogScenarioTest`의 "Retry-After 대기를 시작한다"와 `FeedRetryMetricsScenarioTest` "지표가 있다"(둘 다 2.6) 둘뿐이다. 통합 테스트 결과의 skipped 110개는 태그가 없는 단위 테스트라 정상이다.
- **2.4 뒤(최신):** feed-service `integrationTest`에서 실패하는 것은 `FeedAdRateLimitScenarioTest`, `FeedAdRateLimitNextPeriodScenarioTest`(2.5), `FeedRetryLogScenarioTest`의 "Retry-After 대기를 시작한다"(2.6, `retry_after_hold_started` 줄), `FeedRetryMetricsScenarioTest` "지표가 있다"(2.6, `weave_retry_after_*`·`weave_ratelimiter_rejected_calls_total` 0 등록)뿐이다. 아래 1.x 목록의 나머지는 모두 통과한다.
- **1.4 뒤:** feed-service `integrationTest`: `FeedRetryLogScenarioTest` 둘(2.4 재시도 줄, 2.3+2.4 대기 줄. 로그는 2.6이 마무리), `FeedRetryMetricsScenarioTest` 둘(2.6, 0 등록은 2.4에서 해도 된다), `FeedRetryOverrideScenarioTest`(2.4. 레지스트리 단언은 지금도 통과하고 like 요청 수 1 ≠ 2로 실패).
- **1.3 뒤:** feed-service `integrationTest`: `FeedRetryAfterScenarioTest`의 "초로 준다", "HTTP 날짜로 준다", "머리글이 없다"(2.3+2.4), `FeedAdRateLimitScenarioTest`, `FeedAdRateLimitNextPeriodScenarioTest`(2.5). "대기가 끝난다"와 `FeedRetryAfterMaxHoldScenarioTest`는 지금도 통과한다(대기가 없어서 참).
- **1.6 뒤:** feed-service `integrationTest`: `FeedCircuitBreakerScenarioTest` "4xx는 세지 않는다"(2.4). e2e `integrationTest`: `ExperimentVerdictScenarioTest`의 F-005 여섯(3.2, "지금 단계의 기대"는 4.5). Compose 테스트(5.3)는 3장과 4.5 뒤에 통과한다.
- **1.2 뒤:** feed-service `integrationTest`: `FeedRetryScenarioTest`의 "한 번 실패한 뒤 성공한다", "계속 실패한다", `FeedRetryConnectFailureScenarioTest`, `FeedRetryCircuitOpensScenarioTest`, `FeedRetryIntervalScenarioTest`, `FeedRetryDeadlineScenarioTest`, `FeedRetryBudgetScenarioTest`, `FeedRetryBudgetWindowScenarioTest`(모두 2.4, 예산 둘은 2.2+2.4). 광고·토글·4xx·시간 상한·열린 서킷 다섯은 지금도 통과한다(재시도가 없어서 참인 동작을 지키는 테스트).

## 반복 기록

### 대화형 1.1, 1.5, 1.6 (2026-11-08)
- 1.1: `FeedDegradationScenarioTest` "광고가 속도 조절 응답을 준다"는 끝에 1.2초 기다려 대기가 풀리게 했다. `FeedCircuitBreakerScenarioTest`의 뒤 시나리오는 ad를 보지 않는다.
- 1.5: `ExperimentVerdictScenarioTest`의 `verdict()`에 `verdictInput` 인자. "판정 입력이 없다"는 표준 오류에 "판정 입력 파일"이 있어야 한다.
- 1.6: `ExperimentRuns.json(path)`를 더했다. "리포트 항목"은 S-02의 기대가 객체(`availability`, `p99`, `requestRatio`)라고 본다(4.5 뒤 통과). "광고 요청 한도를 넘는 짧은 실행"은 `allowed` = 4 × 40.

### 2026-11-08 12:05 - 1.2 `retry-policy`의 재시도 시나리오를 옮긴다
- 한 일: 재시도 시나리오 11개(멱등한 조회 넷, 재시도하는 실패 다섯, 간격, 남은 예산 모자람)와 재시도 예산 시나리오 둘을 Kotest 통합 테스트로 옮겼다. GIVEN이 다른 시나리오는 같은 파일의 다른 클래스로 컨텍스트를 나눴다.
- 바꾼 파일: `feed-service/src/test/kotlin/com/weave/feed/FeedRetryScenarioTest.kt`(클래스 다섯: `FeedRetryScenarioTest`, `FeedRetryConnectFailureScenarioTest`, `FeedRetryCircuitOpensScenarioTest`, `FeedRetryIntervalScenarioTest`, `FeedRetryDeadlineScenarioTest`), `FeedRetryBudgetScenarioTest.kt`(클래스 둘: `FeedRetryBudgetScenarioTest`, `FeedRetryBudgetWindowScenarioTest`).
- 확인: `integrationTest --tests 'com.weave.feed.FeedRetry*'`에서 13개 중 8개가 단언 실패(컴파일 오류 없음), 5개 통과. `:feed-service:test`, `ktlintCheck`, `openspec validate` 통과.
- 다음 반복이 알아 둘 것:
  - 로그가 "없다"는 단언은 그 피드의 `http_request` 줄을 기다린 뒤 본다(`awaitRequestLine`). 실패·재시도 로그는 그 줄보다 먼저 남아야 한다. 재시도 로그를 future가 끝난 뒤 다른 스레드에서 늦게 남기면 깨진다.
  - 토글 경로는 feed-service `POST /feed/likes/{postId}/toggle` → like-service `/likes/{postId}/toggle`(스펙의 `/posts/{postId}/like`는 같은 동작을 가리킨다).
  - "기다리는 동안 호출이 먼저 끝났다"는 2.4의 `gateway/RetryLoopTest`가 맡는다(아직 파일 없음).
---

### 2026-11-08 12:10 - 1.3 Retry-After와 ad 요청 수 상한 시나리오를 옮긴다
- 한 일: "Retry-After 동안 부르지 않는다" 다섯과 "ad 계약 쿼터를 넘기 전에 멈춘다" 둘을 Kotest 통합 테스트로 옮겼다. 최대 대기·상한 주기가 다른 시나리오는 다른 클래스로 나눴다. 테스트는 HTTP·로그·지표와 Resilience4j 레지스트리(서킷 상태)만 본다.
- 바꾼 파일: `feed-service/src/test/kotlin/com/weave/feed/FeedRetryAfterScenarioTest.kt`(`FeedRetryAfterScenarioTest`, `FeedRetryAfterMaxHoldScenarioTest`), `FeedAdRateLimitScenarioTest.kt`(`FeedAdRateLimitScenarioTest`, `FeedAdRateLimitNextPeriodScenarioTest`).
- 확인: 7개 중 5개 단언 실패(컴파일 오류 없음), 2개 통과. `:feed-service:test`, `ktlintCheck`, 타입 검사, `openspec validate` 통과.
- 다음 반복이 알아 둘 것:
  - Retry-After 클래스는 `beforeEach`·`afterEach`에서 스텁을 정상으로 되돌리고 피드에 광고가 채워질 때까지 `eventually`로 기다린다(`awaitAdsRestored`). 2.4 구현에서 대기가 풀리지 않으면 이 정리 단계에서 10초 뒤 실패한다.
  - "초로 준다"·"상한을 넘는다"는 ad 서킷을 최근 2개 중 50%에 열리게 둔다. 막은 호출(`retry_after`, `rate_limited`)이 서킷에 실패로 세이면 서킷 상태 단언이 깨진다. `retry_after`는 서킷에 닿지 않게, `RequestNotPermitted`는 서킷 `ignore-exceptions`에 넣는다(design 결정 4, 6).
  - "다음 주기"는 맞추는 피드 하나로 허용을 쓰고 `resilience4j_ratelimiter_available_permissions{name="ad"}`가 1로 돌아올 때(새 주기 시작)까지 기다린 뒤 시나리오를 돈다.
  - HTTP 날짜는 IMF-fixdate(`EEE, dd MMM yyyy HH:mm:ss 'GMT'`)로 보낸다. 2.3의 `RetryAfter.parse`는 이 형식을 읽어야 한다(`DateTimeFormatter.RFC_1123_DATE_TIME`이 읽는다).
---

### 2026-11-08 12:15 - 1.4 로그·지표·설정 시나리오를 옮긴다
- 한 일: "재시도와 대기 이벤트 로그" 둘, "재시도와 외부 API 지표" 둘, "환경 변수로 최대 시도 횟수를 바꾼다"를 Kotest 통합 테스트로 옮겼다.
- 바꾼 파일: `feed-service/src/test/kotlin/com/weave/feed/FeedRetryLogScenarioTest.kt`, `FeedRetryMetricsScenarioTest.kt`, `FeedRetryOverrideScenarioTest.kt`.
- 확인: 새 테스트 5개 모두 단언 실패(컴파일·컨텍스트 오류 없음). `:feed-service:test`, `ktlintCheck`, 타입 검사, `openspec validate` 통과.
- 다음 반복이 알아 둘 것:
  - 로그 숫자 필드(`attempt`, `waitMs`, `holdMs`)는 JSON 숫자든 숫자 문자열이든 통과한다(`longField`). `holdMs`는 정확히 2000이어야 한다(Retry-After 2초 그대로, 남은 시간으로 다시 계산하지 않는다). `holdUntil`은 값만 있으면 된다.
  - `retry_after_hold_started`의 `traceId`는 429를 받은 첫 피드의 것이어야 한다. 429 시도를 끝낸 스레드(벌크헤드 쪽)에서 남기면 `completingIn`처럼 부른 스레드의 MDC를 펼쳐야 한다.
  - `FeedRetryMetricsScenarioTest` "지표가 있다"는 피드 전에 의존성 5개 × (`weave_retry_calls_total` 결과 4개, `weave_retry_after_rejected_calls_total`, `weave_retry_after_hold_remaining_seconds`) + `weave_ratelimiter_rejected_calls_total{name="ad"}`를 본다. ad의 재시도 결정도 0으로 등록해야 한다.
  - `FeedRetryOverrideScenarioTest`는 `RetryRegistry.retry("like").retryConfig.maxAttempts == 2`도 본다(스타터 바인딩이라 지금도 참).
---

### 2026-11-08 12:14 - 2.1 실패 분류와 재시도 판정을 더한다
- 한 일: `DownstreamFailure.of`에 `rate_limited`(`RequestNotPermitted`), `retry_after`(`RetryAfterHoldException`)를 더했다. 서킷 판정은 그대로라 둘 다 세지 않는다. `RetryPolicy.retryable`은 5xx, `connect_failure`, 원인 사슬에 `ConnectionRequestTimeoutException`이 없는 `io_failure`만 참이다.
- 바꾼 파일: `feed-service/src/main/kotlin/com/weave/feed/gateway/DownstreamFailure.kt`(`RetryAfterHoldException(dependency, holdUntil: Instant)`도 여기, `unwrap`·`causeChain`을 `internal`로), `RetryPolicy.kt`(새), 테스트 `gateway/RetryPolicyTest.kt`, `gateway/DownstreamFailureRetryCauseTest.kt`(새), feed-service `CLAUDE.md` 분류 줄.
- 확인: gateway 단위 테스트 45개 통과(기존 `DownstreamFailureTest` 22개 그대로), `:feed-service:test`, `ktlintCheck`, 타입 검사, `openspec validate` 통과.
- 다음 반복이 알아 둘 것:
  - 2.3의 `RetryAfterHolds`는 대기 중이면 `RetryAfterHoldException(dependency, holdUntil)`을 던지면 된다. 2.4의 재시도 루프는 `RetryPolicy.retryable(error)`로 실패 종류를 보고, 시간 예산·재시도 예산은 따로 본다.
---

### 2026-11-08 12:25 - 2.2 재시도 예산을 만든다
- 한 일: `RetryBudget(ratio, minRetries, window, clock)`. 100ms 칸, 지금 칸 + 앞 `ceil(window ÷ 100ms)` 칸을 센다(창보다 최대 한 칸 길게, 짧게는 세지 않는다). `recordCall()`(원래 호출), `tryAcquireRetry()`(확인과 차감을 `synchronized` 하나로). `RetryProperties`(`weave.retry.budget.ratio`·`min-retries`·`window`, 모두 nullable)와 `Budget.newBudget(clock)`(하나라도 없으면 null = 예산을 보지 않는다). `IsolatedCalls`의 `@EnableConfigurationProperties`에 `RetryProperties`를 더했다(아직 쓰지 않는다).
- 바꾼 파일: `feed-service/src/main/kotlin/com/weave/feed/gateway/RetryBudget.kt`, `RetryProperties.kt`(새), `IsolatedCalls.kt`(애너테이션 한 줄), 테스트 `gateway/RetryBudgetTest.kt`(새, 9개).
- 확인: `RetryBudgetTest` 9개 통과, `:feed-service:test`, `ktlintCheck`, 타입 검사, `openspec validate` 통과. `FeedRetryBudgetScenarioTest`는 컨텍스트가 뜨고 지표 0 등록 단언에서 실패한다(2.4, 예상대로).
- 다음 반복이 알아 둘 것:
  - 2.4는 생성자에 `RetryProperties`와 `Clock`을 받아 의존성마다 `properties.budget.newBudget(clock)`으로 예산을 하나씩 만든다(null이면 예산 없이 다시 부른다). `call`마다 `recordCall()`(서킷이 막은 호출 포함), 실제로 다시 부르기 직전에 `tryAcquireRetry()`. false면 결정 `budget_exhausted`.
  - 단위 테스트의 시계는 `MovableClock`(실제 시간 + 이동)이 아니라 테스트 파일 안의 `ManualClock`처럼 손으로만 움직이는 시계를 쓴다. 칸 경계 단언이 실제 시간에 흔들리지 않는다.
---

### 2026-11-08 12:20 - 2.3 Retry-After 대기를 만든다
- 한 일: `RetryAfter.parse(header, now, defaultHold, maxHold)`(초·IMF-fixdate, 없음·읽지 못함·음수는 기본 대기, 지난 날짜는 0, 기본 대기도 최대 대기로 자른다, Long을 넘는 초는 상한까지). `RetryAfterHolds(clock)`: `check(dep)`(대기 중이면 `RetryAfterHoldException`), `hold(dep, duration)`(늘리기만, 대기를 새로 시작했을 때만 끝 시각을 돌려준다 = 대기 시작 로그 조건, 0 이하면 무시), `remaining(dep)`(게이지용). `RetryAfterProperties`(`weave.retry-after.default-hold`·`max-hold`, 둘 다 nullable: 없으면 기본 대기 0, 상한 없음)를 `IsolatedCalls`의 `@EnableConfigurationProperties`에 더했다(아직 쓰지 않는다).
- 바꾼 파일: `feed-service/src/main/kotlin/com/weave/feed/gateway/RetryAfter.kt`, `RetryAfterHolds.kt`, `RetryAfterProperties.kt`(새), `IsolatedCalls.kt`(애너테이션), 테스트 `gateway/RetryAfterTest.kt`(6), `gateway/RetryAfterHoldsTest.kt`(7, 시계 `HoldClock`).
- 확인: 새 단위 테스트 13개 통과, `ktlintCheck`, 타입 검사, `:feed-service:test`, `openspec validate`, `FeedRetryAfterMaxHoldScenarioTest`(컨텍스트) 통과.
- 다음 반복이 알아 둘 것:
  - 2.4: 시도가 429(`HttpClientErrorException`/`RestClientResponseException`, 상태 429)로 끝나면 `responseHeaders?.getFirst(RetryAfter.HEADER)`를 `RetryAfter.parse(..., clock.instant(), props.defaultHold, props.maxHold)`에 넘기고 `holds.hold(dep, d)`. 반환값이 null이 아니면 `retry_after_hold_started`(holdMs = `d.toMillis()`, holdUntil = 반환값). `check`는 던지므로 `call` 안에서 실패 future로 바꿔야 한다(서킷을 거치기 전).
  - 테스트 파일마다 private 시계 클래스 이름이 달라야 한다(같은 패키지에서 `ManualClock` 재선언 불가). 이번엔 `HoldClock`.
  - 기존 `FeedDegradationScenarioTest`·`FeedCircuitBreakerScenarioTest`는 `weave.retry-after.*`를 주지 않는다. 둘 다 `Retry-After` 초를 주므로 속성이 없어도(상한 없음) 동작한다.
---

### 2026-11-08 12:30 - 2.4 `IsolatedCalls.call`에 재시도 루프와 대기를 넣는다
- 한 일: `RetryLoop`(의존성마다, 결정 순서 = 재시도할 실패인가 → 시도 횟수 → 남은 시간(`마감 − 지금 ≥ TimeLimiter 상한 + 간격`) → 재시도 예산, `RetryOutcome` 넷)을 만들고 `IsolatedCalls.call`을 `RetryLoop.run` → `holds.check` → 서킷 → TimeLimiter → 벌크헤드로 감쌌다. 다음 시도는 `isolation-retry` 스케줄러에서 부른 스레드의 trace 문맥·MDC로 시작하고, 결과가 이미 끝났으면 보내지 않는다(그 뒤 끝난 시도의 실패는 결정으로 세지 않는다). 시도가 429면 `RetryAfter.parse` → `holds.hold`. `RequestDeadline`(ThreadLocal)을 `RequestBudget.call`이 `start()` 동안 둔다. `downstream_call_retried` 로그와 `weave.retry.calls`(5 × 4, 0부터)도 여기서 냈다(2.4의 확인 테스트가 필요로 한다). main `application.yml`에 `resilience4j.retry` 구조(`configs.default.max-attempts: 1`, 인스턴스 넷 `base-config: default`, ad 없음).
- 바꾼 파일: `feed-service/src/main/kotlin/com/weave/feed/gateway/RetryLoop.kt`, `RequestDeadline.kt`(새), `IsolatedCalls.kt`, `FeedAssembler.kt`(`RequestBudget.call`), `src/main/resources/application.yml`, 테스트 `gateway/RetryLoopTest.kt`(새, 8개), feed-service `CLAUDE.md` 첫 줄.
- 확인: `RetryLoopTest` 8개, `FeedRetry*`·`FeedRetryAfter*`·`FeedCircuitBreakerScenarioTest`(27개 중 2.6 몫 2개만 실패), 전체 `:feed-service:test`, `:feed-service:integrationTest`(2.5·2.6 몫 4클래스만 실패), `ktlintCheck`, 타입 검사, `openspec validate` 통과.
- 다음 반복이 알아 둘 것:
  - 2.5: RateLimiter는 `attempt` 람다의 `isolation.circuitBreaker.executeCompletionStage(limited)` 안, `limited`(TimeLimiter) 바깥에 끼운다. `Isolation`에 `rateLimiter: RateLimiter?`(`RateLimiterRegistry.find`)를 더하면 된다. `RequestNotPermitted`는 서킷 `ignore-exceptions`에도 더한다(지금은 `BulkheadFullException` 하나).
  - 2.6: 대기 시작 로그는 `IsolatedCalls.holdOnTooManyRequests`에서 `holds.hold(...)`의 반환값이 null이 아닐 때 남기면 된다. 이 함수는 시도 stage의 `whenComplete`에서 돌아 `completingIn`의 MDC가 펼쳐져 있지만, 확실히 하려면 `withMdc(callerMdc)`로 감싼다. `retry_after` 막은 호출 수는 `holds.check`가 던질 때 센다(`attempt` 람다). 게이지는 `holds.remaining(dep)`. 재시도 결정 카운터는 `IsolatedCalls.retryLoop`에 이미 있다.
  - 결정 순서에서 시간을 예산보다 먼저 본다(보내지 않을 재시도로 예산을 쓰지 않는다). 테스트 기본 설정(`max-attempts: 1`)에서는 재시도할 실패마다 `attempts_exhausted`가 하나씩 센다. ad는 인스턴스가 없어 늘 0이다.
---

### 2026-11-08 12:32 - 2.5 ad에 요청 수 상한을 둔다
- 한 일: `IsolatedCalls`가 `RateLimiterRegistry.find(<의존성>)`으로 RateLimiter를 꺼내(`Isolation.rateLimiter`, 없으면 null) 서킷 안, TimeLimiter 바깥에서 `executeCompletionStage`로 감싼다. 허용이 없으면 부른 스레드에서 `RequestNotPermitted`로 바로 끝나고 실패 로그 `rate_limited`, 재시도 없음. 서킷 `ignore-exceptions`를 목록으로 바꿔 `RequestNotPermitted`를 더했다. main `application.yml`에 `resilience4j.ratelimiter`(`configs.default.timeout-duration: 0`, `instances.ad: { base-config: default }`).
- 바꾼 파일: `feed-service/src/main/kotlin/com/weave/feed/gateway/IsolatedCalls.kt`, `src/main/resources/application.yml`, feed-service `CLAUDE.md` 첫 줄(순서).
- 확인: `FeedAdRateLimitScenarioTest`, `FeedAdRateLimitNextPeriodScenarioTest`, `FeedCircuitBreakerScenarioTest`, `FeedDegradationScenarioTest`, `FeedRetryAfter*` 통과. 전체 `:feed-service:integrationTest`는 2.6 몫 2개만 실패. `ktlintCheck`, 타입 검사, `:feed-service:test`, `openspec validate` 통과.
- 다음 반복이 알아 둘 것:
  - main의 `timeout-duration: 0`은 design 결정 6의 "기다리지 않는다"(요청 스레드가 묶인다)를 구조로 먼저 넣은 것이다. Resilience4j 기본 기다림이 5초라 비워 두면 위험해서다. 주기·주기당 한도는 비워 두어 Resilience4j 기본값(500ns마다 50, 사실상 무제한)이다. 4.3은 `limit-refresh-period: 100ms`, `limit-for-period: 54`만 근거와 함께 넣으면 된다(기다림 0은 이미 있다).
  - 2.6: `weave.ratelimiter.rejected.calls{name="ad"}`는 `rateLimiter.eventPublisher.onFailure { ... }`로 세면 된다(`executeCompletionStage`가 허용을 못 받으면 `onFailure` 이벤트를 낸다). 시작할 때 0으로 등록. RateLimiter가 있는 의존성만 등록하면 `name="ad"` 하나다.
---

### 2026-11-08 12:40 - 2.6 재시도·대기 로그와 지표를 낸다
- 한 일: `IsolatedCalls`에 `retry_after_hold_started`(WARN, `dependency`, `holdMs` = 파싱한 대기 그대로, `holdUntil` = `Instant.toString()`)를 `holds.hold`가 끝 시각을 돌려줄 때만 부른 스레드의 MDC(`withMdc(callerMdc)`)로 남긴다. `weave.retry.after.rejected.calls{name}`(`holds.check`가 던질 때), `weave.retry.after.hold.remaining{name}`(게이지, `baseUnit("seconds")` → Prometheus `_seconds`), `weave.ratelimiter.rejected.calls{name}`(RateLimiter가 있는 의존성만, `eventPublisher.onFailure`)을 시작할 때 0으로 등록한다. `downstream_call_retried`와 `weave.retry.calls`는 2.4에서 이미 냈다.
- 바꾼 파일: `feed-service/src/main/kotlin/com/weave/feed/gateway/IsolatedCalls.kt`.
- 확인: `FeedRetryLogScenarioTest`, `FeedRetryMetricsScenarioTest`, `FeedRetryOverrideScenarioTest` 통과. `:feed-service:ktlintCheck :feed-service:test :feed-service:integrationTest` 모두 통과(통합 스위트 75개 실패 0). `openspec validate` 통과.
- 다음 반복이 알아 둘 것:
  - 2.7 문서에 쓸 이름: 로그 `retry_after_hold_started`는 `IsolatedCalls.holdOnTooManyRequests`, 지표 넷은 `IsolatedCalls`(재시도 결정은 `retryLoop`, 나머지는 `holdRejections`와 `init`). feed-service `CLAUDE.md`는 2.7에서 고친다.
---
