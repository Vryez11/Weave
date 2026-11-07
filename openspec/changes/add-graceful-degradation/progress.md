# 진행 메모: add-graceful-degradation

## 알아 둘 패턴
- 셸의 `ls`는 eza 별칭이라 경로 인자에서 깨진다. `command ls`를 쓴다. `grep --include=*.kt`는 zsh glob으로 깨진다. 따옴표로 감싼다.
- 테스트 기본값은 `feed-service/src/test/resources/config/application.yml`이다(상한·타임아웃 2초, 예산 5초, Redis `localhost:1`). Redis를 쓰지 않는 테스트는 캐시 쓰기가 조용히 실패하는 상태로 돈다. post가 실패하면 캐시를 읽지 못해 503이다(`cache_error`). post 캐시를 다루는 시나리오만 Testcontainers Redis 주소를 `@DynamicPropertySource`로 준다(design 결정 15).
- `management.health.redis.enabled: false`는 이미 main `application.yml`에 있다(1.1 뒤 fix 커밋). Redis 스타터가 Redis 헬스를 켜 bootJar의 헬스가 DOWN이 됐다.
- 속성 이름(design 결정 14. 1.5~1.7에서 테스트가 쓰기 시작하면 바꾸지 않는다): `weave.post-cache.max-staleness`, `weave.post-cache.write-interval`, `spring.data.redis.timeout`·`connect-timeout`, `weave.user-cache.expire-after-write`, `weave.user-cache.maximum-size`. 환경 변수 형식은 `WEAVE_POSTCACHE_MAXSTALENESS`(고도화 3의 `FeedIsolationOverrideScenarioTest.EnvironmentOverride` 방식).
- post 캐시 키는 `weave:feed:posts:latest:v1:size:{size}`, 값은 `{"cachedAt": "<ISO-8601>", "posts": [<PostResponse>]}`(design 결정 4). e2e Compose 테스트 "앞에 남은 post 캐시"가 이 키 형식을 쓴다.
- 지표 이름(design 결정 10. 대시보드 Compose 테스트가 `weave_feed_sections_total`, `weave_post_cache_reads_total`을 기대한다): `weave.feed.sections{section,outcome=fresh|cached|stale|missing}`, `weave.post.cache.reads{outcome=hit|expired|miss|error}`, `weave.post.cache.writes{outcome=success|error|dropped}`, Caffeine `cache_gets_total{cache="user-profiles"}`.
- 응답 계약은 design 결정 2다. 1.2에서 고친 테스트가 이미 `degradation`(`missing` 문자열 목록, `stale` 객체 목록), `author.unknown`(정상 false)을 단언한다. `FeedAggregationScenarioTest`의 `expectedPost`는 정상 작성자에 `unknown: false`를 붙인다.
- 실험 스크립트 형식의 기준: degrade 비율 `lib/degrade.sh <k6 요약>`은 `ExperimentDegradeRatioScenarioTest` KDoc, 판정의 `degradeRatio` 기준(`slo.json`의 `degradeRatio`, `failedThresholds`의 `"degradeRatio: <의존성> <비율> >= <상한>"`)은 `ExperimentVerdictScenarioTest` KDoc, `report.json`의 `degradeRatio`는 `ExperimentRunComposeScenarioTest` KDoc, 판정 표의 기대 열(`가용성 meet · p99 meet · degrade 비율 meet`)은 `ExperimentRunAllComposeScenarioTest` KDoc. k6 하위 지표 이름은 `degraded_sections{scenario:measure,section:<의존성>}`, `degraded_responses{scenario:measure}`다.
- e2e에서 Redis를 부르는 도우미는 `ExperimentRuns.redis(...)`(`docker compose exec -T redis redis-cli ...`)다. Compose 서비스 이름은 `redis`로 둔다(3.1).
- Ralph 중에는 이미 커밋된 `src/test/` 파일을 훅이 막는다. 1.5~1.7에서 새 테스트 파일을 커밋하면 그 뒤로는 고칠 수 없다. 2장 구현이 따라야 할 이름(시계 Bean, 속성, 지표)을 테스트를 쓸 때 KDoc에 정해 둔다. 테스트 도구가 더 필요하면 새 파일에 둔다.
- 시계: design 결정 11대로 `Clock` Bean을 두고 `FeedAssembler`·`PostStaleCache`·`UserProfileCache`(Caffeine `Ticker`)가 받는다. Boot는 Bean 덮어쓰기가 꺼져 있으니, 테스트는 `@TestConfiguration`에 다른 이름의 `@Primary` 움직이는 시계를 둔다.
- Ralph 세션 권한에서는 `/bin/bash -n`, 실험 스크립트 직접 실행, `jq -f`, 중괄호·따옴표가 든 인라인 jq, `VAR=값 명령`·`env VAR=값 명령`·`bash 스크립트`가 막힌다(고도화 3 progress.md). 스크립트는 e2e `integrationTest`(`ExperimentScripts.run`)로 확인한다.
- 결과는 `<모듈>/build/test-results/<태스크>/TEST-*.xml`에서 본다. `-Pkotest.filter.tests='*이름*'`는 다른 테스트를 skipped로 거르지만 Spring 컨텍스트는 클래스마다 다 뜬다.

