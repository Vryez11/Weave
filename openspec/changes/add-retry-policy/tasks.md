# Tasks

## 1. 준비와 시나리오 테스트

시나리오(WHEN/THEN)를 실패하는 테스트로 옮긴다. 구현이 없으니 새 동작을 단언하는 테스트는 `integrationTest`에서 실패하고, `test`(Stop 훅)는 통과해야 한다. 테스트 이름에 `F-005`를 넣고, 로그 시나리오는 `F-004`도 넣는다(design 결정 12).

**1.1, 1.5, 1.6은 이미 커밋된 테스트를 고치므로 Ralph 루프가 아니라 대화형(`/opsx:apply`)으로 먼저 끝낸다**(하네스 규칙 2-2의 훅). Ralph가 이 셋 중 체크되지 않은 작업을 만나면 구현하지 말고 progress.md에 적은 뒤 `<promise>BLOCKED</promise>`로 멈춘다.

- [x] 1.1 (대화형) `call-isolation`·`graceful-degradation` 델타를 기존 feed-service 테스트에 옮긴다.
  - 테스트 기본 설정(`src/test/resources/config/application.yml`)에 재시도 인스턴스 넷(post, user, like, recommendation)의 `max-attempts: 1`을 둔다(design 결정 12). 아직 인스턴스가 없어도 속성만으로는 컨텍스트가 뜬다.
  - `FeedCircuitBreakerScenarioTest`의 "4xx는 세지 않는다": ad 429에 `Retry-After: 60`, ad-service 요청 1개·recommendation 20개. 대기는 컨텍스트에 남지만 이 클래스의 뒤 시나리오는 ad를 보지 않는다.
  - `FeedDegradationScenarioTest`의 "중요·선택 등급 하나가 5xx": "최대 시도 횟수보다 많이 요청하지 않는다"(테스트 기본 설정에서 1). "광고가 속도 조절 응답을 준다"는 끝에 대기(1초)가 끝날 때까지 기다려 뒤 시나리오가 광고를 잃지 않게 한다.
  - 확인: `./gradlew :feed-service:integrationTest`에서 "4xx는 세지 않는다"만 새로 실패하고(ad 요청 20 ≠ 1), `./gradlew :feed-service:test`는 통과한다.
- [x] 1.2 `retry-policy`의 재시도 시나리오를 옮긴다.
  - `FeedRetryScenarioTest`: "멱등한 조회만 재시도한다"(넷), "재시도하는 실패"(다섯), "재시도 간격", "남은 예산이 모자라다". 재시도를 켜는 속성(`resilience4j.retry.instances.<의존성>.max-attempts`, `wait-duration` 등)과 서킷 설정을 클래스마다 준다. 첫 요청만 503은 WireMock 시나리오 상태로 만든다. 연결 실패는 듣지 않는 포트.
  - `FeedRetryBudgetScenarioTest`: "재시도 예산"(둘). 속성 이름은 `weave.retry.budget.ratio`, `min-retries`, `window`(design 결정 5, 11).
  - "기다리는 동안 호출이 먼저 끝났다"는 2.4의 단위 테스트(`gateway/RetryLoopTest`)로 둔다. 이 작업에서는 파일을 만들지 않는다.
  - 확인: 새 시나리오가 `integrationTest`에서 단언 실패(컴파일 오류가 아니다)하고 `test`는 통과한다.
- [x] 1.3 Retry-After와 ad 요청 수 상한 시나리오를 옮긴다.
  - `FeedRetryAfterScenarioTest`: "Retry-After 동안 부르지 않는다"(다섯). 속성 `weave.retry-after.default-hold`, `max-hold`. 대기는 컨텍스트에 남으므로 시나리오가 끝날 때 대기가 풀릴 때까지 기다린다(정상 ad 스텁으로 광고가 채워질 때까지 `eventually`). 최대 대기 시간이 다른 시나리오는 다른 클래스(컨텍스트)로 나눈다. 테스트는 feed-service 내부 클래스를 쓰지 않고 HTTP·로그·지표로만 본다(Ralph 중에는 커밋된 테스트를 고칠 수 없다).
  - `FeedAdRateLimitScenarioTest`: "ad 계약 쿼터를 넘기 전에 멈춘다"(둘). 속성 `resilience4j.ratelimiter.instances.ad.limit-for-period`, `limit-refresh-period`, `timeout-duration`.
  - 확인: 1.2와 같다.
