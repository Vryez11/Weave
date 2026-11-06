# Design

## Context

- 왜 하는지는 proposal.md의 Why를 본다. 이 change는 [아키텍처](../../../docs/architecture.md) 12장 표대로 9장(장애 실험 환경), 7-4(대시보드), 기준선 기록, 그리고 4-3·5-6을 채울 측정을 가져간다.
- 지금 Compose(`compose.yaml`)는 서비스 6개, MySQL, 관찰 인프라를 띄운다. feed-service는 하위 서비스를 Compose 서비스 이름으로 바로 부르고, 자원 상한과 Toxiproxy는 없다. Prometheus는 15초마다 긁는다.
- feed-service는 MVP 그대로다(아키텍처 4-2). 공유 스레드 풀 `applicationTaskExecutor`(코어 100, 대기열 무제한), 그룹마다 HttpClient 5 커넥션 풀(라우트당·전체 20), 타임아웃은 라이브러리 기본값(3분), 하위 서비스 하나라도 실패하면 500이다. 부분 응답(degrade)과 서킷 브레이커는 없다.
- 하위 서비스 5개는 고정 데이터를 지연 없이 돌려준다. 평시 지연은 실험이 만들어야 한다.
- `.gitignore`에 `experiments/results/`가 이미 있다. 호스트 쪽 도구는 훅이 이미 쓰는 `jq`와 Docker만 있다고 본다.

## Goals / Non-Goals

**Goals:**

- 같은 시나리오를 언제 돌려도 같은 시작 상태, 같은 장애 시각, 같은 판정 기준으로 잰다.
- 장애가 자원(스레드, 커넥션)을 묶어 피드 전체로 번지는 모습을 지연 수치와 함께 숫자로 보인다.
- 이후 change(F-001, F-002, F-005)가 실험 세트를 고치지 않고 다시 돌려 기준선과 비교할 수 있다. 그 change들이 더하는 값(degrade, 서킷)은 리포트와 대시보드에 끼워 넣을 자리만 둔다.

**Non-Goals:**

- 실험 결과를 CI에서 판정하기. 전체 세트는 약 1시간이 걸려 로컬에서 돌린다. CI는 짧은 스모크만 돌린다(결정 13).
- 노트북 잡음을 없애기. 잡음의 크기를 재서 기록하는 데까지만 한다(결정 12).
- feed-service의 동작 바꾸기. 메트릭 설정 말고는 손대지 않는다.

## Decisions

### 1. 호출 경로: Toxiproxy 하나, 의존성마다 프록시 하나

Toxiproxy 컨테이너 하나에 프록시 5개를 둔다. 프록시마다 Compose 네트워크 별칭을 붙여(`post-proxy` 등) feed-service의 주소를 `http://post-proxy:18081`처럼 준다. 포트는 아키텍처 2장의 예(`18081 → post:8081`)를 따른다. 프록시 정의는 `docker/toxiproxy/toxiproxy.json`에 두고 띄울 때 불러온다. 관리 API(8474)는 `127.0.0.1`에만 연다.

| 의존성 | 별칭:포트 | 대상 |
|---|---|---|
| post | `post-proxy:18081` | `post-service:8081` |
| user | `user-proxy:18082` | `user-service:8082` |
| like | `like-proxy:18083` | `like-service:8083` |
| recommendation | `recommendation-proxy:18084` | `recommendation-service:8084` |
| ad | `ad-proxy:18085` | `ad-service:8085` |

- 장애 종류: 응답 지연은 `latency` 톡식(응답 방향), 무응답은 `timeout` 톡식(`timeout: 0`, 연결을 받고 데이터를 흘리지 않으며 닫지도 않는다), 다운은 프록시 끄기(리스너가 닫혀 연결 거부)다. 모두 `POST /reset` 하나로 되돌린다.
- **다른 안:** 의존성마다 Toxiproxy 컨테이너를 따로 두면 자원 상한과 헬스 체크가 다섯 배가 된다. 별칭 없이 `toxiproxy:1808x`로 부르면 로그와 트레이스의 호스트가 모두 같아 의존성을 구분하기 어렵다. 하위 서비스 컨테이너를 멈춰 다운을 만들면 이름 풀이 실패와 연결 거부가 섞이고, 다시 띄우는 데 수십 초가 걸려 장애를 거두는 시각이 흔들린다.
- Toxiproxy를 거치면 지연이 1ms 안쪽으로 더해진다. 장애가 없을 때도 늘 거치므로 기준선과 이후 결과가 같은 조건이다.

### 2. 앱 수준 장애 주입: `libs/fault-injection`

지연 분포와 에러율은 HTTP를 이해해야 해서 Toxiproxy(TCP 수준)로는 못 만든다. Toxiproxy의 `jitter`는 고른 분포라 꼬리(p99)가 있는 평시 지연을 흉내 내지 못한다. 그래서 하위 서비스 안에서 넣는다.

