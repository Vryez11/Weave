## MODIFIED Requirements

### Requirement: 같은 상태에서 시작한다 (F-007)

`experiments/run.sh`는 부하를 보내기 전에 feed-service를 다시 띄워 헬스 체크가 통과할 때까지 기다리고, 모든 프록시의 장애, 하위 서비스 5개의 장애 설정, ad 요청 한도를 기본값으로 되돌리고, post 캐시를 비워야 한다(SHALL). 앞 실행이 남긴 스레드나 장애, 캐시가 결과에 섞이지 않게 한다. post 캐시는 그 실행의 준비 구간이 다시 채운다.

#### Scenario: 앞에 남은 장애

- **GIVEN** post 프록시에 지연 1,000ms를, like-service에 에러율 1을 손으로 넣어 두었다
- **WHEN** `experiments/run.sh S-01 --rps 5 --warmup 5s --duration 20s`를 돌린다
- **THEN** 리포트의 가용성이 1이다
- **AND** feed-service 컨테이너의 시작 시각이 명령을 돌리기 전보다 늦다

#### Scenario: 앞에 남은 post 캐시

- **GIVEN** 실험 부하가 쓰지 않는 개수(`size=7`)의 post 캐시를 손으로 넣어 두었다
- **WHEN** `experiments/run.sh S-01 --rps 5 --warmup 5s --duration 20s`를 돌린다
- **THEN** 명령이 끝난 뒤 `size=7`의 post 캐시가 없다

### Requirement: 가용성 계산 (F-007)

가용성은 측정 구간에 보내야 했던 요청 중 200을 받은 비율이어야 한다(SHALL). 200이 아닌 응답, 연결 실패, 부하 도구의 요청 시간 초과는 실패이고, 부하 도구가 보내지 못한 요청(가상 사용자가 모자라 건너뛴 요청)도 분모에 넣어 실패로 센다. 지연 백분위는 실패한 요청을 포함해 계산한다.

지연은 부하 도구가 요청을 보낸 때부터 응답을 받거나 요청 시간 초과로 끝날 때까지 잰 경과 시간이어야 한다(SHALL). 시간 초과된 요청의 지연은 요청 시간 초과보다 짧게 적지 않는다.

#### Scenario: 모든 요청이 실패한다

- **GIVEN** Compose 환경의 post 캐시 저장소(Redis)를 멈췄다. post가 꺼지면 대체할 캐시를 읽지 못해 피드가 503이다
- **WHEN** `experiments/run.sh S-06 --rps 5 --warmup 5s --duration 20s`를 돌린다
- **THEN** 리포트의 가용성이 0이다
- **AND** 리포트에 지연 p50·p95·p99가 있다
- **AND** 명령이 끝난 뒤 post 캐시 저장소를 다시 띄운다

#### Scenario: 보내지 못한 요청도 판정에 들어간다

- **GIVEN** S-01의 k6 요약에서 측정 구간에 보낸 요청 1,996개가 모두 200이고 보내지 못한 요청이 4개다
- **WHEN** 그 요약으로 판정한다
- **THEN** 가용성은 0.998이라 판정은 "SLO 미충족, 기대와 다름"이다
- **AND** 넘은 기준에 보내지 못한 요청을 넣은 가용성이 있다

#### Scenario: 시간 초과된 요청

- **GIVEN** `experiments/run.sh S-01 --rps 20 --warmup 5s --duration 30s`의 측정 구간이 시작되고 5초 뒤 feed-service 컨테이너를 10초 동안 멈춘다(`docker compose pause`, 멈춘 동안 보낸 요청은 응답을 받지 못한다)
- **WHEN** 명령이 끝난다
- **THEN** 리포트의 지연 p99는 4,500ms 이상이다

### Requirement: 리포트 (F-007)

실행마다 `experiments/results/` 아래 새 폴더에 사람이 읽는 리포트와 원본 결과를 남겨야 한다(SHALL). 리포트 항목은 아키텍처 9-3의 표를 따르고, 값이 없는 항목은 빈칸이 아니라 "없음"으로 적는다. degrade 비율은 "degrade 비율" 요구사항대로 적는다. 서킷 상태는 의존성마다 측정 구간에 거친 상태를 순서대로 적는다. S-08은 장애를 거둔 뒤 SLO를 다시 지키기까지 걸린 시간을 적는다.

리포트는 측정 구간에 Prometheus가 feed-service 지표를 긁은 비율을 적고, 1보다 작으면 자원 사용량이 긁힌 구간만의 값이라고 함께 적는다.

#### Scenario: 리포트 항목

