# Spec Delta

## Purpose

PRD 4장의 장애 시나리오를 명령 하나로 똑같이 재현하고, 그 결과를 같은 항목의 리포트로 남겨 단계마다 숫자로 비교할 수 있게 한다. 무엇을 왜 하는지와 판정 기준(SLO)은 [PRD](../../../docs/prd.md) 2장, 4장, F-007을 따른다.

## ADDED Requirements

### Requirement: 하위 서비스 호출은 프록시를 거친다 (F-007)

Compose 환경의 feed-service는 하위 서비스 5개를 의존성마다 하나씩 있는 네트워크 프록시를 거쳐 불러야 한다(SHALL). 장애가 없을 때도 같은 경로를 쓴다. 한 의존성의 프록시에 넣은 장애는 그 의존성의 호출에만 영향을 준다.

#### Scenario: 프록시에 지연을 넣는다

- **GIVEN** Compose 환경이 떠 있고 장애가 없다
- **WHEN** recommendation 프록시에 응답 지연 3,000ms를 넣고 `GET /feed`를 부른다
- **THEN** 응답이 3,000ms 이상 걸린다
- **AND** 지연을 거둔 뒤 `GET /feed`는 1,000ms 안에 200으로 온다

#### Scenario: 한 의존성의 프록시를 끊는다

- **WHEN** user 프록시를 끄고 `GET /feed`를 부른다
- **THEN** feed-service의 하위 서비스 호출 실패 로그에서 `dependency`가 `user`인 줄만 있다
- **AND** 프록시를 다시 켜면 `GET /feed`가 200이다

### Requirement: 시나리오의 장애 정의 (F-007)

실험 세트는 S-01~S-09마다 장애 정의를 두고, PRD 4장의 "넣는 장애"를 측정 구간 시작부터 잰 시각에 넣고 거둬야 한다(SHALL). "내내"인 장애는 측정 구간이 끝날 때 거둔다. S-10, S-11은 이 실험 세트에 두지 않는다(F-003).

#### Scenario: 시나리오마다의 장애

- **WHEN** 실험 세트의 장애 정의를 읽는다
- **THEN** 시나리오마다 아래 장애가 정의돼 있다
  - S-01: 없음
  - S-02: recommendation 프록시 응답 지연 3,000ms, 내내
  - S-03: recommendation 프록시 무응답(연결은 받지만 응답을 보내지 않는다), 내내
  - S-04: user 프록시 꺼짐(연결 거부), 내내
  - S-05: like 에러율 0.5(앱 수준), 내내
  - S-06: post 프록시 꺼짐(연결 거부), 내내
  - S-07: ad 요청 한도 켬. 창 10초, 한도는 실험 RPS × 10의 절반, 내내
  - S-08: S-03과 같은 장애를 0초에 넣고 120초에 거둔다
  - S-09: user와 like 에러율 1을 60초, 150초, 240초에 넣고 각각 10초 뒤에 거둔다

### Requirement: 명령 하나로 시나리오 하나를 돌린다 (F-007)

`experiments/run.sh <시나리오 ID>`는 장애 넣기, 부하, 장애 거두기, 리포트 쓰기를 한 번에 해야 한다(SHALL). 부하는 `GET /feed`를 일정한 RPS로 보내며, 준비 구간 뒤에 측정 구간이 온다. RPS, 준비 시간, 측정 시간은 옵션으로 바꿀 수 있고 기본값은 실험 세트 설정 한 곳에 둔다.

#### Scenario: 짧게 돌린다

- **GIVEN** Compose 환경이 떠 있다
- **WHEN** `experiments/run.sh S-02 --rps 5 --warmup 5s --duration 20s`를 돌린다
- **THEN** 명령이 끝나고 `experiments/results/` 아래에 이번 실행의 리포트가 생긴다
- **AND** 리포트의 측정 요청 수는 95 이상 105 이하다(5 RPS × 20초)
- **AND** 리포트의 p50이 3,000ms 이상이다

#### Scenario: 없는 시나리오

- **WHEN** `experiments/run.sh S-99`를 돌린다
- **THEN** 0이 아닌 코드로 끝나고, 있는 시나리오 ID 목록을 출력한다
- **AND** 프록시와 하위 서비스의 장애 설정은 부르기 전과 같다