- **모듈:** `libs/fault-injection`(`weave.spring-library`). 하위 서비스 5개의 `build.gradle.kts`에만 의존성을 더한다. 컨벤션 플러그인(`weave.spring-service`)에 넣지 않아 feed-service에는 들어가지 않는다.
- **코드 공유 규칙과의 관계:** "하위 서비스끼리 코드를 공유하지 않는다"는 업무 코드와 응답 계약을 공유하지 말라는 뜻으로 읽는다. 이 모듈은 `libs/observability`처럼 업무와 상관없는 실험 기반이고, 서비스 사이 계약(HTTP 응답 형식)에 끼어들지 않는다. 서비스마다 복사하면 다섯 벌이 따로 바뀐다. 이 해석은 이 change를 승인할 때 함께 확인한다.
- **켜는 법:** `weave.fault-injection.enabled`(기본 false)가 true일 때만 필터와 Actuator 엔드포인트 `faults`를 등록한다. 서비스마다 `application-experiment.yml`에 이 값과 노출 목록(`health,info,prometheus,faults`)을 둔다(아키텍처 11장: 실험 전용 엔드포인트는 `experiment` 프로필). 라이브러리가 프로필 파일을 싣지 않는 이유는, 같은 이름의 파일이 서비스에도 생기면 하나만 읽히기 때문이다.
- **필터:** 서블릿 필터로 `/actuator`와 `/actuator/`로 시작하는 경로를 건너뛰고, 나머지 요청마다 지연을 뽑아 요청 스레드에서 잠든 뒤(느린 서비스가 자기 스레드를 붙잡는 모습과 같다), 에러율 확률로 500(`{"error":"injected fault"}`)을 돌려주고 끝낸다. 에러여도 지연은 먼저 더한다. 순서는 `libs/observability`의 추적·요청 로그 필터 뒤라, 넣은 에러도 traceId가 붙은 요청 완료 로그에 남고 걸린 시간에 지연이 들어간다.
- **분포:** 로그 정규분포. 중앙값 m과 p99 q에서 μ = ln m, σ = ln(q/m) / 2.326으로 뽑는다. m = q면 고정 지연이다. 뽑는 부분은 `Random`을 받아 시드로 단위 테스트한다.
- **기본값:** 중앙값 20ms, p99 50ms. PRD는 p99(약 50ms)만 정했고, 중앙값은 대부분의 요청이 p99보다 훨씬 빠른 평시 모양을 만들려고 p99의 40%로 뒀다. 이때 p99.9는 약 67ms다. 실험 조건이지 장애 대응 설정값이 아니어서 측정 근거 대신 이 계산을 적는다.
- **엔드포인트:** `GET`(지금 값), `POST`(JSON으로 `latencyMedianMs`, `latencyP99Ms`, `errorRate` 중 준 것만 바꾼다), `DELETE`(기본값으로). 검증 실패는 400이고 값을 바꾸지 않는다. 값은 메모리에만 있어 다시 띄우면 기본값이다.
- **다른 안:** WireMock 같은 가짜 서버를 하위 서비스 앞에 두면 진짜 서비스의 스레드·로그·트레이스가 실험에서 빠진다.

### 3. ad 요청 한도

외부 API의 쿼터는 ad만의 동작이라 ad-service 안에 둔다(`libs/fault-injection`에 넣지 않는다).

- 고정 창: 한도를 켠 시각 t0부터 창 길이 W마다 새 창이다. 창 안의 허용 수를 세고, 넘으면 429와 `Retry-After = ceil(창 끝 - 지금)`(최소 1)을 준다. 거절한 요청은 세지 않는다. 시계는 `Clock` Bean으로 받아 테스트에서 바꾼다.
- `/actuator/quota`: `GET`, `POST`(`limit` ≥ 0, `windowSeconds` ≥ 1), `DELETE`(끔). `experiment` 프로필의 `application-experiment.yml`에서만 켜고 노출한다(`health,info,prometheus,faults,quota`).
- 한도 검사는 장애 주입 필터보다 먼저 한다. 쿼터를 넘은 외부 API는 빨리 429를 준다.
- 429와 `Retry-After`를 feed-service가 어떻게 다루는지는 F-005다. 지금은 다른 실패처럼 피드 전체가 500이 된다.

### 4. feed-service의 실험 지표