- **WHEN** `experiments/run.sh S-02 --rps 5 --warmup 5s --duration 20s`가 끝난다
- **THEN** 리포트에 시나리오 ID, 커밋, 실행 시각, RPS·준비·측정 시간, 측정 요청 수, 보내지 못한 요청 수, p50·p95·p99, 가용성, 기준(가용성, p99, 기대에 적었으면 degrade 비율)마다의 충족 여부와 기대 결과가 있다
- **AND** 의존성 5개마다 호출 p99, 호출 실패율, 요청 배율(하위 서비스가 받은 요청 수 ÷ 피드 요청 수)이 있다
- **AND** Tomcat 사용 중 스레드, 의존성 5개마다 벌크헤드의 사용 중 스레드와 대기열, 의존성별 커넥션 풀의 사용 중·대기 수, feed-service CPU 사용률의 최댓값과 평균이 있다
- **AND** 의존성 5개마다 측정 구간에 거친 서킷 상태와 degrade 비율이 있고, 영역이 하나라도 빠지거나 stale인 응답의 비율이 있다
- **AND** 측정 구간으로 시간 범위를 맞춘 Grafana 대시보드 링크가 있다
- **AND** 넣은 장애마다 실제로 넣은 시각과 거둔 시각이 있다
- **AND** feed-service 지표 수집 비율(0 이상 1 이하)이 있다

#### Scenario: 서킷이 열린 시나리오

- **GIVEN** 측정 구간에 recommendation 서킷이 닫힘에서 열림, 반열림을 거쳐 다시 열렸다
- **WHEN** 그 실행의 리포트를 만든다
- **THEN** recommendation의 서킷 상태는 `CLOSED → OPEN → HALF_OPEN → OPEN`이고, 상태가 바뀌지 않은 의존성은 `CLOSED`다

#### Scenario: 복구 시간

- **GIVEN** S-08의 요청 결과에서 장애를 120초에 거뒀고, 측정 구간을 10초씩 나눈 구간 중 140초 구간부터 끝까지 p99와 가용성이 SLO를 지킨다(130초 구간은 지키지 못한다)
- **WHEN** 그 결과로 리포트를 만든다
- **THEN** 리포트에 장애를 거둔 시각, 복구된 첫 구간의 시작 시각, 복구 시간 20초가 있다

#### Scenario: 복구하지 못함

- **GIVEN** S-08의 요청 결과에서 장애를 거둔 뒤 마지막 10초 구간이 SLO를 지키지 못한다
- **WHEN** 그 결과로 리포트를 만든다
- **THEN** 복구 시간은 "측정 구간 안에 복구하지 못함"이다

### Requirement: 실험 대시보드 (F-007)

Compose 환경의 Grafana는 띄울 때 파일로 둔 피드 실험 대시보드를 불러와야 한다(SHALL). 대시보드에는 피드 가용성과 p50·p95·p99, 의존성별 호출 지연·실패율, 자원 사용량(리포트의 자원 항목), 의존성별 서킷 상태와 벌크헤드 사용량·거절, 의존성별 degrade 비율과 대체(post 캐시 읽기 결과, user 캐시로 채운 수, 503 수)가 있다.

#### Scenario: 대시보드를 불러온다

- **GIVEN** Compose 환경이 떠 있고 `experiments/run.sh S-01 --rps 5 --warmup 5s --duration 20s`를 돌렸다
- **WHEN** Grafana에서 피드 실험 대시보드를 찾는다
- **THEN** 대시보드가 있다
- **AND** 대시보드 패널의 Prometheus 질의는 모두 오류 없이 하나 이상의 시계열을 돌려준다
- **AND** 서킷 상태와 벌크헤드 사용량 패널이 있다
- **AND** 의존성별 degrade 비율 패널이 있다

## ADDED Requirements

### Requirement: 단계별 기대 결과로 판정한다 (F-007)

실험 세트는 단계마다 시나리오별로 SLO의 기준(가용성, p99)을 지켜야 하는지(기대 결과)를 한 파일에 적고, `experiments/run.sh`는 기준마다의 실제 결과가 모두 기대와 같으면 0, 하나라도 다르면 1, 실행하지 못했으면 2로 끝나야 한다(SHALL). 기대는 시나리오마다 두 기준을 함께(`meet`, `miss`) 적거나 기준마다 따로 적는다. 지키지 못하는 것이 기대인 기준을 지켜도 기대와 다른 것이다. 지금 단계(고도화 4)의 기대는 S-01~S-09 모두 두 기준을 지키는 것이고, S-01은 degrade 비율 기준도 지킨다.

#### Scenario: 기대대로 SLO를 못 지킨다

- **GIVEN** 기대 결과에서 S-02는 두 기준 모두 지키지 못한다(`miss`)
- **WHEN** 가용성과 p99 기준을 모두 넘은 S-02의 부하 결과로 판정한다
- **THEN** 판정은 "SLO 미충족, 기대와 같음"이고 판정 코드는 0이다

#### Scenario: 기대와 다르다

- **GIVEN** 기대 결과에서 S-01은 SLO를 지킨다
- **WHEN** p99가 SLO를 넘은 S-01의 부하 결과로 판정한다
- **THEN** 판정은 "SLO 미충족, 기대와 다름"이고 판정 코드는 1이다

#### Scenario: 기준별 기대와 같다

