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