| 지표 | 방법 | Prometheus 이름(예) |
|---|---|---|
| Tomcat 사용 중 스레드 | `server.tomcat.mbeanregistry.enabled=true` | `tomcat_threads_busy_threads` |
| 공유 호출 스레드 풀 | Boot의 실행기 메트릭(`applicationTaskExecutor`) | `executor_active_threads`, `executor_queued_tasks` (`name="applicationTaskExecutor"`) |
| 의존성별 커넥션 풀 | Micrometer의 HttpClient 5 풀 바인더를 그룹마다 이름(`post` 등)으로 등록 | `httpcomponents_httpclient_pool_total_connections{state="leased"}`, `httpcomponents_httpclient_pool_total_pending` (`httpclient="post"`) |
| 의존성별 호출 지연 | `http.client.requests` 히스토그램. `client.name`을 호스트 대신 그룹 이름으로 | `http_client_requests_seconds_bucket{client_name="post"}` |
| 피드 요청 지연 | `http.server.requests` 히스토그램 | `http_server_requests_seconds_bucket{uri="/feed"}` |

- 히스토그램은 `management.metrics.distribution.percentiles-histogram.http.server.requests`와 `...http.client.requests`를 feed-service에서 켠다.
- `client.name`: 기본 관찰 규약은 URL의 호스트를 쓴다. 프록시 별칭이나 테스트의 `localhost`에 따라 이름이 바뀌지 않도록, 이미 있는 `RestClientHttpServiceGroupConfigurer`에서 그룹마다 관찰 규약을 넣어 그룹 이름을 쓴다.
- 커넥션 풀 지표 이름: Micrometer 1.17 바인더는 사용 중 연결을 `httpcomponents.httpclient.pool.total.connections`의 `state="leased"`로 내고(`state="available"`도 있다), 대기는 `...total.pending`, 한도는 `...total.max`, `...route.max.default`로 낸다. `..._total_leased`라는 이름은 없다. 리포트와 대시보드 질의도 이 이름을 쓴다.
- 커넥션 풀 매니저 얻기: 그룹마다 생기는 `PoolingHttpClientConnectionManager` 인스턴스가 있어야 바인더를 붙일 수 있는데, Boot 4.1.2는 넘겨주지 않는다. `HttpComponentsHttpClientBuilder.build()`가 매니저를 만들어 `HttpClientBuilder.setConnectionManager`로 넣고, 그 뒤의 커스터마이저나 httpclient5 5.6 빌더에 인스턴스를 꺼낼 공개 방법이 없다. 또 Boot의 `PropertiesRestClientHttpServiceGroupConfigurer`(순서 `HIGHEST_PRECEDENCE + 10`)는 그룹 이름을 모르는 `ClientHttpRequestFactoryBuilder.build(settings)`로 요청 팩토리를 만든다. 그래서 이렇게 한다.
  - Boot의 configurer 뒤에 도는 `RestClientHttpServiceGroupConfigurer`를 하나 둔다. 그룹마다 Boot와 같은 방법(`HttpServiceClientProperties`의 그룹 설정을 공개 클래스 `HttpClientSettingsPropertyMapper`로 `HttpClientSettings`로 바꾼다)으로 설정을 얻고, 지금의 `ClientHttpRequestFactoryBuilder` Bean으로 요청 팩토리를 다시 만들어 `builder.requestFactory`로 넣는다. 재시도 끔, 풀 크기, 타임아웃은 Bean과 Boot 설정 그대로다.
  - 만든 팩토리의 `HttpComponentsClientHttpRequestFactory.getHttpClient()`에서 httpclient5 내부 필드 `InternalHttpClient.connManager`를 리플렉션으로 읽어 바인더를 그룹 이름으로 묶는다. 매니저 생성을 따라 하지 않아 HTTP 클라이언트 설정이 한 곳(Boot와 Bean)에만 있게 하려는 선택이다.
  - 필드를 못 찾거나 `PoolingHttpClientConnectionManager`가 아니면 시작할 때 이유를 담은 예외로 멈춘다. 지표 없이 조용히 뜨지 않게 하고, httpclient5를 올릴 때 1.5 테스트와 함께 바로 드러나게 한다.
  - Boot의 configurer가 먼저 만든 그룹 팩토리는 쓰이지 않는다. 연결을 연 적이 없고 유휴 정리 스레드도 없어 남는 자원은 없다.
  - 재시도 끔, 풀 크기, 타임아웃 기본값이 지금과 같아야 하고, 이미 있는 `HttpClientPoolCapacityTest`, `MvpTimeoutDefaultsTest`, `HttpClientGroupTest`, `HttpClientRetryDefaultsTest`가 이를 지킨다.
- 실행기 메트릭이 Boot 4.1에서 `applicationTaskExecutor`에 자동으로 붙는지는 첫 작업의 테스트로 확인한다. 안 붙으면 `ExecutorServiceMetrics`로 직접 묶는다.

### 5. 실험 세트 구조: 데이터로 적은 시나리오와 k6 스크립트 하나