- **GIVEN** 기대 결과에서 S-02는 가용성 `miss`, p99 `meet`이다
- **WHEN** p99는 지키고 가용성은 넘은 S-02의 부하 결과로 판정한다
- **THEN** 판정은 "SLO 미충족, 기대와 같음"이고 판정 코드는 0이다
- **AND** 판정 결과에 기준마다의 충족 여부와 기대가 있다

#### Scenario: 기준별 기대와 다르다

- **GIVEN** 기대 결과에서 S-02는 가용성 `miss`, p99 `meet`이다
- **WHEN** 가용성과 p99를 모두 넘은 S-02의 부하 결과로 판정한다
- **THEN** 판정은 "SLO 미충족, 기대와 다름"이고 판정 코드는 1이다
- **AND** 기대와 다른 기준으로 p99가 있다

#### Scenario: 지금 단계의 기대

- **WHEN** 실험 세트의 기대 결과를 읽는다
- **THEN** 단계는 고도화 4이다
- **AND** S-01은 가용성, p99, degrade 비율 모두 `meet`이고, S-02~S-09는 가용성과 p99 모두 `meet`이다

#### Scenario: 부하 도구가 실패한다

- **WHEN** 부하 도구가 결과를 남기지 못하고 끝난다(예: 스크립트 오류)
- **THEN** `experiments/run.sh`는 2로 끝나고, 장애를 거둔 상태다


### Requirement: degrade 비율 (F-002)

부하 도구는 측정 구간의 200 피드 응답마다 `degradation`을 읽어 의존성별로 영역이 빠지거나(`missing`) stale인(`stale`) 응답을 세고, 리포트는 의존성 5개마다 degrade 비율(그 수 ÷ 측정 구간의 200 응답 수)과 영역이 하나라도 빠지거나 stale인 응답의 비율을 적어야 한다(SHALL). 200 응답이 없으면 "없음"이다.

#### Scenario: 비율을 계산한다

- **GIVEN** 측정 구간의 200 응답 200개 중 `missing`에 `recommendation`이 있는 것이 50개, `stale`에 `post`가 있는 것이 20개이고 그 둘이 겹치는 응답이 10개다
- **WHEN** 그 결과로 리포트를 만든다
- **THEN** recommendation의 degrade 비율은 0.25, post는 0.1, user·like·ad는 0이다
- **AND** 하나라도 빠지거나 stale인 응답의 비율은 0.3이다

#### Scenario: 선택 의존성이 느리다

- **WHEN** `experiments/run.sh S-02 --rps 5 --warmup 5s --duration 20s`가 끝난다(recommendation이 3초 늦어 모든 응답에서 추천이 빠진다)
- **THEN** 리포트의 가용성은 1이고 recommendation의 degrade 비율은 1이다
- **AND** post, user, like, ad의 degrade 비율은 0이다

### Requirement: degrade 비율 기준 (F-002)

SLO 판정 설정에 평시 degrade 비율 상한(PRD 2장, 1% 미만)을 두고, 기대 결과에 degrade 비율 기준을 적은 시나리오는 의존성 5개의 degrade 비율이 모두 상한보다 작을 때 그 기준을 지킨 것으로 판정해야 한다(SHALL). 기대에 적지 않은 시나리오는 degrade 비율로 판정하지 않는다. 장애 시나리오에서는 실패한 의존성만큼 오르는 것이 정상이기 때문이다.

#### Scenario: 평시에 영역이 빠진다

- **GIVEN** 기대 결과에서 S-01은 가용성, p99, degrade 비율 모두 `meet`이다
- **WHEN** 가용성과 p99는 지키고 like의 degrade 비율이 0.02인 S-01의 결과로 판정한다
- **THEN** 판정은 "SLO 미충족, 기대와 다름"이고 판정 코드는 1이다
- **AND** 기대와 다른 기준으로 degrade 비율이 있다

#### Scenario: 장애 시나리오의 degrade 비율은 판정하지 않는다

- **GIVEN** 기대 결과에서 S-02는 가용성과 p99 모두 `meet`이고 degrade 비율 기준은 적지 않았다
- **WHEN** 가용성과 p99는 지키고 recommendation의 degrade 비율이 1인 S-02의 결과로 판정한다
- **THEN** 판정은 "SLO 충족, 기대와 같음"이고 판정 코드는 0이다

## REMOVED Requirements

### Requirement: 기대 결과로 판정한다 (F-007)

**Reason:** 시나리오 이름에 단계("고도화 3의 기대")가 들어 있어 단계가 바뀔 때마다 시나리오를 지울 수 없다(MODIFIED는 시나리오를 빼지 못한다). 단계 이름이 들어가지 않는 시나리오("지금 단계의 기대")로 다시 쓴다.

**Migration:** "단계별 기대 결과로 판정한다 (F-007)"가 같은 동작을 정한다. 앞의 다섯 시나리오는 그대로 옮겼고, "고도화 3의 기대"는 "지금 단계의 기대"(고도화 4)로 바뀌었다. 다음 단계부터는 이 요구사항을 MODIFIED로 고친다.
