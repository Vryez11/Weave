# Tasks

## 1. 준비와 시나리오 테스트

시나리오 테스트는 HTTP와 프로세스 수준의 블랙박스 테스트로 쓴다(design 결정 13). 구현이 없으니 `integrationTest`에서는 실패하고, `test`(Stop 훅)는 통과해야 한다. Compose 태그 테스트는 이 묶음에서 컴파일만 확인한다. 테스트 이름에 `F-007`을 넣는다.

- [x] 1.1 `libs/fault-injection` 모듈의 뼈대를 만든다.
  - `settings.gradle.kts`에 모듈을 넣고, `weave.spring-library`를 쓰는 `build.gradle.kts`와 빈 패키지(`com.weave.faultinjection`)를 둔다. 하위 서비스에는 아직 붙이지 않는다.
  - 테스트 소스에 업무 엔드포인트 하나(`GET /work`)가 있는 작은 웹 애플리케이션을 둔다(`libs/observability`의 testapp과 같은 방식).
  - 확인: `./gradlew ktlintCheck build`가 통과한다.
- [x] 1.2 `fault-injection` 스펙의 시나리오를 `libs/fault-injection` 테스트로 옮긴다("실험 프로필로 띄운다"와 "feed-service에는 없다"는 1.3에서 한다).
  - 테스트 앱을 `weave.fault-injection.enabled=true`와 노출 목록으로 띄우는 스펙과, 속성 없이 띄우는 스펙("프로필 없이 띄운다")을 나눈다.
  - "기본 분포를 따른다"는 `GET /work`를 1,000번 부른다.
  - 확인: `./gradlew :libs:fault-injection:integrationTest`가 새 테스트에서 실패하고, `./gradlew :libs:fault-injection:test`는 통과한다.
- [x] 1.3 "실험 프로필로 띄운다"를 하위 서비스 5개의 테스트로, "feed-service에는 없다"를 feed-service 테스트로 옮긴다.
  - 서비스마다 `experiment` 프로필로 띄워 `GET /actuator/faults`를 부른다. like-service는 기존 MySQL Testcontainers 설정을 쓴다.
  - 확인: `./gradlew integrationTest`에서 하위 서비스 5개의 새 테스트가 실패하고, feed-service의 새 테스트는 통과한다(이미 404다). `./gradlew test`는 통과한다.
- [x] 1.4 `ad-serving` 델타의 "요청 한도" 시나리오 여섯 개를 ad-service 테스트로 옮긴다.
  - 테스트 설정이 시각을 옮길 수 있는 `Clock` Bean을 넣는다. 구현은 컨텍스트의 `Clock`을 쓰고, 없을 때만 시스템 시계를 쓴다(design 결정 3).
  - "다음 창"만 실제 시계로 `Retry-After`만큼 기다린다.
  - 확인: `./gradlew :ad-service:integrationTest`가 새 테스트에서 실패하고, `./gradlew :ad-service:test`는 통과한다.
- [x] 1.5 `fault-experiments`의 "feed-service의 실험 지표"를 feed-service 테스트로 옮긴다.
  - 기존 `DownstreamWireMock`으로 하위 서비스 5개가 정상 응답하게 하고 `GET /feed` 뒤 `/actuator/prometheus` 본문을 본다.
  - 지표 이름은 design 결정 4의 표를 따른다. 의존성은 `client_name`과 `httpclient` 라벨의 값(`post` 등)으로 찾는다.
  - 확인: `./gradlew :feed-service:integrationTest`가 새 테스트에서 실패하고, `./gradlew :feed-service:test`는 통과한다.
- [x] 1.6 실험 세트의 계산·판정 시나리오를 `e2e`의 `Integration` 태그 테스트로 옮긴다(design 결정 13).
  - "시나리오마다의 장애", "기본 목록": `experiments/scenarios/S-*.json`과 `run-all.sh`의 기본 목록을 읽는다.
  - "기대대로 SLO를 못 지킨다", "기대와 다르다": 미리 만든 k6 요약 파일로 `experiments/lib/verdict.sh <요약> <시나리오 ID>`를 돌려 출력(판정 문구)과 종료 코드를 본다.
  - "복구 시간", "복구하지 못함": 미리 만든 k6 원본 파일로 `experiments/lib/windows.sh`를 돌려 10초 구간 표와 복구 시간을 본다.
  - 스크립트의 인자와 출력(JSON) 형식은 이 작업에서 정해 테스트 KDoc에 적는다. 픽스처는 `e2e/src/test/resources/experiments/`에 둔다.
  - 확인: `./gradlew :e2e:integrationTest`가 새 테스트에서 실패하고, 기존 종단 간 테스트는 통과한다. `./gradlew :e2e:test`는 통과한다.
