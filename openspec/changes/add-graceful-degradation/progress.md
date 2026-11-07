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
- 결과는 `<모듈>/build/test-results/<태스크>/TEST-*.xml`에서 본다. `-Pkotest.filter.tests='*이름*'`는 다른 테스트를 skipped로 거르지만 Spring 컨텍스트는 클래스마다 다 뜬다. 클래스 몇 개만 돌리려면 Gradle `--tests 'com.weave.feed.<클래스>'`(여러 번 줄 수 있다)가 된다. `-Pkotest.filter.specs`는 거르지 않는다.
- 전체 `:feed-service:integrationTest`에서 `ResilienceAutoConfigurationTest`가 `parsing HTTP/1.1 status line`으로 5개 실패할 때가 있다(임의 포트가 다른 프로세스와 겹친 것으로 보인다). 혼자 돌리면 통과한다. 실패 수를 셀 때 빼고 본다.

## 지금 실패하는 것이 정상인 테스트 (구현 전)
- **1.4 뒤:**
  - feed-service `integrationTest` 실패 11개(2.1~2.4에서 통과해야 한다): `FeedAggregationScenarioTest` "모든 하위 서비스가 정상", "작성자 프로필이 없다", `FeedSlowDependencyScenarioTest`, `FeedUnreachableScenarioTest`, `FeedCallFailureLogScenarioTest` "하위 서비스가 5xx", "호출 시간 상한을 넘긴다", `FeedCallIsolationBudgetScenarioTest` "합치면 예산을 넘는다", `FeedBulkheadTraceScenarioTest` "벌크헤드 스레드의 실패 로그", `FeedTraceLogScenarioTest` "오류 응답", "실패한 요청"(503, 2.4), "하위 서비스 오류 본문이 로그에 없다".
  - e2e `integrationTest` 실패 5개: `ExperimentDegradeRatioScenarioTest` 3(3.2), `ExperimentVerdictScenarioTest` "평시에 영역이 빠진다"(3.3), "지금 단계의 기대"(4.4).
  - Compose 테스트(5.2)는 3장과 4.4 뒤에 통과한다.
- **1.5 뒤:** feed-service `integrationTest` 실패가 26개다. 위 11개에 더해 `FeedDegradationScenarioTest` 11개(2.1·2.2), `FeedUserProfileCacheScenarioTest` 4개(2.3).
- **1.6 뒤:** 37개다. 위 26개에 더해 2.4에서 통과해야 할 11개: `FeedPostStaleCacheScenarioTest` 5, `FeedPostUnavailableDeadlineScenarioTest` 1, `FeedPostStaleCacheOverrideScenarioTest` 1, `FeedPostCacheOutageScenarioTest` 2("죽었고 post는 정상"은 2.1의 `degradation`만 있으면 통과할 수 있다), `FeedPostCacheReadPauseScenarioTest` 1, `FeedPostCacheWritePauseScenarioTest` 1.
- **1.7 뒤:** 42개다. 위 37개에 더해 `FeedFallbackLogScenarioTest` 3(2.5, "게시글을 대체할 수 없다"는 2.4도), `FeedDegradationMetricsScenarioTest` 2(2.6). "정상이면 남기지 않는다"는 없음을 보는 시나리오라 지금도 통과한다.
- **2.4가 지켜야 할 것(1.6 테스트 KDoc):** `cachedAt`, stale 판단, 쓰기 간격을 모두 `Clock` Bean으로 잰다(테스트가 시나리오마다 시계를 1시간 옮겨 같은 키를 다시 쓴다). 캐시 쓰기는 비동기이고 테스트는 키가 생길 때까지 기다린다. `weave.post-cache.*` 값이 없는 기존 테스트 컨텍스트도 떠야 한다(4.3 전까지, user 캐시와 같다).
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