```
experiments/
├── run.sh                    # 시나리오 하나 (결정 6)
├── run-all.sh                # S-01~S-09 (결정 8)
├── config.json               # 기본 RPS, 준비·측정 시간, k6 요청 시간 초과, 사용자 ID 범위
├── slo.json                  # PRD 2장 SLO (가용성, p99). threshold는 여기서만 읽는다
├── expectations.json         # 단계별 기대 결과 (결정 8)
├── scenarios/S-01.json ...   # 시나리오마다 설명과 장애 시간표 (결정 9)
├── k6/feed.js                # 부하·장애 실행·판정 (아래)
├── k6/contract.js            # 응답에서 빠진 영역 찾기. MVP는 "부분 응답 없음"
├── lib/*.sh                  # 장애 되돌리기, 판정, 리포트
└── results/                  # 커밋하지 않는다
```

- **k6 스크립트 하나:** 시나리오 JSON을 `open()`으로 읽어 k6 시나리오 세 개를 만든다.
  - `warmup`: `constant-arrival-rate`로 준비 시간 동안 같은 RPS
  - `measure`: `startTime` = 준비 시간, 측정 시간 동안 같은 RPS. threshold는 이 시나리오의 태그로만 건다(`http_req_duration{scenario:measure}` p99, 가용성 `Rate`)
  - `faults`: 가상 사용자 1명, `startTime` = 준비 시간. 시간표대로 잠들었다가 Toxiproxy API와 하위 서비스 Actuator를 부른다. 이 요청에는 `kind: fault` 태그와 동작 이름을 붙여, 원본 결과에서 넣은 시각과 거둔 시각을 읽는다
  - `setup()`과 `teardown()`이 장애를 모두 되돌린다(결정 6의 되돌리기와 같은 내용)
- 부하 요청: `GET /feed`, `X-User-Id`는 1~1,000에서 고르게 뽑는다. `size`는 기본값(20)이다. like-service에는 사용자 ID 제한이 없다.
- **요청 시간 초과 5초와 가상 사용자 수:** 시간 초과는 S-02의 3초 지연을 끝까지 잴 수 있도록 3초보다 길게, 가상 사용자 수가 감당할 만하도록 짧게 정했다. 필요한 가상 사용자 수는 리틀의 법칙으로 RPS × 5초이고(200 RPS면 1,000), 여기에 10%를 더해 미리 만든다. 실행 중에 가상 사용자를 새로 만들면 그 비용이 결과에 섞이므로 처음부터 다 만든다. 모자라 건너뛴 요청(`dropped_iterations`)은 가용성의 실패로 센다(결정 7).
- **다른 안:** 장애 시간표를 셸에서 `sleep`으로 돌리면 부하와 시계가 따로라 장애 시각이 몇 초씩 흔들린다. 시나리오마다 k6 스크립트를 따로 쓰면 판정·리포트 코드가 아홉 벌이 된다.

### 6. 실행 명령 `experiments/run.sh`

```
experiments/run.sh <S-xx> [--rps N] [--warmup 1m] [--duration 5m]
```

1. 시나리오 ID와 옵션을 확인한다. 없는 ID면 목록을 출력하고 2로 끝난다(아무것도 바꾸지 않는다).
2. Compose 환경을 확인한다. feed-service, Toxiproxy, 하위 서비스 5개가 healthy가 아니면 띄우는 명령을 알려 주고 2로 끝난다.
3. `trap`을 건다(EXIT, INT, TERM). 어떻게 끝나도 장애 되돌리기(Toxiproxy `POST /reset`, 하위 서비스 5개 `DELETE /actuator/faults`, ad `DELETE /actuator/quota`)를 한다. 하위 서비스 Actuator는 호스트에 열려 있지 않으므로, 되돌리기는 k6 스크립트 `k6/reset.js`를 Compose의 `k6` 서비스로 돌려 네트워크 안에서 부른다. `feed.js`의 `setup()`·`teardown()`과 같은 함수를 쓴다. 다섯 곳 중 하나라도 2xx가 아니면 실패로 보고 2로 끝난다.
4. 장애를 되돌리고 feed-service를 다시 띄워 healthy가 될 때까지 기다린다. MVP에서는 무응답 장애 뒤 묶인 호출이 3분까지 남아 다음 실행에 섞이기 때문이다. 다시 띄운 뒤의 JIT 준비는 준비 구간이 맡는다.
5. 결과 폴더 `experiments/results/<UTC yyyyMMdd-HHmmss>-<S-xx>/`를 만들고 k6를 Compose의 `k6` 서비스로 돌린다(`docker compose run --rm`). 요약(`--summary-export`)과 요청마다의 원본(`--out json`, 끝나면 gzip)을 그 폴더에 쓴다.
6. k6 종료 코드가 0 또는 99(threshold 실패)면 판정과 리포트를 만든다(결정 7, 8). 그 밖의 코드거나 요약이 없으면 2로 끝난다.
7. 판정 코드(0 기대와 같음, 1 다름)로 끝난다.