- [x] 1.7 프록시 경로와 대시보드 시나리오를 `e2e`의 `Compose` 태그 테스트로 옮긴다.
  - "프록시에 지연을 넣는다", "한 의존성의 프록시를 끊는다": Toxiproxy API(`127.0.0.1:8474`)를 테스트가 직접 부른다. 하위 서비스 호출 실패 로그는 `docker compose logs feed-service`에서 읽는다. 끝나면 `POST /reset`으로 되돌린다.
  - "대시보드를 불러온다": Grafana `/api/search`와 `/api/dashboards/uid/weave-feed`, Prometheus `/api/v1/query`를 쓴다. 패널 질의의 시간 변수는 `5m`으로 바꿔 부른다.
  - 확인: `./gradlew :e2e:compileTestKotlin`이 통과하고, `./gradlew :e2e:test :e2e:integrationTest`에서 이 테스트가 돌지 않는다(테스트 리포트에 없다).
- [x] 1.8 `run.sh`·`run-all.sh`의 시나리오를 `e2e`의 `Compose` 태그 테스트로 옮긴다.
  - 대상: "짧게 돌린다", "없는 시나리오", "환경이 떠 있지 않다", "앞에 남은 장애", "정상 종료 뒤", "중간에 멈춘다", "준비 구간은 세지 않는다", "모든 요청이 실패한다", "리포트 항목", "부하 도구가 실패한다", "판정 표".
  - `run.sh`는 `ProcessBuilder`로 돌리고 `COMPOSE_PROJECT_NAME`, `COMPOSE_ENV_FILE`을 시스템 속성에서 받아 넘긴다(design 결정 6). 리포트는 결과 폴더의 `report.json`을 읽는다.
  - 하위 서비스의 장애 설정은 `docker compose exec <서비스> curl localhost:<포트>/actuator/faults`로, 프록시는 Toxiproxy API로 확인한다.
  - "중간에 멈춘다"는 리포트 폴더의 k6 원본에 측정 구간 요청이 생긴 뒤 SIGTERM(`Process.destroy()`)을 보낸다. "부하 도구가 실패한다"는 문법 오류가 있는 시나리오 JSON을 임시로 둬서 만든다.
  - 확인: `./gradlew :e2e:compileTestKotlin`이 통과하고, `./gradlew :e2e:test :e2e:integrationTest`에서 이 테스트가 돌지 않는다.

## 2. 앱 수준 장애 주입

- [x] 2.1 지연 분포 뽑기와 장애 설정 검증을 만든다(design 결정 2의 "분포", "엔드포인트").
  - 로그 정규분포(μ = ln 중앙값, σ = ln(p99/중앙값)/2.326). 중앙값 = p99면 고정 지연이다. `Random`을 받는다.
  - 설정 검증: 에러율 0~1, 지연 0 이상, p99 ≥ 중앙값.
  - 확인: 시드를 준 단위 테스트에서 10,000번 뽑은 중앙값·p99가 설정값의 ±5% 안이고, 검증 실패 경우마다 단위 테스트가 있다. `./gradlew :libs:fault-injection:test -Pkotest.filter.tests='*F-007*'`가 통과한다.
- [x] 2.2 필터, `faults` 엔드포인트, 자동 설정을 만든다(design 결정 2).
  - `weave.fault-injection.enabled`가 true일 때만 등록한다. 기본값(중앙값 20ms, p99 50ms, 에러율 0)은 속성 클래스에 둔다.
  - 필터는 `/actuator`, `/actuator/`로 시작하는 경로를 건너뛰고, `libs/observability`의 추적·요청 로그 필터 뒤에 둔다.
  - 확인: 1.2의 시나리오가 모두 `./gradlew :libs:fault-injection:integrationTest`로 통과한다. 넣은 500 응답의 요청 완료 로그에 `traceId`가 있는지 테스트 하나를 더한다.