#### Scenario: 환경이 떠 있지 않다

- **WHEN** Compose 환경이 없을 때 `experiments/run.sh S-01`을 돌린다
- **THEN** 부하를 보내지 않고 0이 아닌 코드로 끝나며, 환경을 띄우는 명령을 알려 준다

### Requirement: 같은 상태에서 시작한다 (F-007)

`experiments/run.sh`는 부하를 보내기 전에 feed-service를 다시 띄워 헬스 체크가 통과할 때까지 기다리고, 모든 프록시의 장애, 하위 서비스 5개의 장애 설정, ad 요청 한도를 기본값으로 되돌려야 한다(SHALL). 앞 실행이 남긴 스레드나 장애가 결과에 섞이지 않게 한다.

#### Scenario: 앞에 남은 장애

- **GIVEN** post 프록시에 지연 1,000ms를, like-service에 에러율 1을 손으로 넣어 두었다
- **WHEN** `experiments/run.sh S-01 --rps 5 --warmup 5s --duration 20s`를 돌린다
- **THEN** 리포트의 가용성이 1이다
- **AND** feed-service 컨테이너의 시작 시각이 명령을 돌리기 전보다 늦다

### Requirement: 장애를 반드시 거둔다 (F-007)

`experiments/run.sh`가 어떻게 끝나든(정상 종료, 판정 실패, 부하 도구 오류, SIGINT·SIGTERM으로 중단) 끝날 때 모든 프록시가 켜져 있고 장애가 없으며, 하위 서비스 5개의 장애 설정과 ad 요청 한도가 기본값이어야 한다(SHALL).

#### Scenario: 정상 종료 뒤

- **WHEN** `experiments/run.sh S-02 --rps 5 --warmup 5s --duration 20s`가 끝난다
- **THEN** 프록시 5개가 모두 켜져 있고 어느 프록시에도 장애가 없다
- **AND** 하위 서비스 5개의 `GET /actuator/faults`가 기본값이고, ad 요청 한도가 꺼져 있다

#### Scenario: 중간에 멈춘다

- **WHEN** `experiments/run.sh S-05 --rps 5 --warmup 5s --duration 60s`를 돌리고 측정 구간이 시작된 뒤 SIGTERM을 보낸다
- **THEN** 명령이 끝난 뒤 프록시와 하위 서비스 5개, ad 요청 한도가 위 시나리오와 같은 상태다

### Requirement: 측정 구간만 판정한다 (F-007)

판정과 리포트의 지연·가용성은 측정 구간에 보낸 요청만으로 계산해야 한다(SHALL). 판정 기준은 PRD 2장의 피드 SLO(가용성, p99)이고 k6 threshold로 적는다. 시나리오마다 판정 기준을 느슨하게 바꾸지 않는다.

#### Scenario: 준비 구간은 세지 않는다

- **WHEN** `experiments/run.sh S-01 --rps 5 --warmup 10s --duration 20s`를 돌린다
- **THEN** 리포트의 측정 요청 수는 95 이상 105 이하다(준비 구간의 약 50개는 빠진다)

### Requirement: 가용성 계산 (F-007)

가용성은 측정 구간에 보내야 했던 요청 중 200을 받은 비율이어야 한다(SHALL). 200이 아닌 응답, 연결 실패, 부하 도구의 요청 시간 초과는 실패이고, 부하 도구가 보내지 못한 요청(가상 사용자가 모자라 건너뛴 요청)도 분모에 넣어 실패로 센다. 지연 백분위는 실패한 요청을 포함해 계산한다.

지연은 부하 도구가 요청을 보낸 때부터 응답을 받거나 요청 시간 초과로 끝날 때까지 잰 경과 시간이어야 한다(SHALL). 시간 초과된 요청의 지연은 요청 시간 초과보다 짧게 적지 않는다.

#### Scenario: 모든 요청이 실패한다

- **WHEN** `experiments/run.sh S-06 --rps 5 --warmup 5s --duration 20s`를 돌린다(MVP에서 post가 꺼지면 피드가 500이다)
- **THEN** 리포트의 가용성이 0이다
- **AND** 리포트에 지연 p50·p95·p99가 있다

#### Scenario: 시간 초과된 요청