- Compose 프로젝트 이름과 env 파일은 `COMPOSE_PROJECT_NAME`, `COMPOSE_ENV_FILE` 환경 변수로 받는다. 기본은 루트의 `compose.yaml`과 `.env`다. `:e2e:composeTest`는 `weave-compose-test`와 `e2e/build/compose-test.env`를 넘긴다(결정 13). `.env`는 스크립트가 읽지 않고 `docker compose --env-file`에 경로만 넘긴다.
- 스크립트는 macOS 기본 bash 3.2에서도 돌게 쓴다(연관 배열 등을 쓰지 않는다). 필요한 것은 bash, `jq`, `docker compose`, `gzip`이다.
- **다른 안:** Gradle 태스크로 만들면 Gradle 데몬 기동이 매번 더해지고, 장애 실험이 빌드와 묶인다. Kotlin 스크립트도 같은 이유로 쓰지 않는다.

### 7. 리포트 항목과 계산

리포트는 결과 폴더의 `report.md`다. 같은 값을 `report.json`에도 써서 `run-all.sh`와 테스트가 읽는다.

| 항목 | 계산 | 출처 |
|---|---|---|
| 시나리오, 커밋, 실행 시각, RPS, 준비·측정 시간, 기대 결과 | 실행 인자와 `git rev-parse --short HEAD`(작업 트리가 더러우면 `-dirty`) | run.sh |
| 측정 요청 수, 보내지 못한 요청 수 | `measure`의 `http_reqs`, `dropped_iterations` | k6 요약 |
| p50, p95, p99 | `measure`의 `http_req_duration`. 실패한 요청을 포함한다(PRD 2장: 503도 300ms 안) | k6 요약 |
| 가용성 | 200 수 ÷ (측정 요청 수 + 보내지 못한 요청 수) | k6 요약 |
| SLO 충족 | `measure` threshold가 모두 통과 | k6 요약 |
| degrade 비율(의존성별) | `contract.js`가 응답에서 빠진 영역을 센다. MVP 계약에는 빠진 영역 표시가 없어 "없음" | k6 요약 |
| 의존성별 호출 p99, 실패율 | `http_client_requests_seconds`의 측정 구간 `increase`로 `histogram_quantile(0.99)`, 실패(`outcome!="SUCCESS"`) 비율 | Prometheus |
| 의존성별 요청 배율 | 하위 서비스의 `http_server_requests_seconds_count`(`/actuator` 제외) 증가량 ÷ 피드 요청 수 | Prometheus |
| 서킷 상태 | `resilience4j_circuitbreaker_state` 시계열이 없으면 "없음". 있으면 측정 구간에 거친 상태 | Prometheus |
| 자원 사용량 | 결정 4의 Tomcat, 공유 풀(활성·대기열), 커넥션 풀(사용 중·대기, 의존성별)과 feed-service `process_cpu_usage`의 측정 구간 최댓값·평균 | Prometheus |
| 장애 시각 | `kind: fault` 요청의 시각과 동작 | k6 원본 |
| 10초 구간 표 | 측정 구간을 10초씩 나눠 구간마다 요청 수, p99, 가용성 | k6 원본 |
| 복구 시간 (S-08) | 장애를 거둔 시각 뒤의 10초 구간 중 SLO를 지키기 시작해 끝까지 이어진 첫 구간의 시작 - 거둔 시각. 없으면 "측정 구간 안에 복구하지 못함" | k6 원본 |
| Grafana 링크 | 대시보드 URL에 `from`, `to`를 측정 구간으로 | run.sh |

- Prometheus 질의는 측정 구간 `[시작, 끝]`을 `query`(구간 증가량)와 `query_range`(최댓값·평균, 5초 간격)로 한다.
- 시계: k6와 Prometheus는 같은 Docker 호스트의 시계를 쓴다.
- k6 원본에서 계산하는 부분(장애 시각, 10초 구간, 복구 시간)과 판정은 `lib/` 아래 스크립트로 나눠, 미리 만든 원본·요약 파일로 테스트한다(결정 13).

### 8. 기대 결과와 "장애 실험 세트 통과"

- `expectations.json`은 `{ "stage": "고도화 2", "scenarios": { "S-01": "meet", "S-02": "miss", ... } }`다. 고도화 2에서는 S-01만 `meet`이다.
- threshold는 언제나 `slo.json`(PRD 2장)이다. 단계마다 바뀌는 것은 기대 결과뿐이다. 판정이 실패해도 k6 threshold를 느슨하게 하지 않는다.
- `miss`인데 SLO를 지키면 기대와 다르다. 장애가 재현되지 않았다는 뜻이라(예: 프록시를 거치지 않는다) 놓치면 안 된다.
- `run-all.sh`는 S-01~S-09를 차례로 돌린다(약 9 × 7분 ≈ 1시간). [브랜치 규칙](../../../conventions/branch.md)의 "장애 실험 세트가 통과하는 상태"를 "`run-all.sh`가 0으로 끝난다"로 고쳐 적는다. 이후 change는 자기가 고친 시나리오의 기대를 `meet`으로 바꾼다.