## 지금 실패하는 것이 정상인 테스트 (구현 전)
- **1.4 뒤:**
  - feed-service `integrationTest` 실패 11개(2.1~2.4에서 통과해야 한다): `FeedAggregationScenarioTest` "모든 하위 서비스가 정상", "작성자 프로필이 없다", `FeedSlowDependencyScenarioTest`, `FeedUnreachableScenarioTest`, `FeedCallFailureLogScenarioTest` "하위 서비스가 5xx", "호출 시간 상한을 넘긴다", `FeedCallIsolationBudgetScenarioTest` "합치면 예산을 넘는다", `FeedBulkheadTraceScenarioTest` "벌크헤드 스레드의 실패 로그", `FeedTraceLogScenarioTest` "오류 응답", "실패한 요청"(503, 2.4), "하위 서비스 오류 본문이 로그에 없다".
  - e2e `integrationTest` 실패 5개: `ExperimentDegradeRatioScenarioTest` 3(3.2), `ExperimentVerdictScenarioTest` "평시에 영역이 빠진다"(3.3), "지금 단계의 기대"(4.4).
  - Compose 테스트(5.2)는 3장과 4.4 뒤에 통과한다.
- **1.5 뒤:** feed-service `integrationTest` 실패가 26개다. 위 11개에 더해 `FeedDegradationScenarioTest` 11개(2.1·2.2), `FeedUserProfileCacheScenarioTest` 4개(2.3).
- user 캐시 속성(`weave.user-cache.*`)은 새 시나리오 클래스만 `@SpringBootTest(properties)`로 준다. 기존 테스트 컨텍스트와 test `config/application.yml`(커밋돼 Ralph가 고칠 수 없다)에는 값이 없다. 2.3은 값이 없어도 컨텍스트가 떠야 한다(4.2 전까지). 못 하면 멈춘다.

## 반복 기록

### 대화형 1.1~1.4 (2026-11-07)
- 1.1: 의존성 커밋 뒤 bootJar의 feed-service 헬스가 Redis 때문에 DOWN이라 main 설정에서 Redis 헬스를 껐다(design 결정 4를 2.4보다 먼저 넣었다).
- 1.2: `trace-logging` 스펙의 세 시나리오도 피드 500을 예로 써서 델타를 더했다(proposal, design 결정 13, tasks 1.2에 반영). 처리되지 않은 예외는 토글 실패로, 오류 응답·실패한 요청은 post 503으로 바꿨다. `FeedAggregationScenarioTest`의 "하위 서비스 실패는 시간 안에 피드 실패로 끝난다" 묶음은 지웠다. 1.5의 `FeedDegradationScenarioTest`가 `graceful-degradation`으로 다시 둔다. `FeedSlowDependencyScenarioTest`(느린 선택 의존성)와 `FeedUnreachableScenarioTest`(선택 의존성 연결 실패)는 상한·주소가 달라 그대로 두고 F-002로 바꿨다. 1.5에서 "느린 선택 의존성"을 다시 만들지 않는다.
- 1.3: degrade 계산은 `lib/degrade.sh`로 정했다. 픽스처는 `summary-degrade-ratio.json`, `summary-no-200.json`, `summary-S-01-like-degraded.json`, `summary-S-02-recommendation-degraded.json`, `expectations-stage4.json`.
- 1.4: `TraceHttp.kt`에 `HttpResponse<String>.json()`을 더했다(feed-service 테스트 도우미).

---

## 2026-11-07 20:40 - 1.5 중요·선택 등급 시나리오를 테스트로 옮긴다
- `FeedDegradationScenarioTest`: 부분 응답 표시 2, 중요·선택 등급 실패 3(하나가 5xx는 의존성 4개를 `forEach`로, 모두 실패, 서킷 열림. "느린 선택 의존성"은 `FeedSlowDependencyScenarioTest`에 있다), 선택 영역 빼기 2, 좋아요 모름 1. 서킷 열림의 GIVEN은 레지스트리에서 `transitionToOpenState()`로 열고, 시나리오마다 모든 서킷을 `reset()`한다.
- `FeedUserProfileCacheScenarioTest`: 4개. 유효 기간 1초, 최대 크기 100을 클래스 속성으로 준다. 시나리오 사이에 1.5초 기다려 앞 시나리오의 프로필이 지나게 한다(캐시를 비우는 API에 기대지 않는다). 유효 기간은 실제 시간으로 지난다. 2.3에서 Caffeine `Ticker`를 `Clock` Bean(`systemUTC`)에서 만들면 그대로 통과한다.
- 확인: `:feed-service:integrationTest`에서 새 15개가 모두 단언 실패(500, `degradation` 없음)이고 기존 실패 11개는 그대로다. `:feed-service:test`, `ktlintCheck`, `openspec validate`는 통과.
- 바꾼 파일: `feed-service/src/test/kotlin/com/weave/feed/FeedDegradationScenarioTest.kt`, `FeedUserProfileCacheScenarioTest.kt`(새 파일), tasks.md, progress.md
- 다음 반복이 알아 둘 것:
  - `FeedDegradationScenarioTest`는 user 캐시가 컨텍스트에 남는다. `user`가 빠졌다고 단언하는 "모두 실패"는 그 클래스에서 user-service가 돌려준 적 없는 작성자 7, 8을 쓴다.
  - 응답 계약 단언: like 실패면 `like`가 `{"count": null, "myLike": "UNKNOWN"}`(null 키가 있어야 한다), 모르는 작성자는 `{"id", "nickname": null, "profileImageUrl": null, "unknown": true}`다.
  - `-Pkotest.filter.specs='*A|*B'`는 거르지 않았다(151개가 다 돌았다). 결과는 XML에서 클래스별로 본다.
---