- [x] 2.3 하위 서비스 5개에 장애 주입을 붙인다.
  - 다섯 서비스의 `build.gradle.kts`에 의존성을, `src/main/resources/application-experiment.yml`에 `weave.fault-injection.enabled: true`와 노출 목록(`health,info,prometheus,faults`)을 둔다. ad-service는 3.1에서 `quota`를 더한다.
  - `docs/architecture.md` 11장에 `application-experiment.yml`에 두는 것을 적는다.
  - 확인: 1.3의 테스트가 모두 통과하고 `./gradlew test integrationTest`가 1.4~1.6의 아직 구현하지 않은 시나리오 말고는 통과한다.

## 3. ad 요청 한도

- [x] 3.1 ad-service에 요청 한도와 `quota` 엔드포인트를 만든다(design 결정 3).
  - 고정 창, 거절한 요청은 세지 않음, `Retry-After = ceil(창 끝 - 지금)`(최소 1). 한도 검사 필터는 장애 주입 필터보다 앞에 둔다.
  - `application-experiment.yml`에서만 엔드포인트를 켜고 노출 목록에 `quota`를 더한다.
  - 확인: 1.4의 여섯 시나리오가 `./gradlew :ad-service:integrationTest`로 통과하고, 기존 ad 테스트도 통과한다.

## 4. feed-service 실험 지표

- [x] 4.1 Tomcat 지표, 요청 지연 히스토그램, 그룹 이름 `client.name`을 넣는다(design 결정 4).
  - `server.tomcat.mbeanregistry.enabled`, `management.metrics.distribution.percentiles-histogram`의 `http.server.requests`·`http.client.requests`를 feed-service `application.yml`에 둔다.
  - `RestClientHttpServiceGroupConfigurer`에서 그룹마다 관찰 규약을 넣어 `client.name`이 그룹 이름이 되게 한다.
  - 확인: 1.5 중 Tomcat, 호출 지연 히스토그램, `/feed` 히스토그램 단언이 통과한다(커넥션 풀과 공유 풀 단언은 4.2까지 실패해도 된다).
- [x] 4.2 공유 풀과 의존성별 커넥션 풀 지표를 넣는다(design 결정 4).
  - `applicationTaskExecutor`에 실행기 메트릭이 이미 붙는지 먼저 확인한다. 안 붙으면 직접 묶는다. 어느 쪽이었는지 design 결정 4에 적는다.
  - 그룹마다 커넥션 매니저에 Micrometer HttpClient 5 풀 바인더를 그룹 이름으로 등록한다. 매니저를 얻은 방법을 design 결정 4에 적는다.
  - MVP의 HTTP 클라이언트 구성(재시도 끔, 풀 크기, 타임아웃 기본값)이 바뀌면 멈추고 design을 고친다.
  - 확인: 1.5가 모두 통과하고 `HttpClientPoolCapacityTest`, `MvpTimeoutDefaultsTest`, `HttpClientGroupTest`, `HttpClientRetryDefaultsTest`를 포함한 `./gradlew :feed-service:test :feed-service:integrationTest`가 통과한다.
- [x] 4.3 feed-service의 Tomcat 최대 스레드를 명시한다(design 결정 10).
  - `server.tomcat.threads.max: 200`. 설정값 커밋이라 다른 변경과 섞지 않는다. 커밋 본문에 Boot 기본값과 같고 아키텍처 4-2에서 확인한 값이라고 적는다.
  - 확인: `MvpTimeoutDefaultsTest`의 Tomcat 단언이 그대로 통과한다.

## 5. Compose 실험 환경

- [x] 5.1 Toxiproxy를 더하고 feed-service가 프록시를 거치게 한다(design 결정 1, 10).
  - `docker/toxiproxy/toxiproxy.json`에 프록시 5개, `compose.yaml`에 Toxiproxy 서비스(버전 고정 이미지, 별칭 5개, 헬스 체크, 관리 API `127.0.0.1:8474`)를 둔다. feed-service는 Toxiproxy가 healthy가 된 뒤 뜬다.
  - feed-service의 `*_BASE_URL`을 별칭 주소로 바꾸고, 하위 서비스 5개에 `SPRING_PROFILES_ACTIVE=experiment`를 준다.
  - 확인: `./gradlew :e2e:composeTest`에서 1.7의 프록시 시나리오 두 개와 기존 Loki·Tempo 시나리오가 통과한다.
