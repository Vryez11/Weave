# Proposal

- **기능:** F-004 traceId 로그 수집
- **단계:** 고도화 1 ([PRD](../../../../docs/prd.md) 1장, 6장 F-004)

## Why

고도화 2에서 장애를 넣어 MVP가 무너지는 모습을 기록하려면, 피드 요청 하나가 어느 서비스에서 어떻게 실패했는지 따라갈 수 있어야 한다. 지금은 서비스 6개가 Spring Boot 기본 텍스트 로그를 따로따로 쓰고, 트레이싱이 없어 요청끼리 이어 볼 방법이 없다. 하위 서비스 병렬 호출은 공유 스레드 풀(`applicationTaskExecutor`)에서 돌아서, 트레이싱을 켜기만 하면 스레드가 바뀌는 곳에서 traceId가 끊긴다.

## What Changes

- 모든 서비스가 `libs/observability` 하나로 아래를 똑같이 적용받는다. 서비스는 로그·트레이싱 설정을 따로 두지 않는다.
  - JSON 로그(stdout): 시각, 레벨, 서비스 이름, traceId, spanId, 로거, 스레드, 메시지, 예외
  - Micrometer Tracing + OpenTelemetry. W3C `traceparent`로 전파하고, 모든 요청을 수집한다(샘플링 100%)
  - 응답 헤더 `X-Trace-Id`
  - 요청마다 한 줄씩 남기는 요청 완료 로그(메서드, 경로, 상태, 걸린 시간). 쿼리 문자열, 헤더, 본문은 남기지 않는다
- **끊김을 먼저 재현한다.** 트레이싱만 켠 상태에서 공유 스레드 풀과 `CompletableFuture` 경계에서 traceId가 끊기는 것을 테스트로 남긴 뒤, 컨텍스트 전파를 켠다. 전파 없는 실행기에서 끊긴다는 테스트는 기록으로 계속 둔다.
- feed-service가 하위 서비스 호출 실패를 의존성 이름, 원인과 함께 로그로 남긴다. 장애 대응 이벤트(서킷 전이, 벌크헤드 거절, fallback, 재시도)는 아직 없으므로, 그 로그는 같은 형식으로 F-001·F-002·F-005에서 더한다.
- Docker Compose로 서비스 6개, MySQL, 관찰 인프라(Alloy, Loki, Tempo, Prometheus, Grafana)를 함께 띄운다.
  - 로그는 Alloy가 컨테이너 stdout을 읽어 Loki로, 스팬은 서비스가 OTLP로 Alloy를 거쳐 Tempo로 보낸다.
  - Grafana에서 로그와 트레이스를 서로 오갈 수 있게 데이터 소스를 파일로 둔다.
- traceId 하나로 6개 서비스의 로그를 Loki에서 시간순으로 조회하는 것을, Compose를 띄워 확인하는 테스트로 남긴다.

## Capabilities

### New Capabilities

- `trace-logging`: 모든 서비스에 공통으로 적용되는 로그 형식, traceId 전파, 응답 헤더, 요청 완료 로그, 로그에 남기지 않는 것, 그리고 Loki에서 traceId로 로그를 모아 보는 동작

### Modified Capabilities

- `feed-aggregation`: 하위 서비스 호출이 실패하면 traceId, 의존성 이름, 원인을 로그로 남긴다는 요구사항을 더한다. 응답 동작(실패하면 500)은 바뀌지 않는다.

## Impact

- **코드:** `libs/observability`(로그 설정, 기본 속성, 응답 헤더·요청 완료 로그 필터), feed-service의 Gateway(실패 로그)와 HTTP 서비스 그룹 클라이언트의 오류 상태 처리기(예외 메시지에서 하위 서비스 응답 본문을 뺀다. 구현 중 design 결정 6에 더했고 검토에서 승인), 서비스 6개의 테스트, `e2e`(로그 파일 검사, Compose 테스트)
- **API:** 모든 서비스 응답에 `X-Trace-Id` 헤더가 붙는다. 요청 헤더 `traceparent`를 받으면 그 trace를 잇는다. 응답 본문은 바뀌지 않는다.
- **의존성:** design.md 결정 9의 목록. 추가 항목은 이 change를 승인할 때 함께 확인한다(하네스 규칙 2-2).
  - observability: logstash-logback-encoder, Boot OpenTelemetry 스타터
  - 서비스 공통: Prometheus 레지스트리
- **인프라:** 루트 `compose.yaml`, `docker/`(서비스 이미지, Alloy·Loki·Tempo·Prometheus·Grafana 설정). Compose 서비스 추가는 하네스 규칙 2-2의 확인 대상이라 이 change 승인으로 함께 확인한다.
- **빌드:** Compose 테스트용 Kotest 태그 `Compose`와 `:e2e:composeTest` 태스크. CI에 Compose 테스트 단계를 더한다.
- **문서:** `docs/architecture.md` 7장(정한 방식으로 고친다), `CLAUDE.md`와 하네스 규칙 5장의 `docker compose up -d`, Compose 테스트 명령, `.env.example`의 MySQL 항목
- **하지 않는 것:**
  - 벌크헤드 스레드 풀의 전파(F-001)와 장애 대응 이벤트 로그(F-001·F-002·F-005)
  - Grafana 대시보드(아키텍처 7-4). 보여 줄 지표 대부분이 고도화 2~4에서 생겨서, 실험 리포트를 만드는 F-007에서 만든다
  - Toxiproxy, Redis, Compose 자원 상한(F-007·F-002)
