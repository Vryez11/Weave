# Tasks

## 1. 준비와 시나리오 테스트

시나리오(WHEN/THEN)를 실패하는 테스트로 옮긴다. 구현이 없으니 바뀐 동작을 단언하는 테스트는 `integrationTest`에서 실패하고, `test`(Stop 훅)는 통과해야 한다. 테스트 이름에 `F-002`를 넣고, 대체 로그 테스트는 `F-004`도 넣는다(design 결정 15).

**1.1~1.4는 이미 커밋된 테스트를 고치거나 의존성을 더하므로 Ralph 루프가 아니라 대화형(`/opsx:apply`)으로 먼저 끝낸다**(하네스 규칙 2-2의 훅과 "확인" 항목). Ralph가 이 넷 중 체크되지 않은 작업을 만나면 구현하지 말고 progress.md에 적은 뒤 `<promise>BLOCKED</promise>`로 멈춘다.

- [x] 1.1 (대화형) feed-service에 의존성을 더한다(design 결정 4, 7, 15).
  - `spring-boot-starter-data-redis`, `com.github.ben-manes.caffeine:caffeine`(Boot BOM), 테스트에 `org.testcontainers:testcontainers`. 의존성만 따로 커밋한다(`chore(build)`).
  - `feed-service/src/test/resources/config/application.yml`에 Redis 주소를 듣지 않는 포트(`localhost:1`)로, `management.health.redis.enabled: false`를 둔다(design 결정 15). 기존 통합 테스트의 컨텍스트가 뜨는지 본다.
  - 확인: `./gradlew :feed-service:test :feed-service:integrationTest`가 지금과 같은 결과다(새로 실패하는 테스트가 없다).
- [x] 1.2 (대화형) `feed-aggregation`·`call-isolation` 델타를 기존 feed-service 테스트에 옮긴다.
  - 피드 500을 단언하는 테스트를 델타대로 바꾼다: `FeedAggregationScenarioTest`(응답 구성의 `degradation`·`unknown`, "하위 서비스 실패는 시간 안에 피드 실패로 끝난다" 시나리오를 지우거나 `graceful-degradation`으로 옮긴다), `FeedCallFailureLogScenarioTest`("하위 서비스가 5xx"의 200), `FeedCallIsolationScenarioTest`(예산 초과의 200과 `missing` `user`), `FeedSlowDependencyScenarioTest`, `FeedUnreachableScenarioTest`, 그 밖에 `grep -ln "500\|INTERNAL_SERVER_ERROR\|is5xx"`로 찾은 것. 좋아요 토글의 500은 그대로 둔다.
  - `trace-logging` 델타(이 작업에서 더했다): `FeedUnhandledExceptionLogScenarioTest`는 토글 실패로, `FeedTraceLogScenarioTest`의 "오류 응답"·"실패한 요청"은 post 503(테스트 기본 설정은 Redis에 연결할 수 없어 캐시를 읽지 못한다)으로 바꾼다. 503은 2.4 전까지 500이라 실패한다.
  - 바뀐 테스트의 이름의 기능 ID를 `F-002`로 바꾼다.
  - 확인: `./gradlew :feed-service:integrationTest`에서 바꾼 시나리오만 실패하고(실패 원인이 컴파일 오류가 아니다), `./gradlew :feed-service:test`는 통과한다.
- [ ] 1.3 (대화형) 실험 세트의 판정 테스트를 델타대로 고친다.
  - `ExperimentVerdictScenarioTest`의 "고도화 3의 기대"를 "지금 단계의 기대"(고도화 4)로 바꾸고, "degrade 비율 기준"의 두 시나리오를 픽스처(`e2e/src/test/resources/experiments/`)로 더한다. 픽스처의 degrade 값을 판정 스크립트에 넘기는 방법(k6 요약의 `degraded_sections` 하위 지표)을 KDoc에 적는다.
  - "degrade 비율"의 "비율을 계산한다"를 새 `ExperimentDegradeRatioScenarioTest`에 k6 요약 픽스처로 둔다. 계산을 맡을 스크립트 이름과 인자를 이 작업에서 정해 KDoc에 적는다.
  - 확인: `./gradlew :e2e:integrationTest`에서 새·바뀐 판정 테스트만 실패하고, `./gradlew :e2e:test`는 통과한다.