- **WHEN** `experiments/run.sh S-03 --rps 60 --warmup 5s --duration 20s`를 돌린다(MVP에서 recommendation이 응답하지 않으면 피드 요청이 요청 시간 초과 5초까지 끝나지 않는다)
- **THEN** 리포트의 지연 p50은 4,500ms 이상이다

### Requirement: 리포트 (F-007)

실행마다 `experiments/results/` 아래 새 폴더에 사람이 읽는 리포트와 원본 결과를 남겨야 한다(SHALL). 리포트 항목은 design 결정 7의 표를 따르고, 지금 단계에 없는 값(degrade 비율, 서킷 상태)은 빈칸이 아니라 "없음"으로 적는다. S-08은 장애를 거둔 뒤 SLO를 다시 지키기까지 걸린 시간을 적는다.

리포트는 측정 구간에 Prometheus가 feed-service 지표를 긁은 비율을 적고, 1보다 작으면 자원 사용량이 긁힌 구간만의 값이라고 함께 적는다.

#### Scenario: 리포트 항목

- **WHEN** `experiments/run.sh S-02 --rps 5 --warmup 5s --duration 20s`가 끝난다
- **THEN** 리포트에 시나리오 ID, 커밋, 실행 시각, RPS·준비·측정 시간, 측정 요청 수, 보내지 못한 요청 수, p50·p95·p99, 가용성, SLO 충족 여부, 기대 결과가 있다
- **AND** 의존성 5개마다 호출 p99, 호출 실패율, 요청 배율(하위 서비스가 받은 요청 수 ÷ 피드 요청 수)이 있다
- **AND** Tomcat 사용 중 스레드, 공유 호출 스레드 풀의 활성 스레드와 대기열, 의존성별 커넥션 풀의 사용 중·대기 수, feed-service CPU 사용률의 최댓값과 평균이 있다
- **AND** degrade 비율과 서킷 상태는 "없음"이고, 측정 구간으로 시간 범위를 맞춘 Grafana 대시보드 링크가 있다
- **AND** 넣은 장애마다 실제로 넣은 시각과 거둔 시각이 있다
- **AND** feed-service 지표 수집 비율(0 이상 1 이하)이 있다

#### Scenario: 복구 시간

- **GIVEN** S-08의 요청 결과에서 장애를 120초에 거뒀고, 측정 구간을 10초씩 나눈 구간 중 140초 구간부터 끝까지 p99와 가용성이 SLO를 지킨다(130초 구간은 지키지 못한다)
- **WHEN** 그 결과로 리포트를 만든다
- **THEN** 리포트에 장애를 거둔 시각, 복구된 첫 구간의 시작 시각, 복구 시간 20초가 있다

#### Scenario: 복구하지 못함

- **GIVEN** S-08의 요청 결과에서 장애를 거둔 뒤 마지막 10초 구간이 SLO를 지키지 못한다
- **WHEN** 그 결과로 리포트를 만든다
- **THEN** 복구 시간은 "측정 구간 안에 복구하지 못함"이다

### Requirement: 기대 결과로 판정한다 (F-007)

실험 세트는 단계마다 시나리오별로 SLO를 지켜야 하는지(기대 결과)를 한 파일에 적고, `experiments/run.sh`는 실제 결과가 기대와 같으면 0, 다르면 1, 실행하지 못했으면 2로 끝나야 한다(SHALL). SLO를 못 지키는 것이 기대인 시나리오가 SLO를 지켜도 기대와 다른 것이다. 고도화 2의 기대는 S-01만 SLO를 지키는 것이다.

#### Scenario: 기대대로 SLO를 못 지킨다

- **GIVEN** 기대 결과에서 S-02는 SLO를 지키지 못한다
- **WHEN** `experiments/run.sh S-02 --rps 5 --warmup 5s --duration 20s`가 p99 기준을 넘는다
- **THEN** 리포트에 "SLO 미충족, 기대와 같음"이 있고 명령은 0으로 끝난다

#### Scenario: 기대와 다르다

- **GIVEN** 기대 결과에서 S-01은 SLO를 지킨다
- **WHEN** p99가 SLO를 넘은 S-01의 부하 결과로 판정한다
- **THEN** 리포트에 "SLO 미충족, 기대와 다름"이 있고 판정 코드는 1이다