### 9. 시나리오 정의

시나리오 JSON에는 설명, PRD 4장 문장, 장애 시간표(`at` 초, `action`, `target`, 값)가 있다. "내내"는 `at: 0`에 넣고 거두는 항목을 두지 않는다(측정 구간이 끝나면 `teardown()`이 거둔다).

| ID | 장애 | 고른 이유 |
|---|---|---|
| S-01 | 없음 | 평시. 실험 RPS와 잡음을 잰다 |
| S-02 | recommendation 프록시 `latency` 3,000ms | PRD 문장 그대로 |
| S-03 | recommendation 프록시 `timeout`(0) | PRD의 "무응답 또는 연결 끊김" 중 무응답을 고른다. 연결 끊김(`reset_peer`)은 바로 실패해 S-04와 비슷하고, 무응답이 자원을 가장 오래 묶는 경우다 |
| S-04 | user 프록시 끄기 | 다운 = 연결 거부(결정 1) |
| S-05 | like `errorRate` 0.5 | PRD 문장 그대로. 앱 수준 장애 |
| S-06 | post 프록시 끄기 | 다운 = 연결 거부 |
| S-07 | ad 한도: 창 10초, 한도 = RPS × 10 ÷ 2 | 부하의 절반이 429. 창을 1초로 하면 `Retry-After`가 늘 1이라, F-005에서 `Retry-After`를 지키는지 보기 어렵다. 10초면 창 뒤쪽 절반에서 1~5초가 나온다 |
| S-08 | S-03의 장애를 0초에 넣고 120초에 거둔다 | 2분은 MVP의 공유 풀·Tomcat 스레드가 묶이기에 충분하고, 남은 3분 동안 복구를 본다. S-02~S-06 중 자원을 가장 오래 묶는 무응답을 골랐다 |
| S-09 | user·like `errorRate` 1을 60·150·240초에 10초씩 | 짧은 장애 세 번이 측정 구간에 고르게 놓인다. 합쳐 30초(측정의 10%)다. Prometheus 5초 수집(결정 10)이면 장애 한 번에 두 번 이상 긁힌다 |

- 준비·측정 시간을 줄여 돌리면(스모크) 측정 구간 밖의 항목은 건너뛰고, 리포트의 장애 시각에 "건너뜀"으로 적는다.
- S-03의 연결 끊김 변형은 필요해지면 시나리오 JSON을 하나 더해 만든다. 지금은 두지 않는다.

### 10. Compose 변경

- **Toxiproxy:** 이미지는 버전으로 고정한다(구현 때 2주 이상 된 최신 안정판). 헬스 체크는 이미지에 있는 `toxiproxy-cli`로 한다. feed-service는 Toxiproxy가 healthy가 된 뒤 뜬다.
- **feed-service 주소:** 결정 1의 별칭으로 바꾼다.
- **하위 서비스 5개:** `SPRING_PROFILES_ACTIVE=experiment`. Compose 환경은 실험 환경이라 늘 켠다. 평시 지연(중앙값 20ms)이 `:e2e:composeTest`의 Loki·Tempo 조회 테스트에도 더해지지만 그 테스트는 지연을 보지 않는다. 하위 서비스 포트는 지금처럼 호스트에 열지 않는다.
- **k6:** `grafana/k6` 이미지(버전 고정, 아키텍처 1장의 2.3대)를 `profiles: [experiment]` 서비스로 둔다. `up`으로는 뜨지 않고 `run.sh`가 `docker compose run`으로만 부른다. `experiments/`를 마운트하고, 결과 파일이 호스트 사용자 소유가 되도록 `--user`를 넘긴다.
- **자원 상한(아키텍처 9-1):** feed-service CPU 2·메모리 1GB, 하위 서비스 각 CPU 1·512MB, MySQL·Toxiproxy 각 CPU 1, 관찰 인프라는 상한만, k6는 CPU 2·2GB. k6 상한은 1,100 가상 사용자(결정 5)가 부하 도구 쪽 병목이 되지 않을 크기로 잡고, S-01에서 보내지 못한 요청이 0인지로 확인한다(결정 12).
- **Tomcat 최대 스레드:** feed-service `server.tomcat.threads.max: 200`을 명시한다. Boot 기본값 그대로이고(아키텍처 4-2에서 확인), 기준선이 이 값에서 재졌다는 것을 설정에서 보이게 하려는 것이다. 설정값 커밋으로 따로 한다.
- **Prometheus 수집 간격 15초 → 5초:** 측정 5분에 표본이 20개에서 60개가 된다. S-09의 10초 장애가 15초 간격에서는 한 번도 긁히지 않을 수 있다. 설정값 커밋으로 따로 한다.

