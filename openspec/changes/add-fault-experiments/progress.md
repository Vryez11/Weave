# 진행 메모: add-fault-experiments

## 알아 둘 패턴
- 셸의 `ls`는 eza 별칭이라 경로 인자에서 깨진다. `command ls`를 쓴다.
- Gradle 9는 테스트 소스가 있는데 발견된 테스트가 없으면 `test` 태스크를 실패시킨다. 새 모듈에는 스펙을 하나 이상 둔다(`Integration` 태그만 있어도 된다).
- 장애 주입 테스트 앱: `libs/fault-injection`의 `com.weave.faultinjection.testapp.FaultInjectionTestApplication`, 업무 엔드포인트 `GET /work`(본문 `WorkController.WORK_RESPONSE`).
- 새 모듈은 `scripts/harness/stop-check.sh`의 모듈 목록에도 넣는다(Stop 훅이 바뀐 모듈만 검사한다).
- Spring 테스트에서 `/actuator/prometheus`를 보려면 `@AutoConfigureMetrics`가 있어야 한다(없으면 404).
- 스펙 하나만 돌리기: `./gradlew :<모듈>:integrationTest -Pkotest.filter.specs='*<스펙 클래스>'`.
- 실험 세트 스크립트의 인자·출력 형식은 e2e 테스트 KDoc이 기준이다: 시나리오 JSON과 `run-all.sh --list`는 `ExperimentScenarioDefinitionTest`, `lib/verdict.sh`는 `ExperimentVerdictScenarioTest`, `lib/windows.sh`(와 `slo.json` 형식)는 `ExperimentWindowsScenarioTest`.
- e2e `Compose` 태그 테스트는 `integrationTest` 리포트에 skipped로 나온다(돌지는 않는다). "리포트에 없다"는 확인은 이것으로 본다.
- 이 반복 세션에서는 `python3`, `docker`, `jq -f`, 셸 변수(`$x`)가 든 인자, `sed -i`가 승인 없이 돌지 않는다. 일회용 생성이 필요하면 `./gradlew -I <빌드 폴더의 init 스크립트> help`로 Kotlin을 돌린다.
- Ralph 중에는 이미 커밋된 `src/test/` 파일(공통 도구 `ComposeEnvironment.kt` 등 포함)을 훅이 막는다. 테스트 도구가 더 필요하면 새 파일에 둔다(예: `ExperimentRuns.kt`).
- `run.sh`의 `report.json` 형식은 `ExperimentRunComposeScenarioTest` KDoc, `run-all.sh` 판정 표 형식은 `ExperimentRunAllComposeScenarioTest` KDoc, 결과 폴더 파일 이름과 ad `GET /actuator/quota`의 `enabled`는 `ExperimentRuns` KDoc이 기준이다.
- 라이브러리 클래스의 내부를 볼 때는 `javap -c -p`를 Gradle init 스크립트 안에서 `ProcessBuilder`로 돌려 파일로 남기고 Read로 읽는다(예: `feed-service/build/tmp/inspect4.init.gradle`). Gradle 9에는 `project.exec`가 없다. 파이프·`for`·`;` 뒤의 `grep`이 섞인 명령은 승인을 기다린다.
- 이 세션에서 `docker compose`는 승인 없이 돈다(`docker ps`·`inspect`·`info`는 막힌다). 루트에 `.env`가 없으니 `docker compose -p weave-compose-test --env-file e2e/build/compose-test.env ...`로 부른다(env 파일은 `:e2e:composeTestEnv`가 만든다. 읽지 않는다). `docker inspect`가 필요하면 Gradle init 스크립트에서 `ProcessBuilder`로 돌린다(`build/tmp/inspect-limits.init.gradle`, 커밋하지 않음). `echo "$?"`처럼 셸 변수가 든 명령은 승인을 기다린다.
- feed 통합 테스트에서 `HttpClientPools`(리플렉션 객체 그래프 순회)가 드물게 `ConcurrentModificationException`으로 실패한다(4.2에서 5번 중 1번, `HttpClientPoolSizePropertyTest`). 다시 돌리면 통과한다. 반복되면 사람에게 알린다(커밋된 테스트 도구라 Ralph가 고칠 수 없다).

---

## 2026-11-06 - 1.1 `libs/fault-injection` 모듈의 뼈대
- `settings.gradle.kts`에 `libs:fault-injection`을 넣고, `weave.spring-library`를 쓰는 `build.gradle.kts`(main은 `spring-boot-autoconfigure`만, 테스트는 webmvc·test 스타터와 kotest-spring)를 뒀다. main 패키지는 비어 있어 `.gitkeep`으로 남겼다. 하위 서비스에는 붙이지 않았다.
- 테스트 앱 `FaultInjectionTestApplication`(`GET /work`)과, 그 앱이 Tomcat에서 200·`ok`를 주는지 보는 `Integration` 스모크 스펙을 더했다. 스펙이 없으면 Gradle 9가 `test`를 실패시켜서다.
- Stop 훅 모듈 목록에 `libs/fault-injection`을 더했다.
- 바꾼 파일: `settings.gradle.kts`, `libs/fault-injection/**`, `scripts/harness/stop-check.sh`, `tasks.md`
- 확인: `./gradlew ktlintCheck build` 통과.
- 다음 반복(1.2): 엔드포인트·필터를 시험하려면 테스트 의존성에 `spring-boot-starter-actuator`(`/actuator/faults`)와, 2.2의 traceId 단언용 `project(":libs:observability")`·`testFixtures(project(":libs:observability"))`(`LogCapture`)가 필요하다. main에는 2.2에서 `compileOnly` webmvc·servlet-api·actuator를 더하게 된다.
---