- [ ] 1.4 (대화형) Compose 테스트와 종단 간 테스트를 델타대로 고친다.
  - `ExperimentRunComposeScenarioTest`: "리포트 항목"(degrade 비율), "모든 요청이 실패한다"(`docker compose stop redis` 뒤 S-06, 끝나면 `start`), 새 "앞에 남은 post 캐시"(`redis-cli SET`으로 `size=7` 키), 새 "선택 의존성이 느리다"(S-02 짧은 실행의 degrade 비율).
  - `ExperimentDashboardComposeScenarioTest`: degrade 비율 패널. `ProxyPathComposeScenarioTest` 등 피드 500이나 degrade "없음"을 전제로 한 것을 찾아 고친다.
  - `ServiceProcesses`가 feed-service 프로세스에 1.1과 같은 Redis 주소를 준다.
  - 확인: `./gradlew :e2e:compileTestKotlin`이 통과하고 `./gradlew :e2e:test :e2e:integrationTest`가 1.3에서 실패하게 둔 것 말고는 통과한다. Compose 테스트는 5.2에서 돌린다.
- [ ] 1.5 `graceful-degradation`의 중요·선택 등급 시나리오를 옮긴다.
  - `FeedDegradationScenarioTest`: "부분 응답 표시"(둘), "중요·선택 등급의 실패는 피드를 실패시키지 않는다"(넷), "선택 영역 빼기"(둘), "좋아요 상태를 모름으로 내린다".
  - `FeedUserProfileCacheScenarioTest`: "작성자 프로필을 로컬 캐시로 채운다"(넷). 유효 기간은 시나리오에서 속성으로 1초를 준다.
  - 확인: `./gradlew :feed-service:integrationTest`에서 이 시나리오들이 실패하고(컴파일 오류가 아니다), `./gradlew :feed-service:test`는 통과한다.
- [ ] 1.6 post stale 캐시와 Redis 장애 시나리오를 옮긴다.
  - `FeedPostStaleCacheScenarioTest`: "게시글을 stale 캐시로 대체한다"(셋), "게시글을 대체할 수 없으면 503"(셋), "대체 설정값은 설정으로 바꾼다". Testcontainers `GenericContainer("redis:8.2.2")`를 클래스에서 공유하고, 시각은 테스트 `Clock` Bean으로 움직인다(design 결정 11). 시나리오 사이에 `FLUSHDB`한다.
  - `FeedPostCacheOutageScenarioTest`: "대체 수단의 장애는 피드를 막지 않는다"(넷). 응답하지 않는 Redis는 `CLIENT PAUSE 1000 ALL`, 죽은 Redis는 듣지 않는 포트.
  - 테스트에서 시계를 바꾸는 방법과 환경 변수 형식 속성(`WEAVE_POSTCACHE_MAXSTALENESS`)을 주는 방법을 KDoc에 적는다.
  - 확인: 1.5와 같다.
- [ ] 1.7 대체 로그와 지표 시나리오를 옮긴다.
  - `FeedFallbackLogScenarioTest`: "대체 이벤트 로그"(넷). 이름에 `F-002`와 `F-004`.
  - `FeedDegradationMetricsScenarioTest`: "대체 지표"(둘). 지표 이름은 design 결정 10이다.
  - 확인: 1.5와 같다.

## 2. 대체 구현

- [ ] 2.1 응답 계약을 바꾼다(design 결정 2).
  - `FeedResponse`에 `degradation`(`missing`, `stale`), `FeedAuthor`에 `unknown`, `FeedLike.count`를 nullable로, `MyLike`에 `UNKNOWN`. 정상 응답은 빈 목록과 `unknown` false다. 응답 구성 함수(design 결정 3)를 `com.weave.feed.degradation`에 두고 `FeedAssembler`가 쓴다. 아직 실패는 지금처럼 던진다.
  - 확인: `FeedDegradationTest`(정상 입력)와 1.2의 응답 구성 시나리오가 통과한다.
- [ ] 2.2 의존성 결과를 값으로 받고 중요·선택 등급을 대체한다(design 결정 1, 3).
  - `RequestBudget`에 결과를 값으로 돌려주는 기다리기를 더하고, post future와 "post 뒤 user·like" future를 따로 든다. user 실패는 아직 모두 `unknown`, like는 `UNKNOWN`, recommendation·ad는 빈 목록. post 실패는 아직 던진다(2.4).
  - 확인: `FeedDegradationTest`의 등급별 단언, 1.5의 `FeedDegradationScenarioTest`, 1.2의 예산 시나리오가 통과한다.