- [x] 1.4 로그·지표·설정 시나리오를 옮긴다.
  - `FeedRetryLogScenarioTest`: "재시도와 대기 이벤트 로그"(둘). 이름에 `F-005`와 `F-004`.
  - `FeedRetryMetricsScenarioTest`: "재시도와 외부 API 지표"(둘). 지표 이름은 design 결정 9.
  - `FeedRetryOverrideScenarioTest`: "환경 변수로 최대 시도 횟수를 바꾼다"(`RESILIENCE4J_RETRY_INSTANCES_LIKE_MAXATTEMPTS=2`, `FeedIsolationOverrideScenarioTest`와 같은 방식).
  - 확인: 1.2와 같다.
- [x] 1.5 (대화형) 실험 세트의 판정 테스트를 델타대로 고친다.
  - `ExperimentVerdictScenarioTest`: 판정 입력 파일(넷째 인자) 형식을 KDoc에 정한다(`{"requestRatioMax": {<의존성>: 수|null}, "adRejections": {"rejected": 수|null, "allowed": 수|null}}`). "요청 배율 기준" 셋, "Retry-After 기준"의 두 판정 시나리오를 픽스처(`expectations-stage5.json`, `verdict-input-*.json`, 기존 요약 픽스처)로 더하고, "지금 단계의 기대"를 고도화 5로 바꾼다.
  - 확인: `./gradlew :e2e:integrationTest`에서 새·바뀐 판정 테스트만 실패하고 `./gradlew :e2e:test`는 통과한다.
- [x] 1.6 (대화형) Compose 테스트를 델타대로 고친다.
  - `ExperimentRunComposeScenarioTest`: "리포트 항목"(요청 배율 최댓값, 재시도 결정 수, ad 429 수, 판정 입력 파일), 새 "광고 요청 한도를 넘는 짧은 실행". KDoc의 `report.json` 형식에 `requestRatioMax`, `retries`, `adRejections`를 더한다.
  - `ExperimentDashboardComposeScenarioTest`: 재시도 결정 패널, ad Retry-After 대기 패널.
  - 확인: `./gradlew :e2e:compileTestKotlin`이 통과한다. Compose 테스트는 5.3에서 돌린다.

## 2. 구현

- [x] 2.1 실패 분류와 재시도 판정을 더한다(design 결정 2, 7).
  - `DownstreamFailure`에 `rate_limited`(`RequestNotPermitted`)와 `retry_after`(`RetryAfterHoldException`). 서킷 판정은 그대로다. `RetryPolicy.retryable`(5xx, `connect_failure`, 커넥션 풀 대기를 뺀 `io_failure`).
  - 확인: 새 단위 테스트 `gateway/RetryPolicyTest`, `gateway/DownstreamFailureRetryCauseTest`가 통과하고 기존 `DownstreamFailureTest`가 그대로 통과한다.
- [x] 2.2 재시도 예산을 만든다(design 결정 5).
  - `RetryBudget`(창, 100ms 칸, 비율, 최솟값, `Clock`). `weave.retry.budget.*` 속성 구조(`RetryProperties`)만 두고 값은 4.2에서 넣는다(그때까지는 속성이 없으면 예산을 보지 않는다).
  - 확인: 단위 테스트 `gateway/RetryBudgetTest`(창 안·밖, 칸 경계, 최솟값과 비율 중 큰 값, 동시에 차감해도 넘지 않는다)가 통과한다.
- [ ] 2.3 Retry-After 대기를 만든다(design 결정 4).
  - `RetryAfter.parse`(초, HTTP 날짜, 없음·읽지 못함, 음수, 상한), `RetryAfterHolds`(의존성별 대기 끝 시각, 늘리기만). `weave.retry-after.*` 속성 구조.
  - 확인: 단위 테스트 `gateway/RetryAfterTest`, `gateway/RetryAfterHoldsTest`가 통과한다.
- [ ] 2.4 `IsolatedCalls.call`에 재시도 루프와 대기를 넣는다(design 결정 1, 3, 4).
  - 순서 `재시도 → 대기 → 서킷 → (RateLimiter, 2.5) → TimeLimiter → 벌크헤드`. `RetryRegistry.find`로 설정을 꺼낸다. 마감은 `RequestDeadline`(`RequestBudget.call`이 `start()` 동안 둔다). `isolation-retry` 스케줄러, 다음 시도는 부른 스레드의 trace 문맥·MDC로 시작한다. 이미 끝난 호출은 다시 부르지 않는다. 429면 대기를 시작한다.
  - `resilience4j.retry` 인스턴스 넷의 구조를 `application.yml`에 둔다(값은 4.1). 테스트 기본 설정이 끄므로 기존 테스트에는 영향이 없다.
  - 확인: 새 단위 테스트 `gateway/RetryLoopTest`("기다리는 동안 호출이 먼저 끝났다" 포함), 1.1의 "4xx는 세지 않는다", 1.2의 `FeedRetryScenarioTest`·`FeedRetryBudgetScenarioTest`, 1.3의 `FeedRetryAfterScenarioTest`가 통과한다.