## 2026-11-07 20:45 - 1.6 post stale 캐시와 Redis 장애 시나리오를 테스트로 옮긴다
- 컨텍스트 설정이 시나리오마다 달라 파일 두 개에 클래스 여섯 개를 뒀다.
  - `FeedPostStaleCacheScenarioTest.kt`: `FeedPostStaleCacheScenarioTest`(stale 셋, 503 중 "캐시가 없다"·"10분을 넘었다"), `FeedPostUnavailableDeadlineScenarioTest`("503도 시간 안에", post 상한 100ms·예산 250ms), `FeedPostStaleCacheOverrideScenarioTest`(`WEAVE_POSTCACHE_MAXSTALENESS=1m`).
  - `FeedPostCacheOutageScenarioTest.kt`: `FeedPostCacheOutageScenarioTest`(죽은 Redis `localhost:1`, 둘), `FeedPostCacheReadPauseScenarioTest`(`CLIENT PAUSE`, 읽기 상한 30ms), `FeedPostCacheWritePauseScenarioTest`(`CLIENT PAUSE`, 명령 타임아웃 2초. 30ms면 동기 쓰기도 300ms 안에 들어와 시나리오가 가르지 못한다).
- 새 테스트 도구: `MovableClock.kt`(실제 시간 + 옮긴 거리, `MovableClockConfiguration`이 `@Primary`로 둔다), `PostCacheRedis.kt`(`redis:8.2.2` 컨테이너 하나, `redis-cli` exec, `warmUp`은 size 1~5로 쓰기가 남을 때까지 피드를 불러 연결을 맺는다).
- "N분 전 캐시"는 앱이 정상 요청으로 쓰게 하고(값 형식을 테스트가 만들지 않는다) 키가 생길 때까지 기다린 뒤 시계를 옮긴다. `cachedAt`은 그 요청의 앞뒤 시각(앞쪽 1초 여유) 안이어야 한다.
- 확인: `:feed-service:integrationTest` 실패 37개(기존 26 + 새 11), 새 실패는 모두 단언 실패(캐시 키 없음, 500≠503, `degradation` null)다. `:feed-service:test`, `ktlintCheck`, `openspec validate`는 통과.
- 바꾼 파일: 위 테스트 네 파일(새 파일), `feed-service/CLAUDE.md`(테스트 도구 한 줄), tasks.md, progress.md
- 다음 반복이 알아 둘 것:
  - 2.4의 Redis 첫 연결: `warmUp`은 쓰기 다섯 번 중 하나만 남으면 된다. 읽기 상한 30ms 클래스도 연결 타임아웃은 2초로 준다.
  - 시간을 재는 시나리오는 GIVEN 전에 같은 경로(503 경로는 `size=7`)를 한 번 지난다. 그래도 흔들리면 테스트가 아니라 구현의 첫 요청 비용을 본다.
---

## 2026-11-07 20:55 - 1.7 대체 로그와 지표 시나리오를 테스트로 옮긴다
- `FeedFallbackLogScenarioTest`: "대체 이벤트 로그" 4. 클래스 하나에 [PostCacheRedis] 주소를 줘 `cache_miss`가 나오게 했다(죽은 저장소면 `cache_error`). 503 시나리오는 다른 시나리오가 쓰지 않는 `size=7`로 불러 앞 시나리오의 비동기 쓰기와 겹치지 않게 했다. user 캐시가 컨텍스트에 남으므로 "작성자 일부"를 뺀 시나리오는 작성자 3의 게시글만 쓴다. "정상이면 남기지 않는다"는 그 요청의 `http_request` 줄이 보인 뒤 `fallback_applied`가 없는지 본다.
- `FeedDegradationMetricsScenarioTest`: "대체 지표" 2. 카운터는 컨텍스트에 남아 피드 전후로 긁은 차이를 단언한다. "지표가 있다"는 피드를 부르기 전 본문에 영역 결과 20개, post 캐시 읽기 4·쓰기 3, `cache_gets_total{cache="user-profiles",result=hit|miss}`가 있는지도 본다.
- 확인: 새 실패 5개가 모두 단언 실패(500≠200/503, `weave_feed_sections_total` 없음)다. 전체 실패 42개(+ 포트 겹침으로 보이는 `ResilienceAutoConfigurationTest` 5개, 혼자 돌리면 통과). `:feed-service:test`, `ktlintCheck`, `compileKotlin compileTestKotlin`, `openspec validate`는 통과.
- 바꾼 파일: `feed-service/src/test/kotlin/com/weave/feed/FeedFallbackLogScenarioTest.kt`, `FeedDegradationMetricsScenarioTest.kt`(새 파일), tasks.md, progress.md
- 다음 반복이 알아 둘 것:
  - 2.5의 로그 필드는 design 결정 9(`event`, `dependency`, `fallback`, `reason`, `cacheHits`, `cacheMisses`)이고, 테스트는 `fallback`·`reason`만 단언한다. 로그는 응답 전에 요청 스레드에서 남겨야 MDC `traceId`가 붙는다.
  - 2.6의 영역 결과 카운터는 200 응답마다 의존성 5개에 하나씩이다. 테스트의 "대체를 센다"는 앞선 정상 요청의 user `fresh` +1이 보인 뒤 값을 기준으로 둔다. 응답 뒤에 세도 `eventually`(5초)로 기다린다.
  - 다음 작업은 2.1(응답 계약)이다. 1장은 모두 끝났다.