- [ ] 2.3 user 로컬 캐시를 넣는다(design 결정 7).
  - `UserProfileCache`(Caffeine, `Ticker`는 `Clock`에서, `recordStats`, `CaffeineCacheMetrics`). 성공하면 `putAll`, 실패하면 작성자마다 조회해 채우거나 `unknown`. `weave.user-cache.*` 속성은 구조만 두고 값은 4.2에서 넣는다(그때까지는 테스트 속성으로 준다).
  - `Clock` Bean을 두고 `FeedAssembler`가 받게 한다(design 결정 11).
  - 확인: `UserProfileCacheTest`와 1.5의 `FeedUserProfileCacheScenarioTest`가 통과한다.
- [ ] 2.4 post stale 캐시와 503을 넣는다(design 결정 4, 5, 8).
  - `PostStaleCache`(키·값 형식, stale 판단, 비동기 쓰기와 키별 쓰기 간격, 대기열 50에서 버림), Lettuce `REJECT_COMMANDS` customizer, `management.health.redis.enabled: false`를 main 설정에. post 실패면 요청 스레드에서 읽고, 쓸 수 없으면 `PostsUnavailableException` → `FeedController`가 503(`{"error":"posts_unavailable"}`)이다. 503이면 recommendation·ad를 기다리지 않는다.
  - `weave.post-cache.*`와 Redis 타임아웃은 구조만 두고 값은 4장에서 넣는다(그때까지는 테스트 속성).
  - 확인: `PostStaleCacheTest`, `FeedAssemblerTest`의 post 흐름 단언, 1.6의 두 시나리오 테스트가 통과한다.
- [ ] 2.5 대체 이벤트 로그를 남긴다(design 결정 9).
  - `fallback_applied`를 요청 스레드에서 의존성마다 한 줄. 필드는 design 결정 9.
  - feed-service `CLAUDE.md`에 대체 경로(Assembler 한 곳, 요청 스레드, 로그 한 줄)를 적는다.
  - 확인: 1.7의 `FeedFallbackLogScenarioTest`가 통과한다.
- [ ] 2.6 대체 지표를 낸다(design 결정 10).
  - `weave.feed.sections`(20개 시계열 0부터), `weave.post.cache.reads`, `weave.post.cache.writes`. user 캐시는 2.3의 Caffeine 지표.
  - 확인: 1.7의 `FeedDegradationMetricsScenarioTest`가 통과하고 `./gradlew :feed-service:test :feed-service:integrationTest`가 모두 통과한다.
- [ ] 2.7 아키텍처 문서를 구현에 맞춘다.
  - 5-2(fallback은 `FeedAssembler`, design 결정 1), 6장(응답 계약, 흐름, 캐시 구조, 지표·로그, design 결정 2~10), 4-2 표의 고도화 4 줄, 11장 표(대체 설정값 위치), 12·13장의 F-002 항목. 값은 4장에서 채운다.
  - 확인: 문서의 클래스·속성·지표 이름이 코드에 있다(`grep`으로 확인).

## 3. 실험 세트

- [ ] 3.1 Compose에 Redis를 더한다(design 결정 12).
  - `redis` 서비스(태그는 `docker manifest inspect redis:8.2.2`로 있는지 확인한다. 없으면 같은 마이너의 있는 패치로 바꾸고 design에 적는다), `--save "" --appendonly no`, healthcheck, 자원 상한, feed-service의 `REDIS_HOST`와 `depends_on`. `chore(infra)`로 따로 커밋한다.
  - `docs/architecture.md` 9-1 표(Redis 줄), CLAUDE.md "로컬 인프라" 줄을 고친다.
  - 확인: `docker compose config`가 통과하고, `./gradlew bootJar` 뒤 `docker compose up -d --build --wait`에서 redis가 healthy이며 `GET /feed`가 200이다.