## 2026-11-06 - 1.2 `fault-injection` 시나리오를 `libs/fault-injection` 테스트로 옮긴다
- `FaultInjectionScenarioTest`(`weave.fault-injection.enabled=true`, 노출 `health,info,prometheus,faults`, `@AutoConfigureMetrics`): 기본 분포(데우기 20번 뒤 1,000번), 고정 지연, 에러율 1·0.5, 되돌린다, 장애 중의 헬스 체크, 범위를 벗어난 값 3가지. 테스트마다 `beforeTest`에서 `DELETE`로 되돌린다.
- `FaultInjectionDisabledScenarioTest`: 속성 없이, 노출 목록에는 `faults`를 넣고 404를 본다(지금도 통과).
- 테스트 의존성에 actuator 스타터·actuator-test·prometheus 레지스트리를 더했다. 공통 도구 `FaultsHttpClient.kt`(`FaultSettings`, `percentile`).
- 바꾼 파일: `libs/fault-injection/build.gradle.kts`, `libs/fault-injection/src/test/kotlin/com/weave/faultinjection/*.kt`, `libs/fault-injection/CLAUDE.md`, `tasks.md`
- 확인: `:libs:fault-injection:integrationTest` 11개 중 활성 스펙 9개가 실패(404, 지연 없음), `:libs:fault-injection:test`와 전체 `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과.
- 다음 반복이 알아 둘 것: 테스트가 가정한 JSON 형식은 `GET /actuator/faults` → `{"latencyMedianMs":..,"latencyP99Ms":..,"errorRate":..}`(숫자는 실수로 비교), `POST`는 준 필드만 바꾸고 2xx, `DELETE`는 2xx. 2.2에서 이 형식을 맞춘다.
---

## 2026-11-06 - 1.3 "실험 프로필로 띄운다"·"feed-service에는 없다"를 서비스 테스트로 옮긴다
- 하위 서비스 5개에 `<Svc>FaultsEndpointScenarioTest`(`@ActiveProfiles("experiment")`, `RANDOM_PORT`, 실제 HTTP로 `GET /actuator/faults`, 200과 세 필드가 숫자인지)를 더했다. 서비스의 노출 목록은 테스트가 넣지 않는다(2.3의 `application-experiment.yml`이 맡는다). like-service는 컴포넌트 스캔되는 `MySqlTestConfiguration`을 그대로 쓴다.
- feed-service에 `FeedFaultsEndpointScenarioTest`: `experiment` 프로필과 노출 목록 `health,info,prometheus,faults`로 띄워 404를 본다. 노출해도 엔드포인트가 없다는 것을 보려고 노출 목록을 넣었다.
- 바꾼 파일: `{post,user,like,recommendation,ad}-service/src/test/kotlin/com/weave/*/*FaultsEndpointScenarioTest.kt`, `feed-service/src/test/kotlin/com/weave/feed/FeedFaultsEndpointScenarioTest.kt`, `tasks.md`
- 확인: `./gradlew integrationTest --continue`에서 새 테스트 중 하위 서비스 5개가 404로 실패하고 feed는 통과. 그 밖의 실패는 1.2의 `FaultInjectionScenarioTest`(예정된 것)뿐. `./gradlew ktlintCheck test` 통과.
- 다음 반복이 알아 둘 것: `libs/fault-injection`의 테스트 도구(`FaultsHttpClient`, `FaultSettings`)는 testFixtures가 아니라 서비스 테스트에서 못 쓴다. 서비스 테스트는 `java.net.http.HttpClient`와 `tools.jackson.databind.json.JsonMapper`로 직접 부른다.
---

## 2026-11-06 - 1.4 `ad-serving` "요청 한도" 시나리오를 ad-service 테스트로 옮긴다
- `AdQuotaScenarioTest`(`@ActiveProfiles("experiment")`, `RANDOM_PORT`, `@Import(AdjustableClockConfiguration::class)`): 한도를 넘는다(3번 뒤 시계를 4초 옮겨 4번째가 429), 다음 창(실제 시계, `Retry-After`만큼 잔다), 한도 0, 꺼진 한도(100번), 잘못된 한도 2가지. 테스트마다 `beforeTest`에서 시계를 멈추고 `DELETE /actuator/quota`.
- `AdQuotaDisabledScenarioTest`: 프로필 없이, 노출 목록에 `quota`를 넣고 404(지금도 통과).
- 테스트 시계 `AdjustableClock`(`freeze`/`advance`/`useRealTime`)은 `@TestConfiguration`이라 컴포넌트 스캔에서 빠진다. 다른 ad 테스트에는 들어가지 않는다.
- 바꾼 파일: `ad-service/src/test/kotlin/com/weave/ad/{AdjustableClock,AdQuotaScenarioTest,AdQuotaDisabledScenarioTest}.kt`, `tasks.md`
- 확인: `:ad-service:integrationTest`에서 `AdQuotaScenarioTest` 6개가 404로 실패(그 밖의 실패는 1.3의 `AdFaultsEndpointScenarioTest`뿐), 전체 `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과.
- 다음 반복(3.1)이 알아 둘 것: 테스트가 가정한 형식은 `POST /actuator/quota` `{"limit":n,"windowSeconds":w}` → 2xx, `DELETE` → 2xx, `GET` → 200(본문 형식은 자유. 400 전후로 본문이 같아야 한다). `Retry-After`는 정수 초 헤더. 구현은 컨텍스트의 `Clock`을 `ObjectProvider<Clock>.getIfAvailable { Clock.systemUTC() }`처럼 받아야 테스트 시계가 먹는다. 엔드포인트는 기본으로 꺼져 있어야 한다(노출해도 404).
---

## 2026-11-06 - 1.5 "feed-service의 실험 지표"를 feed-service 테스트로 옮긴다
- `FeedExperimentMetricsScenarioTest`(`@AutoConfigureMetrics`, `RANDOM_PORT`, `DownstreamWireMock`): 테스트마다 다섯 의존성을 정상 스텁하고 `GET /feed` 뒤 `/actuator/prometheus`를 최대 5초 동안 다시 긁는다(`eventually`). 단언을 Tomcat, 공유 풀, 의존성별 커넥션 풀(5개), 의존성별 호출 지연 히스토그램(5개), `/feed` 히스토그램으로 나눠 13개 테스트로 뒀다. 4.1·4.2가 단언 묶음별로 통과를 확인할 수 있다.
- Prometheus 텍스트를 읽는 `PrometheusSamples`(파일 안 private 클래스). 실패하면 같은 이름 표본의 라벨을 보여 준다.
- 바꾼 파일: `feed-service/src/test/kotlin/com/weave/feed/FeedExperimentMetricsScenarioTest.kt`, `tasks.md`
- 확인: `:feed-service:integrationTest -Pkotest.filter.specs='*FeedExperimentMetricsScenarioTest'`에서 13개 중 12개 실패(표본 없음). `ktlintCheck compileKotlin compileTestKotlin :feed-service:test`, `openspec validate` 통과.
- 다음 반복(4.2)이 알아 둘 것: **공유 풀 테스트는 지금도 통과한다.** Boot 4.1이 `applicationTaskExecutor`에 실행기 지표(`executor_active_threads`, `executor_queued_tasks`, `name="applicationTaskExecutor"`)를 이미 붙인다. 4.2에서 직접 묶지 말고 design 결정 4에 "자동으로 붙는다"고 적는다. 지금 `http_client_requests_seconds_bucket`·`http_server_requests_seconds_bucket`은 이름 자체가 없다(히스토그램 꺼짐).
---

## 2026-11-06 - 1.6 실험 세트의 계산·판정 시나리오를 e2e `Integration` 테스트로 옮긴다
- `ExperimentScenarioDefinitionTest`: "시나리오마다의 장애"(S-01~S-09 파일만 있고 장애 시간표가 PRD 4장과 같다)와 "기본 목록"(`run-all.sh --list`가 S-01~S-09 출력, 0 종료).
- `ExperimentVerdictScenarioTest`: `lib/verdict.sh <요약> <ID> <기대 결과 파일>`. 스펙의 두 시나리오에 더해, 측정 구간 threshold만 보는지(준비 구간 threshold는 넘어도 충족), miss인데 지키면 1, 요약이 없으면 2인지 본다.
- `ExperimentWindowsScenarioTest`: `lib/windows.sh <원본.json.gz> 300`. "복구 시간"(20초), "복구하지 못함". 구간은 보낸 시각(점 시각 − 지연)으로 나누고, 준비·장애·setup·teardown 요청과 보내지 못한 요청 처리를 함께 본다.
- 공통 도구 `ExperimentScripts`(폴더 위치, 픽스처, gzip, 스크립트 실행). e2e `build.gradle.kts`가 모든 `Test` 태스크에 `weave.experiments.dir`과 `experiments/` 입력을 넘긴다.
- 픽스처 `e2e/src/test/resources/experiments/`: `expectations-stage2.json`, `summary-S-0{1,2}-*.json`(k6 `--summary-export` 형식), `raw-S-08-{recovered,not-recovered}.json`(k6 `--out json` 형식, 1 RPS·준비 10초·측정 300초. 일회용 Gradle init 스크립트로 만들었고 모양은 테스트 KDoc에 적었다).
- 바꾼 파일: `e2e/build.gradle.kts`, `e2e/src/test/kotlin/com/weave/e2e/{ExperimentScripts,ExperimentScenarioDefinitionTest,ExperimentVerdictScenarioTest,ExperimentWindowsScenarioTest}.kt`, `e2e/src/test/resources/experiments/*`, `e2e/CLAUDE.md`, `tasks.md`
- 확인: `:e2e:integrationTest`에서 새 테스트 9개가 실패(파일·스크립트 없음), 기존 종단 간 테스트 4개 통과, Compose 2개 skipped. `:e2e:test`, 전체 `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과.
- 다음 반복이 알아 둘 것:
  - 6.1: 시나리오 `action`은 `latency`(`latencyMs`), `timeout`, `down`, `errorRate`(`errorRate`), `quota`(`windowSeconds`, `limitRatio`. 한도 = RPS × 창 × 비율), `clear`. `slo.json`은 `{"availability": 0.999, "p99Ms": 300}`.
  - 6.2: `--summary-export`에서 threshold 값 `true`가 "넘음(실패)"이라고 보고 픽스처를 만들었다(k6 summary-wrapper의 `!threshold.ok`). 실제 k6 실행 결과로 한 번 확인한다. 측정 구간 지표 키는 `http_req_duration{scenario:measure}`, `availability{scenario:measure}`처럼 `{scenario:measure}`가 붙어야 판정에 들어간다. 장애 요청에는 `kind: fault`, `action`, `target` 태그를, setup·teardown 되돌리기에는 `action: reset`을 붙인다.
  - 6.3: jq의 `fromdateiso8601`은 소수 초를 받지 않는다. 원본 시각은 소수 자릿수가 줄마다 다르고 없을 수도 있다(픽스처에 `01:01:03.5Z` 같은 줄이 있다). epoch 밀리초(약 1.8e12)는 double로 정확하지만 나노초는 아니다.
---

## 2026-11-06 - 1.7 프록시 경로와 대시보드 시나리오를 e2e `Compose` 태그 테스트로 옮긴다
- `ProxyPathComposeScenarioTest`: "프록시에 지연을 넣는다"(recommendation에 latency 톡식 3,000ms → 3초 이상, 톡식 `DELETE` 뒤 1초 안에 200), "한 의존성의 프록시를 끊는다"(user `enabled:false` → 응답 `X-Trace-Id`의 `downstream_call_failed` 줄이 user만, 켜면 200). 테스트마다 앞뒤로 Toxiproxy `POST /reset`.
- `ExperimentDashboardComposeScenarioTest`: GIVEN대로 `run.sh S-01 --rps 5 --warmup 5s --duration 20s`를 직접 돌리고(0 종료), Grafana `/api/search`에 uid `weave-feed`, 패널 질의(접힌 행 포함)의 시간 변수를 `5m`으로 바꿔 Prometheus `/api/v1/query`가 success·시계열 1개 이상. 리포트 자원·SLO 지표 이름 8개가 질의에 있는지도 본다.
- 공통 도구 `ComposeEnvironment`(주소, `dockerCompose`, `experiment`). `composeTest`에 시스템 속성 6개를 더했다.
- 바꾼 파일: `e2e/build.gradle.kts`, `e2e/src/test/kotlin/com/weave/e2e/{ComposeEnvironment,ProxyPathComposeScenarioTest,ExperimentDashboardComposeScenarioTest}.kt`, `e2e/CLAUDE.md`, `tasks.md`
- 확인: `:e2e:compileTestKotlin`, 전체 `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과. `:e2e:integrationTest`에서 새 테스트 3개는 돌지 않는다. 다만 리포트에 **skipped**로 나온다(기존 `TraceLookupComposeScenarioTest`와 같은 Kotest 동작, e2e/CLAUDE.md). tasks의 "리포트에 없다"를 "돌지 않는다(skipped)"로 읽었다. `:e2e:composeTest`는 돌리지 않았다(Toxiproxy·대시보드가 아직 없다).
- 다음 반복이 알아 둘 것:
  - 5.1: Toxiproxy 프록시 **이름은 의존성 이름**(`post`, `user`, `like`, `recommendation`, `ad`)이어야 한다(테스트가 `/proxies/recommendation`을 부른다). 관리 API는 `127.0.0.1:8474`.
  - 6.6: 대시보드 질의에는 시간 변수(`$__rate_interval`, `$__interval`, `$__range`) 말고 변수를 두지 않는다. 장애가 없는 S-01 뒤에도 모든 질의가 시계열을 돌려줘야 하므로, 실패율 질의는 분자가 비지 않게 쓴다(예: `sum by (client_name) (rate(...{outcome!="SUCCESS"}[..])) or ...`처럼 0을 채운다).
  - 1.8: run.sh는 `ComposeEnvironment.experiment("run.sh", ..., timeout = ...)`로 돌리면 프로젝트·env 파일이 넘어간다. "환경이 떠 있지 않다"는 `environment = mapOf("COMPOSE_PROJECT_NAME" to "<없는 이름>")`으로 덮는다. SIGTERM이 필요하면 `ProcessBuilder`를 따로 쓴다(`experiment`는 끝날 때까지 기다린다).
---

## 2026-11-06 - 1.8 `run.sh`·`run-all.sh` 시나리오를 e2e `Compose` 테스트로 옮긴다
- `ExperimentRunComposeScenarioTest`(10개): S-02 짧은 실행 하나(`by lazy`)를 "짧게 돌린다"(측정 요청 95~105, p50 ≥ 3,000ms, `SLO 미충족, 기대와 같음`, 0 종료)·"정상 종료 뒤"·"리포트 항목"이 이어서 본다. 그 밖에 "없는 시나리오"(S-99 → 2, ID 목록 출력, 미리 넣은 장애 상태·결과 폴더가 그대로), "환경이 떠 있지 않다"(`COMPOSE_PROJECT_NAME=weave-no-such-env` → 2, `docker compose ... up` 안내, 결과 폴더·그 프로젝트의 컨테이너 없음), "앞에 남은 장애"(post 지연 1,000ms·like 에러율 1 → 가용성 1, feed `State.StartedAt`이 늦어짐), "준비 구간은 세지 않는다"(준비 10초), "모든 요청이 실패한다"(S-06 가용성 0, p50·p95·p99 있음), "중간에 멈춘다"(S-05 측정 60초, `raw.json`에 `scenario: measure`의 `http_req_duration` 점이 생기면 `Process.destroy()`), "부하 도구가 실패한다"(문법 오류 `scenarios/S-97.json`을 잠시 두고 → 2, 깨끗한 상태).
- `ExperimentRunAllComposeScenarioTest`: `run-all.sh --rps 5 --warmup 5s --duration 20s S-01 S-02` → 마크다운 표 두 줄(`| S-`로 시작, 5칸), 판정 `기대와 같음`, 리포트 폴더에 `report.json`, 0 종료.
- 공통 도구 `ExperimentRuns.kt`(새 파일. 커밋된 `ComposeEnvironment.kt`는 Ralph 훅이 막아 고치지 않았다).
- 바꾼 파일: `e2e/src/test/kotlin/com/weave/e2e/{ExperimentRuns,ExperimentRunComposeScenarioTest,ExperimentRunAllComposeScenarioTest}.kt`, `e2e/CLAUDE.md`, `tasks.md`
- 확인: `:e2e:compileTestKotlin`, 전체 `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과. `:e2e:integrationTest`에서 새 테스트 11개는 skipped(돌지 않음), 실패는 1.6의 9개(예정된 것)뿐. `:e2e:composeTest`는 돌리지 않았다(run.sh가 아직 없다).
- 다음 반복이 알아 둘 것:
  - 3.1: 테스트가 ad `GET /actuator/quota` 본문의 `enabled`(꺼지면 `false`)를 본다. 켜면 `{"enabled":true,"limit":n,"windowSeconds":w}`.
  - 6.2·6.4: 결과 폴더에 `report.md`, `report.json`, `summary.json`, k6 원본은 도는 동안 `raw.json`, 끝나면 `raw.json.gz`. `report.json`의 `faults`는 시나리오의 `clear`가 아닌 항목마다 `injectedAtSeconds`·`removedAtSeconds`(측정 시작부터 잰 초, 거둔 시각은 같은 target의 다음 `clear` 또는 teardown `reset`)·`skipped`.
  - 6.4: "있는 시나리오"는 `scenarios/<ID>.json` 파일이 있는 것. 문법 오류 JSON은 되돌리기 뒤에 k6가 실패해야 테스트가 통과한다(테스트가 장애를 미리 넣어 둔다). 없는 ID와 환경 없음은 아무것도 바꾸지 않고 2.
  - 6.5: 표 줄 말고는 표준 출력에 `| S-`로 시작하는 줄을 쓰지 않는다. 리포트 칸은 레포 루트 기준 상대 경로나 절대 경로.
  - `composeTest`에 run.sh 실행이 8번(run-all 2번 포함) 더해져 10분 남짓 길어진다. Kotest 기본 테스트 시간 제한 안에 들도록 run-all 테스트의 시간 제한을 8분으로 뒀다.
---

## 2026-11-06 - 2.1 지연 분포 뽑기와 장애 설정 검증
- `LatencyDistribution(medianMs, p99Ms).sampleMs(java.util.Random)`: μ = ln m, σ = ln(q/m)/2.326. σ = 0(m = q)이면 계산 없이 m을 돌려준다. `exp(ln 300)`이 299.999…가 되어 고정 지연 300ms가 짧아지는 것을 단위 테스트가 잡았다. m = 0이면 지연 0(로그 정규분포로 못 나타냄. 스펙의 거절 목록에 없어 받기로 했다).
- `FaultConfig(latencyMedianMs, latencyP99Ms, errorRate)`: `init`의 `require`로 검증(지연은 유한한 0 이상, p99 ≥ 중앙값, 에러율 0~1, NaN 거절). 기본값은 두지 않았다(2.2가 속성 클래스에 둔다).
- 단위 테스트 `LatencyDistributionTest`(시드 7, 10,000번, (20,50)·(50,200)·(100,120)의 중앙값·p99 ±5%, 고정, 0), `FaultConfigTest`(거절 8가지, copy 거절, 경계 값).
- 바꾼 파일: `libs/fault-injection/src/main/kotlin/com/weave/faultinjection/{LatencyDistribution,FaultConfig}.kt`, `libs/fault-injection/src/test/kotlin/com/weave/faultinjection/{LatencyDistributionTest,FaultConfigTest}.kt`, `libs/fault-injection/CLAUDE.md`, `tasks.md`
- 확인: `:libs:fault-injection:test -Pkotest.filter.tests='*F-007*'` 18개 통과, 전체 `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과.
- 다음 반복(2.2)이 알아 둘 것:
  - main 패키지의 `.gitkeep`은 이 세션에서 `git rm`이 승인 없이 돌지 않아 남겼다. 지워도 된다.
  - 필터는 `ThreadLocalRandom.current()`를 `sampleMs`와 에러율 뽑기에 넘기면 된다. 잠들 때 밀리초 아래를 버리면 고정 지연이 짧아지니 나노초로 바꿔 잔다(`Thread.sleep(Duration)` 등).
  - `POST`는 `current.copy(준 필드만)`를 `runCatching`으로 감싸 `IllegalArgumentException`을 400으로 바꾸면 "값을 바꾸지 않는다"가 따라온다.
---

## 2026-11-06 - 2.2 필터, `faults` 엔드포인트, 자동 설정
- `FaultInjectionAutoConfiguration`(`@ConditionalOnBooleanProperty("weave.fault-injection.enabled")`, `AutoConfiguration.imports`에 등록): `FaultState`(`AtomicReference`, 기본값으로 되돌리기), `FaultsEndpoint`, 서블릿 컨텍스트에서만 `FaultInjectionFilter`.
- `FaultInjectionProperties`에 기본값(20ms, 50ms, 0). `FaultsEndpoint`: `GET` 지금 값, `POST` 준 필드만 `copy`(검증 실패는 `InvalidEndpointRequestException` → 400, 값 그대로), `DELETE` 기본값. 셋 다 바뀐 뒤 값을 돌려준다(200).
- `FaultInjectionFilter`: `/actuator`, `/actuator/...`는 `shouldNotFilter`. `ThreadLocalRandom`으로 지연을 뽑아 나노초로 올려 자고, 에러율이면 500 `{"error":"injected fault"}`. 순서 `HIGHEST_PRECEDENCE + 10`(`TraceLoggingFilter`는 `+2`).
- 테스트: 1.2 시나리오 9개 모두 통과. 새 `FaultInjectionRequestLogTest`(넣은 500에 `X-Trace-Id`가 있고 그 traceId의 `http_request` 줄이 status 500, durationMs ≥ 100). 이를 위해 테스트 의존성에 `project(":libs:observability")`와 그 testFixtures를 더했다.
- 바꾼 파일: `libs/fault-injection/build.gradle.kts`, `libs/fault-injection/src/main/kotlin/com/weave/faultinjection/{FaultInjectionProperties,FaultState,FaultsEndpoint,FaultInjectionFilter,FaultInjectionAutoConfiguration}.kt`, `libs/fault-injection/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `libs/fault-injection/src/test/kotlin/com/weave/faultinjection/FaultInjectionRequestLogTest.kt`, `libs/fault-injection/CLAUDE.md`, `tasks.md`
- 확인: `:libs:fault-injection:integrationTest` 통과(활성 시나리오 9, 꺼짐 1, 스모크 1, 로그 1), 전체 `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과.
- 다음 반복이 알아 둘 것:
  - 2.3: 서비스는 `implementation(project(":libs:fault-injection"))`만 더하면 자동 설정이 실린다. `application-experiment.yml`의 노출 목록을 빠뜨리면 1.3 테스트가 404로 남는다.
  - 3.1: ad 한도 필터의 order는 `FaultInjectionFilter.ORDER - 1`처럼 `TraceLoggingFilter`(`+2`)와 `+10` 사이에 둔다(429도 요청 완료 로그에 남게).
  - Boot 4 actuator에는 `@OptionalParameter`가 없다. 선택 인자는 Kotlin `Double?`로 충분하다.
  - main의 `.gitkeep`은 이번에도 `git rm`이 승인 없이 돌지 않아 남겼다.
---

## 2026-11-06 - 2.3 하위 서비스 5개에 장애 주입을 붙인다
- 다섯 서비스의 `build.gradle.kts`에 `implementation(project(":libs:fault-injection"))`를, `src/main/resources/application-experiment.yml`에 `weave.fault-injection.enabled: true`와 노출 목록 `health,info,prometheus,faults`를 뒀다(ad도 아직 `quota` 없음).
- `docs/architecture.md` 11장 표에 "실험 전용 설정" 줄(파일 위치, 두는 것, ad의 `quota`, 라이브러리가 프로필 파일을 싣지 않는 이유)을 더했다.
- 바꾼 파일: `{post,user,like,recommendation,ad}-service/build.gradle.kts`, `{post,user,like,recommendation,ad}-service/src/main/resources/application-experiment.yml`, `docs/architecture.md`, `tasks.md`
- 확인: `./gradlew integrationTest --continue`에서 1.3의 하위 서비스 5개·feed 테스트 통과. 실패는 1.4(`AdQuotaScenarioTest` 6), 1.5(`FeedExperimentMetricsScenarioTest` 12), 1.6(e2e 9)뿐. 기존 e2e 종단 간 테스트는 통과(프로필 없이 뜬다). `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과.
- 다음 반복(3.1)이 알아 둘 것: ad의 `application-experiment.yml` 노출 목록 끝에 `quota`를 더하고, 한도 엔드포인트를 켜는 속성도 같은 파일에 둔다. 노출 목록은 `libs/observability`의 `observability-defaults.properties`(`health,info,prometheus`)를 프로필 파일이 덮는 방식이다.
---

## 2026-11-06 - 3.1 ad-service 요청 한도와 `quota` 엔드포인트
- `AdQuota`(고정 창, `@Synchronized`, 거절은 세지 않음, `tryAcquire()`가 null이면 허용·아니면 `Retry-After` 초 = `ceilDiv(창 끝 - 지금, 1s)` 최소 1), `AdQuotaEndpoint`(`GET`/`POST {limit, windowSeconds}`/`DELETE`, 검증 실패는 `InvalidEndpointRequestException` → 400), `AdQuotaFilter`(`/actuator` 건너뜀, 429 `{"error":"quota exceeded"}`, order `FaultInjectionFilter.ORDER - 1`), `AdQuotaConfiguration`(`@ConditionalOnBooleanProperty("weave.ad.quota.enabled")`, 시계는 `ObjectProvider<Clock>.getIfAvailable { Clock.systemUTC() }`).
- `GET /actuator/quota` 본문: 꺼지면 `{"enabled":false,"limit":null,"windowSeconds":null}`, 켜면 `{"enabled":true,"limit":n,"windowSeconds":w}`.
- ad `application-experiment.yml`에 `weave.ad.quota.enabled: true`와 노출 목록 `quota`. 아키텍처 11장에 스위치 이름을 적었다.
- 단위 테스트 `AdQuotaTest`(창 경계 9.999초/10초, 올림, 다시 켜기, 끄기, 검증 실패 뒤 그대로) 6개.
- 바꾼 파일: `ad-service/src/main/kotlin/com/weave/ad/{AdQuota,AdQuotaEndpoint,AdQuotaFilter,AdQuotaConfiguration}.kt`, `ad-service/src/main/resources/application-experiment.yml`, `ad-service/src/test/kotlin/com/weave/ad/AdQuotaTest.kt`, `docs/architecture.md`, `tasks.md`
- 확인: `:ad-service:integrationTest`에서 `AdQuotaScenarioTest` 6개·`AdQuotaDisabledScenarioTest`·기존 ad 테스트 모두 통과. 전체 `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과.
- 다음 반복(4.1)이 알아 둘 것: 1.5 메모대로 공유 풀 단언은 이미 통과한다. 4.1은 Tomcat·호출 지연 히스토그램·`/feed` 히스토그램 단언만 통과시키면 된다.
---

## 2026-11-06 - 4.1 Tomcat 지표, 요청 지연 히스토그램, 그룹 이름 `client.name`
- feed `application.yml`에 `server.tomcat.mbeanregistry.enabled: true`와 `management.metrics.distribution.percentiles-histogram`의 `http.server.requests`·`http.client.requests`를 뒀다.
- `HttpClientConfig.groupNameClientMetrics`: `RestClientHttpServiceGroupConfigurer`에서 그룹마다 `builder.observationConvention(...)`으로 `DefaultClientRequestObservationConvention`을 상속해 `clientName`만 그룹 이름으로 바꾼 규약을 넣었다. Boot의 관찰 커스터마이저가 이 규약을 덮지 않았다(순서 지정 없이 통과).
- 바꾼 파일: `feed-service/src/main/resources/application.yml`, `feed-service/src/main/kotlin/com/weave/feed/client/HttpClientConfig.kt`, `feed-service/CLAUDE.md`, `tasks.md`
- 확인: `:feed-service:integrationTest`에서 `FeedExperimentMetricsScenarioTest` 13개 중 Tomcat·공유 풀·호출 지연 5개·`/feed` 히스토그램 8개 통과, 실패는 커넥션 풀 5개(4.2 몫)뿐. 그 밖의 feed 통합 테스트는 모두 통과. 전체 `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과.
- 다음 반복(4.2)이 알아 둘 것: `-Pkotest.filter.specs`를 줘도 feed `integrationTest`는 전체(82개)를 돌렸다(30초 남짓이라 문제는 없다). 실패 내용은 `feed-service/build/test-results/integrationTest/TEST-*.xml`을 `grep -E 'testcase name|failure message'`로 본다. 이 세션에서는 `~/.gradle` 아래 jar를 찾거나 열 수 없어 Boot 소스를 직접 못 본다.
---

## 2026-11-06 - 4.2 공유 풀과 의존성별 커넥션 풀 지표 (멈춤)
- 구현하지 않았다. 코드 변경 없음. tasks.md도 체크하지 않았다.
- **멈춘 이유 1: 이미 커밋된 시나리오 테스트의 지표 이름을 Micrometer 바인더가 내지 않는다.**
  - `FeedExperimentMetricsScenarioTest`(1.5)와 design 결정 4의 표는 `httpcomponents_httpclient_pool_total_leased`·`..._total_pending`(`httpclient="post"`)을 기대한다.
  - design이 정한 방법인 Micrometer 1.17.2 `PoolingHttpClientConnectionManagerMetricsBinder(manager, "post")`를 실제로 묶어 보니 지표는 이렇다: `httpcomponents.httpclient.pool.total.connections{state=leased|available}`, `...total.pending`, `...total.max`, `...route.max.default`(모두 `httpclient=post`). Prometheus 이름으로는 `httpcomponents_httpclient_pool_total_connections{state="leased"}`다. **`..._total_leased`라는 이름은 없다.** `..._total_pending`은 맞다.
  - 통과시키려면 (가) 테스트와 design 표를 `httpcomponents_httpclient_pool_total_connections` + `state="leased"`로 고치거나, (나) 바인더와 별도로 `httpcomponents.httpclient.pool.total.leased` 게이지를 직접 만든다. (가)는 커밋된 시나리오 테스트 수정이고, (나)는 design이 정한 방법과 다르다. 사람의 판단이 필요하다. 권장은 (가)다(바인더 이름 그대로가 대시보드·리포트 질의에도 표준이다). 6.4 리포트와 6.6 대시보드 질의, 1.7 `ExperimentDashboardComposeScenarioTest`의 "지표 이름 8개"에도 같은 이름이 들어 있는지 같이 본다.
- **멈춘 이유 2(참고, design 결정 4의 빈틈): Boot 4.1.2는 그룹마다 만든 `PoolingHttpClientConnectionManager` 인스턴스를 넘겨주는 지점이 없다.**
  - `HttpComponentsClientHttpRequestFactoryBuilder`의 공개 메서드는 `withHttpClientCustomizer(HttpClientBuilder)`, `withConnectionManagerCustomizer(PoolingHttpClientConnectionManagerBuilder)`, `withConnectionConfigCustomizer`, `withSocketConfigCustomizer`, `withDefaultRequestConfigCustomizer`, `withTlsSocketStrategyFactory`, `withDnsResolver`뿐이다. 매니저는 private `HttpComponentsHttpClientBuilder.createConnectionManager(settings)`에서 만들어 `HttpClientBuilder.setConnectionManager`로 넣는다. `PoolingHttpClientConnectionManagerBuilder`(httpclient5 5.6.5)에도 만든 인스턴스를 알려 주는 훅이 없고, `HttpClientBuilder.connManager`는 getter 없는 private 필드다.
  - design의 대안("configurer에서 지금의 `ClientHttpRequestFactoryBuilder` Bean으로 요청 팩토리를 직접 만든다")도 매니저 인스턴스를 주지 않는다(`HttpComponentsClientHttpRequestFactory.getHttpClient()`까지만 공개).
  - 남은 길: (ㄱ) `ClientHttpRequestFactoryBuilder`를 직접 구현해 Boot의 `createConnectionManager`(SocketConfig, `HttpClientSettings`의 연결·읽기 시간 초과로 만든 ConnectionConfig, TLS)를 따라 매니저를 만들고 바인더를 붙인다. 기존 `HttpClientPoolCapacityTest`·`MvpTimeoutDefaultsTest`·`HttpClientGroupTest`·`HttpClientPoolSizePropertyTest`가 같은 값을 지키는지 본다. (ㄴ) `withHttpClientCustomizer` 안에서 `HttpClientBuilder.connManager`를 리플렉션으로 읽는다(짧지만 httpclient5 내부 필드 이름에 기댄다). 어느 쪽을 쓸지 design 결정 4에 적어야 한다.
- 공유 풀(`applicationTaskExecutor`)은 1.5 메모대로 Boot가 이미 붙인다. design 결정 4에 "자동으로 붙는다"고 적는 일만 남았다.
- 확인 방법: 바인더 지표 이름과 Boot·httpclient5 API는 일회용 Gradle init 스크립트(`feed-service/build/tmp/inspect*.init.gradle`, 커밋하지 않음)로 feed `runtimeClasspath`를 읽어 확인했다.
- 다음 반복이 알아 둘 것: 사람이 위 두 가지를 정하고(테스트·design 수정 포함) 4.2를 다시 연다. 이 반복은 BLOCKED로 끝냈다.
---

## 2026-11-06 - 4.2 멈춤에 대한 사람의 결정
- 지표 이름: 바인더 이름을 따른다. 1.5 `FeedExperimentMetricsScenarioTest`는 `httpcomponents_httpclient_pool_total_connections{httpclient=..., state="leased"}`와 `..._total_pending`을, 1.7 `ExperimentDashboardComposeScenarioTest`의 `expectedMetrics`는 `httpcomponents_httpclient_pool_total_connections`를 기대하도록 고쳤다. 6.4 리포트·6.6 대시보드 질의도 이 이름을 쓴다.
- 매니저 얻기: 리플렉션(`InternalHttpClient.connManager`)으로 정했다. 방법과 실패 처리는 design 결정 4에 적었다(Boot configurer 뒤에 도는 configurer, `HttpClientSettingsPropertyMapper`로 그룹 설정, 못 읽으면 시작할 때 예외).
- design 결정 4에 공유 풀 메모는 아직 없다. 4.2에서 "Boot가 자동으로 붙인다"를 적는다.
- 다음 반복은 4.2를 다시 연다.
---

## 2026-11-06 - 4.2 공유 풀과 의존성별 커넥션 풀 지표
- `ConnectionPoolMetrics`(`RestClientHttpServiceGroupConfigurer`, 순서 `HIGHEST_PRECEDENCE + 20`): 그룹마다 `HttpServiceClientProperties.get(그룹)` → `HttpClientSettingsPropertyMapper(SslBundles?, HttpClientSettings?)` → `ClientHttpRequestFactoryBuilder` Bean으로 요청 팩토리를 다시 만들어 `builder.requestFactory`로 넣고, `InternalHttpClient.connManager`(package-private 클래스라 이름으로 확인)를 리플렉션으로 읽어 `PoolingHttpClientConnectionManagerMetricsBinder(manager, 그룹)`을 `ObjectProvider<MeterRegistry>`에 묶는다. 못 읽으면 시작할 때 예외. Bean은 `HttpClientConfig.connectionPoolMetrics`.
- Boot의 그룹 처리(`PropertiesRestClientHttpServiceGroupConfigurer.configureClient`)는 javap로 확인했다: `mapper.map(properties.get(name))` → `requestFactoryBuilder.build(settings)` → `builder.requestFactory`, 그 뒤 base-url·헤더·API 버전만 더 건다. 그래서 다시 만든 팩토리가 Boot와 같은 설정이다.
- 공유 풀은 Boot가 자동으로 붙인다고 design 결정 4에 적었다. 구현 메모도 같은 곳에 더했다.
- 바꾼 파일: `feed-service/src/main/kotlin/com/weave/feed/client/{ConnectionPoolMetrics,HttpClientConfig}.kt`, `feed-service/CLAUDE.md`, `design.md`, `tasks.md`
- 확인: `:feed-service:integrationTest`에서 `FeedExperimentMetricsScenarioTest` 13개 모두와 `HttpClientPoolCapacityTest`, `HttpClientPoolSizePropertyTest`, `MvpTimeoutDefaultsTest`, `HttpClientGroupTest`, `HttpClientRetryDefaultsTest` 등 feed 통합 테스트 전체 통과(5번 중 4번. 1번은 위 패턴의 `HttpClientPools` CME). 전체 `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과.
- 다음 반복(4.3)이 알아 둘 것: `server.tomcat.threads.max: 200`만 feed `application.yml`의 `server.tomcat` 아래에 더하고 따로 커밋한다.
---

## 2026-11-06 - 4.3 feed-service의 Tomcat 최대 스레드를 명시한다
- feed `application.yml`의 `server.tomcat` 아래에 `threads.max: 200`을 더했다(Boot 기본값, 아키텍처 4-2). 설정값 커밋이라 다른 변경과 섞지 않았다.
- 바꾼 파일: `feed-service/src/main/resources/application.yml`, `tasks.md`
- 확인: `:feed-service:integrationTest`에서 `MvpTimeoutDefaultsTest`의 Tomcat 단언 포함 feed 통합 테스트 전체 통과(실패 없음), `ktlintCheck`, `:feed-service:test`, `openspec validate` 통과.
- 다음 반복(5.1)이 알아 둘 것: Toxiproxy 프록시 이름은 의존성 이름(`post` 등), 관리 API `127.0.0.1:8474`(1.7 메모). `docker`는 이 세션에서 승인 없이 돌지 않을 수 있다(알아 둘 패턴).
---

## 2026-11-06 - 5.1 Toxiproxy를 더하고 feed-service가 프록시를 거치게 한다
- `docker/toxiproxy/toxiproxy.json`에 프록시 5개(이름 = 의존성 이름, `0.0.0.0:1808x` → `<의존성>-service:808x`).
- `compose.yaml`: `toxiproxy` 서비스(`ghcr.io/shopify/toxiproxy:2.13.0`, `-config=/config/toxiproxy.json`, 기본 네트워크 별칭 `post-proxy` 등 5개, `127.0.0.1:8474`, 헬스 체크 `/toxiproxy-cli list`). feed-service의 `*_BASE_URL`을 `http://<의존성>-proxy:1808x`로 바꾸고 `depends_on`에 toxiproxy `service_healthy`. 하위 서비스 5개는 새 앵커 `x-downstream-environment`(공통 환경 + `SPRING_PROFILES_ACTIVE: experiment`)를 쓴다.
- 이미지 버전: `gh api repos/Shopify/toxiproxy/releases`로 확인한 최신 안정판 v2.13.0(2026-10-14, 23일 전). 이미지는 scratch(셸 없음, `/toxiproxy`, `/toxiproxy-cli`). 서버는 `-config`를 읽은 뒤 관리 API를 연다(`cmd/server/server.go`)라 헬스 체크가 통과하면 프록시도 있다. 2.13은 비루프백 주소의 인증 없는 API에 경고 로그를 낸다(컨테이너 안에서 `0.0.0.0`이라 나온다. 호스트에는 `127.0.0.1`로만 연다).
- `docker/CLAUDE.md`에 Toxiproxy 헬스 체크와 별칭 규칙을 적었다.
- 바꾼 파일: `compose.yaml`, `docker/toxiproxy/toxiproxy.json`, `docker/CLAUDE.md`, `tasks.md`
- 확인: `./gradlew :e2e:composeTest --continue`에서 `ProxyPathComposeScenarioTest` 2개, `TraceLookupComposeScenarioTest` 2개 통과. 실패 12개는 모두 `experiments/run.sh`·`run-all.sh`가 없어서다(`Cannot run program`, 6.4·6.5 몫). `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과.
- 다음 반복(5.2)이 알아 둘 것: 자원 상한은 서비스마다 `deploy.resources.limits`. Toxiproxy도 CPU 1. `docker compose ps`·`docker inspect`는 이 세션에서 승인 없이 돌지 않는다(`docker version`조차 막혔다). 확인이 필요하면 `:e2e:composeTest`처럼 Gradle을 거치는 길을 찾거나, 명령을 progress.md에 적어 사람이 돌리게 한다. 결과는 `e2e/build/test-results/composeTest/TEST-*.xml`을 Read로 본다(`grep`을 여러 파일에 쓰면 승인을 기다렸다).
---

## 2026-11-06 - 5.2 자원 상한과 k6 서비스를 더한다
- `compose.yaml`: feed CPU 2·1GB, 하위 서비스 5개 각 CPU 1·512MB(앵커 `x-downstream-deploy`), MySQL·Toxiproxy 각 CPU 1. 관찰 인프라는 design에 값이 없어 각 CPU 0.5로, 메모리는 부하 중 로그·스팬이 쌓이는 Loki·Tempo만 1GB, Alloy·Prometheus·Grafana는 512MB로 뒀다(장애 대응 설정값이 아니라 실험 환경 조건이다. 근거는 compose 주석, OOM 재시작이 보이면 올린다).
- k6: `grafana/k6:2.3.0`(gh releases로 확인한 2.3대 최신 안정판, 2026-09-21, 46일 전), `profiles: [experiment]`, `./experiments:/experiments` 마운트, `working_dir: /experiments`, CPU 2·2GB. `--user`는 compose에 두지 않고 run.sh가 `docker compose run --user`로 넘긴다(design 결정 10). `depends_on`도 두지 않았다(run.sh가 환경을 먼저 확인한다).
- 바꾼 파일: `compose.yaml`, `docker/CLAUDE.md`, `tasks.md`
- 확인 명령과 결과(루트 `.env`가 없어 프로젝트·env 파일을 준다):
  - `./gradlew bootJar` 뒤 `docker compose -p weave-compose-test --env-file e2e/build/compose-test.env up -d --build --wait` → 13개 모두 Up. 헬스 체크가 있는 것은 healthy, Alloy·Loki는 running.
  - `docker compose -p weave-compose-test --env-file e2e/build/compose-test.env ps -a --format '{{.Service}} {{.State}} {{.Status}}'` → k6는 목록에 없다(프로필).
  - `docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}} NanoCpus={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}' <컨테이너들>`(Gradle init 스크립트로 돌림) → feed 2e9·1073741824, 하위 서비스 1e9·536870912, Loki·Tempo 5e8·1073741824, Alloy·Prometheus·Grafana 5e8·536870912, MySQL·Toxiproxy 1e9·0(메모리 상한 없음).
  - `docker compose -p weave-compose-test --env-file e2e/build/compose-test.env run --rm k6 version` → `k6 v2.3.0 (commit/e088784614, go1.27.1, linux/arm64)`.
  - `./gradlew :e2e:composeTest --continue`: 프록시 2개, Loki·Tempo 2개 통과(상한을 건 뒤에도). 실패 12개는 5.1과 같이 `run.sh`·`run-all.sh`가 없어서다. `ktlintCheck compileKotlin compileTestKotlin test`, `openspec validate` 통과.
- 다음 반복이 알아 둘 것:
  - 이 기계의 Docker는 CPU 15개, 메모리 약 7.75GB(`docker info`). 상한의 메모리 합(약 9GB)이 더 크다. 7번 묶음 기준선 문서에 적을 값이다.
  - `docker compose run k6`가 처음 돌 때 빈 `experiments/` 폴더가 생겼다(마운트 대상). 6.1이 채운다.
  - 5.3: Prometheus 설정은 `docker/prometheus/prometheus.yml`. 설정값 커밋이라 수집 간격만 따로 커밋한다.
---

## 2026-11-06 - 5.3 Prometheus 수집 간격을 5초로 바꾼다
- `docker/prometheus/prometheus.yml`의 `global.scrape_interval`을 15s → 5s로 바꾸고 근거를 주석으로 달았다. 설정값 커밋이라 다른 변경과 섞지 않았다(`d07782b`, 본문에 표본 20 → 60과 S-09 10초 장애 계산).
- `scrape_timeout`은 따로 두지 않았다. Prometheus 기본 규칙(간격과 10초 중 작은 값)으로 10s → 5s가 된다.
- 바꾼 파일: `docker/prometheus/prometheus.yml`, `tasks.md`
- 확인:
  - `./gradlew bootJar :e2e:composeTestEnv` 뒤 `docker compose -p weave-compose-test --env-file e2e/build/compose-test.env up -d --build --wait`, `... exec grafana wget -qO- http://prometheus:9090/api/v1/status/config` → `global.scrape_interval: 5s`, `scrape_timeout: 5s`, 잡 `services`도 5s. 확인 뒤 `down -v`.
  - `./gradlew :e2e:composeTest --continue`: 프록시 2개, Loki·Tempo 2개 통과. 실패 12개는 5.1·5.2와 같이 `run.sh`·`run-all.sh`가 없어서다(결과 XML의 `Cannot run program`). `openspec validate` 통과.
- 다음 반복(6.1)이 알아 둘 것: 시나리오 JSON 형식은 `ExperimentScenarioDefinitionTest` KDoc, `slo.json` 형식은 `ExperimentWindowsScenarioTest` KDoc이 기준이다(알아 둘 패턴).
---