- [ ] 2.5 ad에 요청 수 상한을 둔다(design 결정 6).
  - `RateLimiterRegistry.find`로 꺼내 서킷 안, TimeLimiter 바깥에서 감싼다. 서킷 `ignore-exceptions`에 `RequestNotPermitted`. `resilience4j.ratelimiter.instances.ad` 구조(값은 4.3).
  - 확인: 1.3의 `FeedAdRateLimitScenarioTest`가 통과한다.
- [ ] 2.6 재시도·대기 로그와 지표를 낸다(design 결정 8, 9).
  - `downstream_call_retried`, `retry_after_hold_started`. `weave.retry.calls`(20개 시계열 0부터), `weave.retry.after.rejected.calls`, `weave.retry.after.hold.remaining`, `weave.ratelimiter.rejected.calls`.
  - 확인: 1.4의 세 클래스가 통과하고 `./gradlew :feed-service:test :feed-service:integrationTest`가 모두 통과한다.
- [ ] 2.7 아키텍처 문서와 feed-service `CLAUDE.md`를 구현에 맞춘다.
  - 4-2 표의 고도화 5 줄, 5-2 그림, 5-3 설정 구조, 5-4 순서(Retry-After 대기), 5-5 실패 분류 표, 12·13장의 F-005 항목. `CLAUDE.md`에 재시도 루프 위치, `RequestDeadline`의 전제, 테스트 기본 설정이 재시도를 끈다는 것, 대기 지우기.
  - 확인: 문서의 클래스·속성·지표 이름이 코드에 있다(`grep`).

## 3. 실험 세트

- [ ] 3.1 `run.sh`가 요청 배율 최댓값, 재시도 결정 수, ad 429 수를 재고 판정 입력 파일을 쓴다(design 결정 10).
  - Prometheus 질의 셋(요청 배율 최댓값, `weave_retry_calls_total` 증가, ad-service 429 증가)과 ad 동시 호출 상한, 시나리오의 요청 한도 창으로 `allowed`. `verdict-input.json`을 판정 전에 쓴다. `report.json`·`report.md` 항목.
  - `docs/architecture.md` 9-3 리포트 표를 고친다.
  - 확인: 바뀐 스크립트가 `/bin/bash -n`을 통과한다. 동작은 1.6의 Compose 테스트(5.3).
- [ ] 3.2 판정에 요청 배율·Retry-After 기준을 넣는다(design 결정 10).
  - `slo.json`의 `requestRatio`(1.2), `lib/verdict.sh`의 넷째 인자와 기대 객체의 `requestRatio`·`retryAfter` 키, `run-all.sh` 판정 표의 "기대" 열. `expectations.json`은 아직 바꾸지 않는다(4.5).
  - 확인: 1.5의 판정 테스트 중 "지금 단계의 기대"를 뺀 것이 `./gradlew :e2e:integrationTest`로 통과한다.
- [ ] 3.3 대시보드에 "재시도·외부 API" 행을 더한다(design 결정 10).
  - `docs/architecture.md` 7-4 표에 줄을 더한다.
  - 확인: `jq . docker/grafana/dashboards/feed-experiments.json`이 통과한다. Compose 확인은 5.3.

## 4. 설정값

**Ralph 루프는 이 묶음을 하지 않는다.** 여기에 오면 progress.md에 "3번 묶음까지 끝남, 4번 묶음은 사람과 함께 `/opsx:apply`로 진행"이라고 적고 `<promise>BLOCKED</promise>`로 멈춘다. 값을 넣는 작업은 하나씩 커밋하고 본문에 근거를 적는다(커밋 컨벤션). 근거는 design 결정 11이다.