- [ ] 3.2 부하 도구가 부분 응답을 읽고 리포트가 degrade 비율을 적는다(design 결정 12).
  - `contract.js`(`PARTIAL_RESPONSE = true`, `missing` + `stale[].section`), `feed.js`(`degraded_responses`, 하위 지표 기준 `count>=0`), `run.sh`의 `degradeRatio`(의존성 5개와 `any`, 200 응답이 없으면 "없음"), `report.md` 줄. 1.3에서 정한 계산 스크립트를 쓴다.
  - `docs/architecture.md` 9-3 리포트 표의 degrade 줄을 고친다.
  - 확인: 1.3의 `ExperimentDegradeRatioScenarioTest`가 통과하고 바뀐 스크립트가 `/bin/bash -n`을 통과한다.
- [ ] 3.3 degrade 비율 판정 기준을 넣는다(design 결정 12).
  - `slo.json`의 `degradeRatio`, `lib/verdict.sh`가 기대 객체의 `degradeRatio` 키를 읽는다. `run-all.sh` 판정 표의 "기대" 열에 degrade 기준을 적는다. `expectations.json`은 아직 바꾸지 않는다(4.4).
  - 확인: 1.3의 판정 테스트 중 "지금 단계의 기대"를 뺀 것이 `./gradlew :e2e:integrationTest`로 통과한다.
- [ ] 3.4 `run.sh`가 시작할 때 post 캐시를 비운다(design 결정 12).
  - Redis가 떠 있지 않으면 비우지 않고 표준 오류에 한 줄 적고 계속한다. 끝날 때의 되돌리기에는 넣지 않는다(캐시는 장애가 아니다).
  - `docs/architecture.md` 9-3의 `run.sh` 설명을 고친다.
  - 확인: `/bin/bash -n experiments/run.sh`가 통과한다. 동작은 1.4의 "앞에 남은 post 캐시"(5.2).
- [ ] 3.5 대시보드에 degrade 행을 더한다(design 결정 12).
  - 의존성별 degrade 비율(분모가 0이면 0), post 캐시 읽기 결과, user 캐시로 채운 응답, 피드 503. 대시보드 설명의 "degrade 패널은 F-002에서 더한다"를 지운다.
  - `docs/architecture.md` 7-4 표의 degrade 줄을 채운다.
  - 확인: `jq . docker/grafana/dashboards/feed-experiments.json`이 통과한다. Compose 확인은 5.2.

## 4. 설정값

**Ralph 루프는 이 묶음을 하지 않는다.** 여기에 오면 progress.md에 "3번 묶음까지 끝남, 4번 묶음은 사람과 함께 `/opsx:apply`로 진행"이라고 적고 `<promise>BLOCKED</promise>`로 멈춘다. Compose 측정이 반복 세션의 명령 시간 제한을 넘을 수 있다. 값을 넣는 작업은 하나씩 커밋하고 본문에 근거를 적는다(커밋 컨벤션).

- [ ] 4.1 Redis 읽기 지연을 재고 읽기 상한을 넣는다(design 결정 6).
  - 이 브랜치 구성에 `spring.data.redis.timeout`·`connect-timeout` 30ms를 `FEED_SPRING_APPLICATION_JSON`으로 주고 Compose를 띄워 S-01로 데운 뒤 `experiments/run.sh S-06`(400 RPS)을 돌린다. 측정 구간의 Lettuce 명령 지연 p99와 Redis 컨테이너 CPU(`docker stats`)를 본다.
  - p99가 10ms를 넘으면 값을 넣지 않고 멈춘다. 넘지 않으면 30ms를 `application.yml`에 근거 주석과 함께 넣는다.
  - 결과(결과 폴더 이름, p99, CPU)를 design 결정 6과 `docs/architecture.md` 6장에 적는다.
  - 확인: design에 결과가 있고, `DegradationSettingsTest`(새로 둔다. `spring.config.location=classpath:/application.yml`로 main 설정만 읽어 대체 설정값을 단언한다, 고도화 3의 `IsolationSettingsTest`와 같은 방식)의 Redis 타임아웃 단언이 통과한다.
- [ ] 4.2 user 캐시 값을 넣는다(design 결정 7).
  - 유효 기간 1시간, 최대 크기 10,000(승인 때 바뀌었으면 그 값). 항목 크기 추정(약 0.5KB)을 1만 개를 넣어 `Runtime` 사용량 차이로 한 번 재 design 결정 7에 적는다.
  - 확인: `DegradationSettingsTest`의 user 캐시 단언이 통과한다.