- [x] 5.2 자원 상한과 k6 서비스를 더한다(design 결정 10).
  - 서비스마다 `deploy.resources.limits`(feed CPU 2·1GB, 하위 서비스 각 CPU 1·512MB, MySQL·Toxiproxy 각 CPU 1, 관찰 인프라 상한, k6 CPU 2·2GB)를 둔다.
  - `grafana/k6` 이미지(버전 고정)를 `profiles: [experiment]` 서비스로 두고 `experiments/`를 마운트한다.
  - 확인: `docker compose up -d --build --wait` 뒤 `docker compose ps`에서 k6 말고 모두 healthy(헬스 체크가 없는 이미지는 running)이고, `docker inspect`로 상한이 걸린 것을 본다. `docker compose run --rm k6 version`이 버전을 출력한다. 이 확인 명령을 progress.md에 적는다.
- [x] 5.3 Prometheus 수집 간격을 5초로 바꾼다(design 결정 10).
  - 설정값 커밋이라 다른 변경과 섞지 않는다. 커밋 본문에 측정 5분의 표본 수(20 → 60)와 S-09의 10초 장애 계산을 적는다.
  - 확인: Compose 환경의 Prometheus `/api/v1/status/config`에서 `scrape_interval: 5s`가 보이고, `./gradlew :e2e:composeTest`가 통과한다.

## 6. 실험 세트

- [x] 6.1 실험 설정과 시나리오 정의를 만든다(design 결정 5, 8, 9).
  - `experiments/config.json`(RPS 200은 출발점이고 7.1에서 정한다, 준비 1분, 측정 5분, 요청 시간 초과 5초, 사용자 ID 1~1,000), `slo.json`(PRD 2장), `expectations.json`(고도화 2: S-01만 `meet`), `scenarios/S-01.json`~`S-09.json`
  - 확인: 1.6의 "시나리오마다의 장애" 시나리오가 통과한다.
- [x] 6.2 k6 스크립트를 만든다(design 결정 5).
  - `k6/feed.js`: `warmup`·`measure`·`faults` 시나리오, `measure` 태그로 건 threshold, 가용성 `Rate`, 보내지 못한 요청, `kind: fault` 태그. `setup()`·`teardown()`이 되돌리기를 한다.
  - `k6/contract.js`(MVP는 부분 응답 없음), `k6/reset.js`(되돌리기만)
  - 확인: Compose 환경에서 `docker compose run --rm k6 run` 으로 S-02를 RPS 5, 준비 5초, 측정 20초로 직접 돌려 요약의 `measure` 요청 수가 95~105, p50이 3,000ms 이상이고, 끝난 뒤 Toxiproxy `GET /proxies`에 톡식이 없다. 명령과 결과를 progress.md에 적는다.
- [x] 6.3 판정과 10초 구간 계산 스크립트를 만든다(design 결정 7, 8).
  - `experiments/lib/verdict.sh`, `experiments/lib/windows.sh`. 1.6에서 정한 인자와 출력 형식을 따른다. bash 3.2에서 돈다.
  - 확인: 1.6의 판정·복구 시나리오가 `./gradlew :e2e:integrationTest`로 통과한다.
- [x] 6.4 `experiments/run.sh`를 만든다(design 결정 6, 7).
  - 옵션 확인, 환경 확인, `trap` 되돌리기(`k6/reset.js`), feed-service 다시 띄우기, k6 실행, Prometheus 질의, `report.md`·`report.json`, 종료 코드 0/1/2
  - 확인: 1.8 중 `run-all.sh` 말고 모든 시나리오가 `./gradlew :e2e:composeTest`로 통과한다.
- [x] 6.5 `experiments/run-all.sh`를 만든다(design 결정 8).
  - 확인: 1.6의 "기본 목록"과 1.8의 "판정 표"가 통과한다.
- [x] 6.6 Grafana 대시보드를 더한다(design 결정 11).
  - 공급자 설정, `docker/grafana/dashboards/feed-experiments.json`(uid `weave-feed`), `compose.yaml`의 마운트
  - `docs/architecture.md` 7-4에 지금 있는 패널과, 서킷·벌크헤드·degrade 패널은 F-001·F-002에서 더한다는 것을 적는다.
  - 확인: 1.7의 "대시보드를 불러온다"가 `./gradlew :e2e:composeTest`로 통과한다.