---

## 2026-11-07 20:57 - 2.1 응답 계약을 바꾼다
- `FeedResponse.degradation`(`Degradation(missing: List<String>, stale: List<StaleSection(section, cachedAt)>)`), `FeedAuthor.unknown`(기본값 false, `nickname`·`profileImageUrl` nullable), `FeedLike.count: Long?`, `MyLike.UNKNOWN`. 기존 `FeedAssemblerTest`가 `FeedAuthor(id, nickname, url)`로 만들어서 `unknown`에 기본값을 뒀다.
- 응답 구성 함수 `com.weave.feed.degradation.FeedDegradation.compose(posts: PostSource, users, likes, recommendations, ads: Result<List<...>>): ComposedFeed(response, sections)`. `PostSource`는 `Fresh(posts)`·`Stale(posts, cachedAt)`, `sections`는 의존성 이름 → `SectionOutcome`(`FRESH`·`CACHED`·`STALE`·`MISSING`, `tag`가 지표 태그)이고 늘 5개가 `post, user, like, recommendation, ad` 순서다. `degradation`은 `sections`에서 만든다. 지금은 실패 `Result`면 `getOrThrow()`로 던진다.
- `FeedAssembler`는 post future와 "post 뒤 user·like" future(`Pair`)를 따로 들고, 지금처럼 `budget.await`(던진다)로 받아 `Result.success`로 넘긴다. 게시글이 없으면 user·like는 빈 목록의 성공이다.
- 확인: `FeedDegradationTest` 5개 통과, `FeedAggregationScenarioTest` 17개 통과. `:feed-service:integrationTest` 실패 42 → 38(빠진 것: aggregation 2, `FeedDegradationScenarioTest` "모든 하위 서비스가 정상", `FeedPostCacheOutageScenarioTest` "죽었고 post는 정상"). 새 실패 없음. `:feed-service:test`, `ktlintCheck`, `openspec validate` 통과.
- 바꾼 파일: `FeedResponse.kt`, `FeedAssembler.kt`, `degradation/FeedDegradation.kt`(새 파일), `src/test/.../degradation/FeedDegradationTest.kt`(새 파일), tasks.md, progress.md
- 다음 반복이 알아 둘 것:
  - **`FeedDegradationTest`는 이제 커밋돼 Ralph가 고치지 못한다.** 2.2의 "등급별 단언"은 새 파일(예: `degradation/FeedDegradationGradeTest.kt`)에 둔다. `compose`의 지금 매개변수는 바꾸지 말고, 더할 것(user 캐시 조회 결과 등)은 기본값 있는 매개변수로, 출력은 `ComposedFeed`에 필드를 더한다(테스트는 읽기만 한다).
  - **2.2·2.4 전에 사람이 봐야 할 수 있다(BLOCKED 후보):** 커밋된 단위 테스트 `FeedAssemblerBudgetTest`가 지금 동작(실패하면 던진다)을 단언한다. "마감이 지나면 끝나지 않은 의존성만 budget_exhausted …"(user·recommendation이 끝나지 않으면 `CompletionException`)와 "예산 안에 먼저 실패한 의존성이 있으면 … 그 실패로 끝난다"(ad 실패면 던진다)는 2.2 뒤 200이 되어야 하고, "post가 끝났을 때 남은 예산이 없으면 …"(post 뒤 user·like 예산 초과면 `CompletionException`)도 2.2 뒤에는 user·like가 빠진 200이다. 2.2에서 이 셋이 깨지면 테스트를 고쳐야 하므로 멈춘다. 로그 단언(`budget_exhausted` 줄)은 그대로 쓸 수 있다.
---