### 11. 대시보드

- `docker/grafana/provisioning/dashboards/`에 공급자 설정을, `docker/grafana/dashboards/feed-experiments.json`에 대시보드(uid `weave-feed`)를 둔다. 지금은 데이터 소스 폴더만 마운트하므로 두 폴더를 더 마운트한다.
- 패널: 피드 RPS·가용성·p50/p95/p99(`http_server_requests`), 의존성별 호출 p99·실패율·RPS, Tomcat 사용 중 스레드, 공유 풀 활성·대기열, 의존성별 커넥션 풀 사용 중·대기, feed-service CPU. 서킷·벌크헤드·degrade 패널은 F-001, F-002에서 그 지표와 함께 더한다(아키텍처 7-4를 그렇게 고친다).
- 실험 리포트는 이 대시보드 링크에 측정 구간을 붙여 그래프를 대신한다.

### 12. 실험 RPS와 기준선 기록

- **실험 RPS:** S-01을 100, 200, 300, 400 RPS로 돌려(준비 1분, 측정 5분), 아래를 모두 채우는 가장 높은 값을 고른다. 고른 값과 표를 `docs/experiments/baseline-mvp.md`에 적고 `config.json`에 넣는다(설정값 커밋).
  - S-01이 SLO를 지킨다
  - 보내지 못한 요청이 0이다(부하 도구가 병목이 아니다)
  - feed-service `process_cpu_usage` 최댓값이 0.7 미만이다. 장애 시나리오에서 스레드가 묶이기 전에 CPU가 먼저 차면 원인이 섞인다
- **잡음:** 고른 RPS로 S-01을 세 번 돌려 p99와 가용성의 차이를 적는다. 이후 비교에서 이 차이 안의 변화는 개선이나 악화로 보지 않는다.
- **기준선:** S-01~S-09를 한 번씩 돌려 시나리오마다 리포트 요약(판정, p50·p95·p99, 가용성, 자원 최댓값, S-08 복구 시간)을 `docs/experiments/baseline-mvp.md`에 표로 적는다. 실행 환경(호스트 CPU·메모리, Docker에 준 CPU·메모리, 커밋)도 적는다.
- **아키텍처 4-3:** S-01 리포트의 의존성별 호출 p99를 "기준선 평시 p99" 열로 더한다. 예산 값은 F-001에서 이 열을 근거로 정한다.

### 13. 테스트 배치

| 대상 | 어디에 | 방식 |
|---|---|---|
| 지연 분포 뽑기, 에러 판정, 설정 검증 | `libs/fault-injection` | 단위. 시드를 준 `Random`으로 10,000번 뽑아 중앙값·p99가 ±5% 안 |
| `fault-injection` 스펙 시나리오 | `libs/fault-injection` | `Integration`. 테스트 소스의 작은 웹 애플리케이션(`libs/observability`의 testapp과 같은 방식) |
| 서비스 5개에서 실험 프로필로 엔드포인트가 열린다, feed에는 없다 | 서비스마다 | `Integration`. `experiment` 프로필로 `GET /actuator/faults`. like-service는 기존 MySQL Testcontainers 설정을 쓴다 |
| `ad-serving` 요청 한도 | ad-service | `Integration`. `Clock`을 테스트용으로 바꿔 창을 넘긴다. "다음 창"만 실제 시계로 최대 2초 기다린다 |
| feed 실험 지표 | feed-service | `Integration`. 기존 `DownstreamWireMock`으로 `GET /feed` 뒤 `/actuator/prometheus` 본문을 본다 |
| 시나리오 정의(S-01~S-09 표), 기본 목록, 판정 코드, 복구 시간, 리포트 계산 | `e2e` | `Integration`. 시나리오 JSON을 읽거나, `lib/` 스크립트를 미리 만든 k6 요약·원본 파일(`e2e/src/test/resources/experiments/`)로 돌린다. Docker는 필요 없고 `bash`와 `jq`만 쓴다. 모듈 밖의 파일을 읽고 프로세스를 띄우는 테스트라 Stop 훅의 `test`에서 빼고, 시나리오를 테스트로 먼저 옮겨도 `test`가 통과하게 한다 |
| 프록시 경로, `run.sh`·`run-all.sh` 스모크, 같은 상태에서 시작, 장애 되돌리기(정상, SIGTERM), 환경 없음, 대시보드 | `e2e` | `Compose` 태그. `:e2e:composeTest`가 띄운 환경에서 `run.sh`를 프로세스로 돌린다. 짧은 옵션(`--rps 5 --warmup 5s --duration 20s`)을 쓴다 |