- [x] 6.7 명령과 구성을 문서에 적는다(design 결정 14).
  - 하네스 규칙 5장과 `CLAUDE.md` 명령 표에 `experiments/run.sh`, `experiments/run-all.sh`(Compose 환경을 먼저 띄운다)
  - `docs/architecture.md` 3장, 9장(프록시 표, 장애 주입 방법, 명령, 리포트 항목), 브랜치 규칙의 "장애 실험 세트 통과"
  - README의 실행 방법
  - 확인: 문서에 적은 명령을 그대로 돌려 `experiments/run.sh S-01 --rps 5 --warmup 5s --duration 20s`가 0으로 끝난다.
- [x] 6.8 전체 검사를 돌린다. 확인: `./gradlew ktlintCheck build`, `./gradlew :e2e:composeTest`, `openspec validate --all --strict --no-interactive`가 통과한다.

### 기준선에서 찾은 측정 결함

7.3의 첫 기준선 실행(400 RPS)에서 S-02·S-03·S-08의 리포트 값이 실제와 달랐다. 고친 뒤 7.3을 다시 돈다(design 결정 5, 7, 10, 11).

- [x] 6.9 결함을 드러내는 시나리오를 테스트로 먼저 옮긴다.
  - `ExperimentRunComposeScenarioTest`: "시간 초과된 요청", "Tomcat 스레드가 모두 묶여도 긁힌다"(같은 S-03 60 RPS 실행 하나로), "리포트 항목"의 수집 비율. KDoc의 `report.json` 형식에 `feedScrapeRatio`와 `feed_latency`를 적는다.
  - k6 원본·요약 픽스처와 `ExperimentRuns.hasMeasureRequest`, `ExperimentWindowsScenarioTest`, `ExperimentVerdictScenarioTest`를 `feed_latency`로 바꾼다.
  - 확인: 새 테스트와 바꾼 테스트가 지금 코드에서 실패한다.
- [x] 6.10 feed-service의 Actuator를 관리 포트 8090으로 연다(design 결정 10, 11). Compose 환경 변수, 헬스 체크, Prometheus 대상, `run.sh`의 feed-service 대상, 대시보드 질의. 확인: "Tomcat 스레드가 모두 묶여도 긁힌다"와 대시보드 시나리오가 통과한다.
- [x] 6.11 피드 지연을 `feed_latency`로 잰다(design 결정 5, 7). `k6/feed.js`의 `Trend`와 threshold, `run.sh`의 지연, `lib/windows.sh`의 구간 p99. 확인: "시간 초과된 요청"과 `:e2e:integrationTest`가 통과한다.
- [x] 6.12 리포트에 feed-service 지표 수집 비율을 적는다(design 결정 7). 확인: "리포트 항목"이 통과한다.
- [x] 6.13 문서(아키텍처 7장·9장)를 고치고 전체 검사를 다시 돌린다. 확인: `./gradlew ktlintCheck build`, `./gradlew :e2e:composeTest`, `openspec validate --all --strict --no-interactive`가 통과한다.

### 검토에서 나온 것

8.1 검토의 막음 1건과 제안 중 이 change에서 받은 것이다.

- [x] 6.14 (막음) 판정의 가용성에 보내지 못한 요청을 넣는다. k6 `availability` Rate는 보낸 요청만 세어, 보내지 못한 요청이 있어도 SLO 충족으로 판정될 수 있었다. `lib/verdict.sh`가 passes ÷ (측정 요청 수 + 보내지 못한 요청 수)로 다시 계산한다. 확인: "보내지 못한 요청도 판정에 들어간다"(`summary-S-01-dropped.json`)가 통과하고 기준선 요약의 판정이 그대로다.
- [x] 6.15 하위 서비스 5개에 "프로필 없이 띄운다" 테스트를 더한다(전에는 라이브러리 테스트 앱으로만 봤다). 확인: 다섯 서비스의 `integrationTest`가 통과한다.
- [x] 6.16 k6 자원 상한의 근거를 실험 RPS 400의 가상 사용자 2,200과 기준선 측정으로 고치고, k6에 `TZ=UTC`를 준다(`lib/windows.sh`는 `Z`로 끝나는 시각만 읽는다). 확인: `docker compose config`가 통과한다.

## 7. 실험 RPS와 기준선

이 묶음은 오래 걸린다(합쳐 약 2시간). 노트북을 전원에 연결하고 다른 무거운 프로그램을 끈 상태에서 돌린다. 결과의 원본은 커밋하지 않고 요약만 문서에 적는다.