- [ ] 4.1 재시도 횟수·간격·jitter를 넣는다(post, user, like, recommendation: 최대 3, 10ms, ×2, 0.5). 확인: 새 `RetrySettingsTest`(main 설정만 읽는다)의 재시도 단언이 통과한다.
- [ ] 4.2 재시도 예산을 넣는다(비율 0.1, 창 10초, 최솟값 10). 확인: `RetrySettingsTest`의 예산 단언.
- [ ] 4.3 ad 요청 수 상한을 넣는다(주기 100ms, 주기당 54, 기다림 0). 확인: `RetrySettingsTest`의 RateLimiter 단언.
- [ ] 4.4 Retry-After 기본·최대 대기를 넣는다(1초, 60초). 확인: `RetrySettingsTest`가 모두 통과하고 `./gradlew ktlintCheck build`가 1.5의 "지금 단계의 기대" 말고는 통과한다.
- [ ] 4.5 평시를 다시 재고 고도화 5의 기대를 적는다.
  - `./gradlew bootJar` 뒤 Compose를 띄워 S-01로 데우고 `experiments/run.sh S-01`이 0으로 끝나는지, ad degrade(상한으로 막힌 호출)와 요청 배율 최댓값을 본다. 결과를 design 결정 13 아래에 적는다.
  - `experiments/expectations.json`을 고도화 5로 바꾼다(design 결정 10). 설정값 커밋과 섞지 않는다.
  - 확인: S-01이 0으로 끝나고 `./gradlew ktlintCheck build`가 통과한다.

## 5. 실험과 기록

**Ralph 루프는 이 묶음을 하지 않는다**(4장과 같은 이유, 합쳐 약 1시간 반).

- [ ] 5.1 장애 실험 세트를 돌려 고도화 5 결과를 기록한다.
  - `experiments/run-all.sh`를 기본값으로 돌린다. 기대와 다른 시나리오가 있으면 기대를 고치지 말고 원인을 찾는다. 기대 자체가 틀렸다고 판단되면 멈추고 사람에게 묻는다.
  - `docs/experiments/retry-policy.md`에 고도화 4와 같은 형식의 표를 만들고, 고도화 4·3·기준선과 가용성·p99·degrade 비율을, 고도화 4와 S-07의 ad 429 수·S-09의 요청 배율 최댓값·재시도 수를 비교한다.
  - 확인: `experiments/run-all.sh`가 0으로 끝나고, 문서의 표가 그 실행의 리포트 값과 같다.
- [ ] 5.2 문제 재현 비교 실행을 돌린다(design 결정 10).
  - 예산 없는 재시도로 S-09, 한도를 S-07 요청 한도보다 낮춘 RateLimiter로 S-07을 한 번씩 돌린다(`FEED_SPRING_APPLICATION_JSON`). 끝나면 변수 없이 feed-service를 다시 만든다.
  - 결과(요청 배율 최댓값, 재시도 수, ad 429 수, ad degrade)를 `docs/experiments/retry-policy.md`에 기본 구성 결과와 나란히 적는다.
  - 확인: 문서의 값이 두 실행의 리포트와 같다.
- [ ] 5.3 Compose 테스트를 돌린다. 확인: `./gradlew :e2e:composeTest`가 통과한다(1.6에서 고친 시나리오 포함).
- [ ] 5.4 PRD와 아키텍처를 마무리한다.
  - PRD F-005 수용 기준과 F-004의 재시도 이벤트를 체크한다. 3장 ad 줄에 계약 쿼터(가정), 10장 "남은 것"의 "S-09의 재시도 증가 비율 상한"을 "정한 것"으로 옮기고 계약 쿼터·Retry-After 대기 결정을 더한다.
  - `docs/architecture.md` 맨 위 상태 줄, 5-6 설정값 표, 13장을 고친다. README의 단계 표를 고친다.
  - 확인: PRD와 아키텍처에 F-005의 남은 항목이 없다.

## 6. 검토와 change 완료 조건

이 묶음은 Ralph 루프가 하지 않는다([하네스 규칙](../../../conventions/harness.md) 3-3, 4장).

- [ ] 6.1 구현에 참여하지 않은 리뷰어가 하네스 규칙 3-3의 검토 항목으로 change 문서와 diff를 본다.
  - 재시도가 멱등한 조회에만 걸려 있는지(토글, ad 제외), 재시도가 시간 예산과 재시도 예산 안에 묶이는지, 서킷과 대기·상한의 순서, 다시 부른 시도의 traceId, 설정값마다 근거(design 결정 11)를 본다.
  - 확인: 막는 지적이 없다.
- [ ] 6.2 하네스 규칙 4장의 change 완료 조건을 확인한다.
  - 장애 실험 세트 결과는 5.1이다. PR 설명에 판정 표와 고도화 4 비교 요약을 적는다.
  - 확인: 체크리스트가 모두 채워진다.

## Workflow follow-up

- `/opsx:archive`로 스펙을 `openspec/specs/`에 합친다.
- `develop`으로 PR을 열고 CI가 통과하면 merge한다.
- 고도화 5 릴리스: `develop` → `main` 릴리스 PR, merge 뒤 `v0.6.0` 태그.