- "환경이 떠 있지 않다"는 Compose 테스트 안에서 없는 프로젝트 이름(`COMPOSE_PROJECT_NAME`)을 줘서 확인한다.
- 테스트 이름에 `F-007`을 넣는다.
- `:e2e:composeTest`에 스모크가 더해져 몇 분 길어진다. k6 이미지도 CI에서 받는다.

### 14. 문서

- PRD: 4장 S-10·S-11 문장 옆에 실험 스크립트는 F-003에서 만든다고 적고, 6장 F-007 수용 기준의 "4장의 시나리오마다"를 "S-01~S-09마다"로, F-003에 S-10·S-11 스크립트 항목을 더한다. 이 change의 첫 커밋으로 한다(proposal).
- 아키텍처: 3장(`experiments/` 설명 S-01~S-09, `docker-compose.yml` → `compose.yaml`), 4-3(기준선 평시 p99 열), 7-4(패널을 단계마다 더한다), 9장(정한 구성과 명령, 프록시 표, 리포트 항목), 11장(`application-experiment.yml`), 13장(실험 RPS를 정했으면 지운다)
- 하네스 규칙 5장과 `CLAUDE.md` 명령 표: `experiments/run.sh`, `experiments/run-all.sh`
- 브랜치 규칙: "장애 실험 세트 통과"의 뜻(결정 8)
- 영향받는 장애 시나리오: 이 change는 장애 대응 동작을 바꾸지 않는다. Tomcat 최대 스레드와 Prometheus 수집 간격은 모든 시나리오의 측정 조건이라 기준선 문서에 함께 적는다.

## Risks / Trade-offs

- [노트북 상태(다른 프로그램, 발열)에 따라 결과가 흔들린다] → S-01 세 번으로 잡음 폭을 재서 기록하고(결정 12), 리포트에 실행 환경과 커밋을 남긴다. 비교는 같은 기계에서 한다.
- [Docker에 준 CPU가 Compose 상한의 합(약 13)보다 작으면 컨테이너끼리 CPU를 다툰다] → 상한은 예약이 아니라 상한이다. 실험 RPS를 이 환경에서 S-01로 고르므로(결정 12), 평시에는 다툼이 결과에 드러나지 않는 RPS가 된다. 기준선 문서에 Docker의 CPU 수를 적는다.
- [k6와 서비스가 같은 호스트라 부하 도구가 결과에 끼어든다] → k6에 CPU 상한을 두고, 보내지 못한 요청과 k6의 `http_req_blocked`를 리포트에서 본다.
- [MVP의 무응답 장애 뒤 묶인 호출이 3분까지 남는다] → 실행마다 feed-service를 다시 띄운다(결정 6). 하위 서비스는 다시 띄우지 않는다(장애 설정은 API로 되돌린다).
- [`trap`이 돌지 못하는 종료(SIGKILL, 호스트 재부팅)] → 다음 `run.sh`가 시작할 때 먼저 되돌린다(같은 상태에서 시작). `docker compose down`으로도 사라진다(모두 메모리 상태다).
- [`/actuator/faults`, `/actuator/quota`에 인증이 없다] → `experiment` 프로필에서만 열리고, Compose는 하위 서비스 포트를 호스트에 열지 않는다. Toxiproxy API는 `127.0.0.1`에만 연다.
- [k6 원본 출력이 크다(200 RPS × 6분 ≈ 7만 줄, 수십 MB)] → 끝나면 gzip하고, `results/`는 커밋하지 않는다.
- [CI(Linux)에서 k6 컨테이너가 쓴 결과 파일의 소유자가 달라 지우거나 읽지 못한다] → `--user`로 호스트 사용자 ID를 넘긴다.
- [커넥션 풀 바인더를 붙이려고 요청 팩토리를 직접 만들면 MVP의 HTTP 클라이언트 구성이 바뀔 수 있다] → 기존 기본값 테스트(결정 4)가 막는다. 그래도 바뀌면 멈추고 design을 고친다.
- [`client.name`을 그룹 이름으로 바꾸면 기존 대시보드나 질의가 깨진다] → 지금 `http.client.requests`를 쓰는 곳이 없다.

## Migration Plan

- 배포 대상은 로컬 Compose뿐이다. `docker compose down -v` 뒤 `./gradlew bootJar`와 `docker compose up -d --build --wait`로 새 구성을 띄운다.
- 되돌리려면 feed-service 주소를 하위 서비스로 다시 바꾸고 Toxiproxy를 지운다. 장애 주입은 `experiment` 프로필을 빼면 꺼진다.

## Open Questions

- Toxiproxy와 k6 이미지의 정확한 버전은 구현할 때 고른다(결정 10).