**Ralph 루프는 이 묶음을 하지 않는다.** 여기에 오면 구현하지 말고 progress.md에 "6번 묶음까지 끝남, 7번 묶음은 사람과 함께 `/opsx:apply`로 진행"이라고 적은 뒤 `<promise>BLOCKED</promise>`로 멈춘다. 측정 하나가 반복 세션의 명령 시간 제한(10분)을 넘고(`run-all.sh`는 약 1시간), 노트북 상태를 사람이 맞춰야 하기 때문이다.

- [x] 7.1 실험 RPS를 정한다(design 결정 12).
  - S-01을 100, 200, 300, 400 RPS로 돌려 기준(SLO 충족, 보내지 못한 요청 0, feed CPU 최댓값 0.7 미만)을 모두 채우는 가장 높은 값을 고른다.
  - `docs/experiments/baseline-mvp.md`에 표와 실행 환경을 적고, `experiments/config.json`의 RPS를 바꾼다. 설정값 커밋이라 `config.json`만 따로 커밋하고, 본문에 고른 근거(표의 값)를 적는다.
  - 확인: 고른 RPS로 `experiments/run.sh S-01`이 0으로 끝난다.
- [x] 7.2 잡음을 잰다. 고른 RPS로 S-01을 세 번 더 돌려 p99·가용성의 최솟값·최댓값을 `docs/experiments/baseline-mvp.md`에 적는다. 확인: 문서에 세 번의 결과 폴더 이름과 값이 있다.
- [x] 7.3 기준선을 기록한다(design 결정 12).
  - `experiments/run-all.sh`를 기본값으로 돌려 시나리오마다의 판정, p50·p95·p99, 가용성, 자원 최댓값, S-08 복구 시간을 `docs/experiments/baseline-mvp.md`에 표로 적는다. 장애가 자원을 묶어 피드 전체로 번진 모습(어느 시나리오에서 Tomcat·공유 풀·커넥션 풀이 찼는지)을 몇 줄로 적는다.
  - `docs/architecture.md` 4-3에 "기준선 평시 p99" 열(S-01의 의존성별 호출 p99)을 더하고, 13장의 "실험 RPS와 실행 시간"을 지운다.
  - 기대와 다른 시나리오가 있으면 기대를 고치지 말고 원인을 찾는다. 시나리오가 장애를 재현하지 못한 것이면 시나리오나 실험 세트를 고친다. 기대 자체가 틀렸다고 판단되면 멈추고 사람에게 묻는다.
  - 확인: `experiments/run-all.sh`가 0으로 끝나고, 문서의 표가 그 실행의 리포트 값과 같다.

## 8. 검토와 change 완료 조건

이 묶음은 Ralph 루프가 하지 않는다([하네스 규칙](../../../conventions/harness.md) 3-3, 4장).

- [ ] 8.1 구현에 참여하지 않은 리뷰어가 하네스 규칙 3-3의 검토 항목으로 change 문서와 diff를 본다.
  - 이 change에 해당하지 않는 항목(3번 격리, 4번 재시도)은 "해당 없음"으로 적는다. 5번은 실험 조건 값(지연 분포, k6 시간 초과, 실험 RPS, 수집 간격, Tomcat 스레드)의 근거가 design이나 커밋에 있는지로 본다.
  - `libs/fault-injection`이 "하위 서비스끼리 코드를 공유하지 않는다"와 맞는지(design 결정 2)를 사람이 확인한다.
  - 사람이 Grafana에서 대시보드를 열어 기준선 실행 하나(S-03 권장)의 측정 구간을 보고, 리포트의 자원 최댓값과 그래프가 맞는지 확인한다.
  - 확인: 막는 지적이 없다.
- [ ] 8.2 하네스 규칙 4장의 change 완료 조건을 확인한다.
  - 장애 실험 세트 결과는 7.3의 기준선이다. PR 설명에 판정 표를 적는다.
  - PRD F-007 수용 기준을 체크한다.
  - 확인: 체크리스트가 모두 채워진다.

## Workflow follow-up

- `/opsx:archive`로 스펙을 `openspec/specs/`에 합친다.
- `develop`으로 PR을 열고 CI가 통과하면 merge한다.
- 고도화 2 릴리스: `develop` → `main` 릴리스 PR, merge 뒤 `v0.3.0` 태그. 릴리스 조건의 "장애 실험 세트 통과"는 `experiments/run-all.sh`가 0으로 끝나는 것이다.