## 2026-11-07 20:58 - 2.2 의존성 결과를 값으로 받고 중요·선택 등급을 대체한다 (BLOCKED)
- 구현하지 않고 멈췄다. 코드는 바꾸지 않았다.
- 이유: 2.2를 구현하면 커밋된 단위 테스트 `feed-service/src/test/kotlin/com/weave/feed/FeedAssemblerBudgetTest.kt`의 세 테스트가 깨진다. 셋 다 `shouldThrow<CompletionException> { assembler.assemble(...) }`로 지금 동작(피드 실패)을 단언하는데, `graceful-degradation`·`call-isolation` 델타는 같은 상황에서 대체한 응답을 요구한다. 테스트를 고쳐야 통과하는 경우이고, Ralph 중에는 훅이 `src/test/` 수정을 막는다. design 결정 15의 표도 post 흐름 단언을 "`FeedAssemblerTest`, `FeedAssemblerBudgetTest`(기존)에 더한다"로 적어 커밋된 파일 수정을 전제한다(2.4도 같다). 1.2의 `grep "500\|INTERNAL_SERVER_ERROR\|is5xx"`에 이 단위 테스트가 걸리지 않아 빠진 것으로 보인다.
  - 103행 "post가 끝났을 때 남은 예산이 없으면 user와 like를 부르지 않고, 둘 다 budget_exhausted로 실패한다": 2.2 뒤에는 던지지 않고 user·like(post Gateway가 시계를 250ms 옮기므로 recommendation·ad도)가 `missing`인 응답이다.
  - 127행 "마감이 지나면 끝나지 않은 의존성만 budget_exhausted 실패 로그를 남기고 그 future를 끝낸다": 2.2 뒤에는 user·recommendation이 `missing`인 응답이다.
  - 149행 "예산 안에 먼저 실패한 의존성이 있으면 예산 초과가 아니라 그 실패로 끝난다": 2.2 뒤에는 ad만 `missing`인 응답이다.
- 사람에게 제안(대화형으로 고친 뒤 루프를 다시 돌린다):
  - 세 테스트의 `shouldThrow`를 `val feed = assembler.assemble(...)`로 바꾸고 `feed.degradation.missing`을 단언한다(103행 `shouldContainAll listOf("user", "like")`, 127행 `shouldContainAll listOf("user", "recommendation")`, 149행 `shouldBe listOf("ad")`). 103행은 작성자 `unknown`·`myLike` `UNKNOWN`도 볼 수 있다. `verify`, `budget_exhausted` 로그 단언, 127행의 `users.join()`·`recommendations.join()` 단언은 그대로 둔다. 149행 이름은 "…먼저 실패한 의존성이 있으면 budget_exhausted가 아니라 그 의존성만 빠진다"처럼 바꾼다. 기능 ID는 `F-001, F-002`.
  - 2.4가 더할 post 흐름 단언(실패면 캐시, 캐시 없으면 예외, 503이면 recommendation·ad를 기다리지 않음, stale 경로의 예산)도 같은 이유로 Ralph가 이 두 파일에 더할 수 없다. 사람이 같이 더해 두거나, design 결정 15를 "새 파일(예: `FeedAssemblerPostFlowTest`)"로 고쳐 Ralph가 새 파일에 두게 한다.
- 바꾼 파일: progress.md
---

## 2026-11-07 대화형 - 2.2 막힘 풀기
- `FeedAssemblerBudgetTest`의 세 테스트(103·127·149행)를 Ralph 제안대로 "던진다"에서 `degradation.missing` 단언으로 바꿨다(F-001, F-002). 2.2 전이라 지금 `:feed-service:test`에서 이 셋이 실패하는 것이 정상이다. 2.2에서 통과해야 한다.
- design 결정 15와 tasks 2.2·2.4를 고쳤다: 등급별 단언은 새 `degradation/FeedDegradationGradeTest`, post 흐름 단언은 새 `FeedAssemblerPostFlowTest`에 둔다.
- **`FeedAssembler`에 매개변수를 더할 때는 기본값을 둔다.** 커밋된 `FeedAssemblerTest`, `FeedAssemblerBudgetTest`가 Gateway 다섯, `timeBudget`, `clock`만으로 직접 만든다(design 결정 15). 기본값이 없으면 컴파일이 깨져 다시 멈춘다.
---