#### Scenario: 부하 도구가 실패한다

- **WHEN** 부하 도구가 결과를 남기지 못하고 끝난다(예: 스크립트 오류)
- **THEN** `experiments/run.sh`는 2로 끝나고, 장애를 거둔 상태다

### Requirement: 실험 세트 전체를 돌린다 (F-007)

`experiments/run-all.sh`는 S-01~S-09(시나리오 ID를 주면 그것만)를 차례로 돌리고, 기대와 다른 시나리오가 있어도 끝까지 돌린 뒤 시나리오마다의 판정을 한 표로 출력해야 한다(SHALL). 모두 기대와 같으면 0, 하나라도 다르거나 실행하지 못했으면 0이 아닌 코드로 끝난다. 시나리오 ID 없이 돌려 0으로 끝나는 것을 "장애 실험 세트 통과"로 본다.

#### Scenario: 판정 표

- **WHEN** `experiments/run-all.sh --rps 5 --warmup 5s --duration 20s S-01 S-02`를 돌린다
- **THEN** 두 줄의 표(시나리오 ID, SLO 충족 여부, 기대, 판정, 리포트 경로)를 출력하고 0으로 끝난다

#### Scenario: 기본 목록

- **WHEN** 시나리오 ID 없이 돌릴 목록을 본다
- **THEN** S-01부터 S-09까지 아홉 개다

### Requirement: feed-service의 실험 지표 (F-007)

feed-service는 `/actuator/prometheus`에 Tomcat 사용 중 스레드 수, 공유 호출 스레드 풀의 활성 스레드 수와 대기열 길이, 의존성 5개마다 커넥션 풀의 사용 중·대기 연결 수, 의존성마다 호출 지연 히스토그램, 피드 요청 지연 히스토그램을 내보내야 한다(SHALL). 의존성은 이름(`post`, `user`, `like`, `recommendation`, `ad`)으로 구분된다.

Compose 환경에서는 Actuator(지표, 헬스 체크)를 업무 요청과 다른 관리 포트로 열어, 업무 요청이 Tomcat 스레드를 모두 묶어도 Prometheus가 지표를 긁을 수 있어야 한다(SHALL).

#### Scenario: 지표가 있다

- **GIVEN** 하위 서비스 5개가 정상 응답한다
- **WHEN** `GET /feed`를 한 번 부른 뒤 `GET /actuator/prometheus`를 부른다
- **THEN** Tomcat 사용 중 스레드, 공유 호출 스레드 풀의 활성 스레드와 대기열 값이 있다
- **AND** 의존성 이름 5개마다 커넥션 풀의 사용 중·대기 값과 호출 지연 히스토그램 버킷이 있다
- **AND** `/feed` 요청 지연 히스토그램 버킷이 있다

#### Scenario: Tomcat 스레드가 모두 묶여도 긁힌다

- **GIVEN** Compose 환경의 feed-service는 지표와 헬스 체크를 업무 요청과 다른 관리 포트로 내보낸다
- **WHEN** `experiments/run.sh S-03 --rps 60 --warmup 5s --duration 20s`를 돌린다(동시 요청이 60 × 5초 = 300개로 Tomcat 최대 스레드 200을 넘는다)
- **THEN** 리포트의 feed-service 지표 수집 비율은 1이다
- **AND** 리포트의 Tomcat 사용 중 스레드 최댓값은 200이다

### Requirement: 실험 대시보드 (F-007)

Compose 환경의 Grafana는 띄울 때 파일로 둔 피드 실험 대시보드를 불러와야 한다(SHALL). 대시보드에는 피드 가용성과 p50·p95·p99, 의존성별 호출 지연·실패율, 자원 사용량(리포트의 자원 항목)이 있다.

#### Scenario: 대시보드를 불러온다

- **GIVEN** Compose 환경이 떠 있고 `experiments/run.sh S-01 --rps 5 --warmup 5s --duration 20s`를 돌렸다
- **WHEN** Grafana에서 피드 실험 대시보드를 찾는다
- **THEN** 대시보드가 있다
- **AND** 대시보드 패널의 Prometheus 질의는 모두 오류 없이 하나 이상의 시계열을 돌려준다