- [ ] 4.3 stale 허용 10분과 쓰기 간격 1초를 넣는다(design 결정 4, 5). 확인: `DegradationSettingsTest`가 모두 통과하고 `./gradlew ktlintCheck build`가 1.3의 "지금 단계의 기대" 말고는 통과한다.
- [ ] 4.4 평시를 다시 재고 고도화 4의 기대를 적는다.
  - `./gradlew bootJar` 뒤 Compose를 다시 띄워 S-01로 데우고 `experiments/run.sh S-01`이 0으로 끝나는지(3.3의 degrade 기준은 아직 기대에 없다), 리포트의 의존성별 degrade 비율이 모두 1% 미만인지 본다. 결과를 design 결정 16에 적는다.
  - `experiments/expectations.json`을 고도화 4로 바꾼다(design 결정 12). 설정값 커밋과 섞지 않는다.
  - 확인: S-01이 0으로 끝나고 `./gradlew ktlintCheck build`가 통과한다.

## 5. 실험과 기록

**Ralph 루프는 이 묶음을 하지 않는다**(4장과 같은 이유, 합쳐 약 1시간 반).

- [ ] 5.1 장애 실험 세트를 돌려 고도화 4 결과를 기록한다.
  - `experiments/run-all.sh`를 기본값으로 돌린다. 기대와 다른 시나리오가 있으면 기대를 고치지 말고 원인을 찾는다. 기대 자체가 틀렸다고 판단되면 멈추고 사람에게 묻는다.
  - `docs/experiments/graceful-degradation.md`에 고도화 3과 같은 형식의 표를 만들고, 고도화 3·기준선과 나란히 가용성·p99·degrade 비율을 비교한다. S-04의 user `cached` 비율, S-06의 stale 비율과 Redis 읽기 지연, S-01의 like degrade(고도화 3 "남은 것"의 like 순간 막힘)를 적는다. 실험 데이터가 고정이라 user 캐시 적중률이 현실보다 높다는 한계를 적는다.
  - 확인: `experiments/run-all.sh`가 0으로 끝나고, 문서의 표가 그 실행의 리포트 값과 같다.
- [ ] 5.2 Compose 테스트를 돌린다. 확인: `./gradlew :e2e:composeTest`가 통과한다(1.4에서 고친 시나리오 포함).
- [ ] 5.3 PRD와 아키텍처를 마무리한다.
  - PRD F-002 수용 기준을 체크하고, F-004의 "fallback" 부분 진행을 적는다(재시도는 F-005에 남는다). 10장 "남은 것"에서 user 캐시 TTL과 post 캐시 키 구조를 "정한 것"으로 옮긴다.
  - `docs/architecture.md` 맨 위 상태 줄과 13장을 고친다.
  - 확인: PRD와 아키텍처에 F-002의 남은 항목이 없다.

## 6. 검토와 change 완료 조건

이 묶음은 Ralph 루프가 하지 않는다([하네스 규칙](../../../conventions/harness.md) 3-3, 4장).

- [ ] 6.1 구현에 참여하지 않은 리뷰어가 하네스 규칙 3-3의 검토 항목으로 change 문서와 diff를 본다.
  - 대체가 `FeedAssembler` 한 곳에만 있는지, 응답의 `degradation`이 실제 대체와 맞는지(등급표 단위 테스트), 대체 수단(Redis 읽기·쓰기)이 시간 예산과 헬스 체크를 해치지 않는지, 설정값마다 측정이나 계산이 있는지(design 결정 4~7), 재시도가 없는지 본다.
  - 사람이 Grafana에서 S-06 실행 하나의 측정 구간을 보고, 리포트의 degrade 비율과 그래프가 맞는지 확인한다.
  - 확인: 막는 지적이 없다.
- [ ] 6.2 하네스 규칙 4장의 change 완료 조건을 확인한다.
  - 장애 실험 세트 결과는 5.1이다. PR 설명에 판정 표와 고도화 3 비교 요약을 적는다.
  - 확인: 체크리스트가 모두 채워진다.

## Workflow follow-up

- `/opsx:archive`로 스펙을 `openspec/specs/`에 합친다.
- `develop`으로 PR을 열고 CI가 통과하면 merge한다.
- 고도화 4 릴리스: `develop` → `main` 릴리스 PR, merge 뒤 `v0.5.0` 태그.
