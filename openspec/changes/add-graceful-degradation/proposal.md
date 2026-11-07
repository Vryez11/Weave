# Proposal

- **기능:** F-002 등급별 Graceful Degradation
- **단계:** 고도화 4 ([PRD](../../../docs/prd.md) 1장, 3장, 4장, 6장 F-002). F-004의 남은 수용 기준 중 fallback 이벤트 로그도 여기서 채운다.

## Why

[고도화 3 결과](../../../docs/experiments/call-isolation.md)에서 느린 의존성이 더는 피드 자원을 묶지 않게 됐지만(S-02 p99 5,002ms → 201ms), 실패한 의존성은 여전히 피드 전체를 500으로 만든다. 선택 등급인 recommendation 하나가 아파도(S-02, S-03) 가용성은 0이고, like 에러율 50%(S-05)는 서킷까지 열려 가용성이 0.064로 기준선(0.50)보다 나빠졌다. 장애가 피드 서버로 번지지 않는 것(F-001)에서 한 걸음 더 가서, 실패한 영역만 빠지고 피드는 응답해야 PRD 2장의 SLO(가용성 99.9%)를 장애 시나리오에서도 지킬 수 있다.

## What Changes

- **등급별로 응답한다(PRD 3장 표).** 하위 서비스 호출이 어떤 이유로 실패하든(5xx, 4xx, 연결 실패, 시간 상한, 서킷 열림, 벌크헤드 거절, 시간 예산 초과) 등급대로 대체한다.
  - **post (필수):** 마지막 정상 응답을 Redis에 두고, 실패하면 10분 안의 것으로 stale 응답한다. 캐시가 없거나 10분이 넘었으면 503이다. 503도 시간 예산 안에 나간다.
  - **user (중요):** feed-service 로컬 캐시(Caffeine)의 프로필로 채우고, 캐시에 없는 작성자는 "알 수 없는 사용자"로 표시한다.
  - **like (선택):** 좋아요 수를 빼고 내 좋아요 상태를 `UNKNOWN`으로 내린다.
  - **recommendation, ad (선택):** 그 영역을 빈 목록으로 뺀다.
- **응답에 빠진 영역과 stale 여부를 담는다.** 피드 응답에 `degradation`(빠진 영역 목록, stale 영역과 캐시 시각)을 늘 넣어 클라이언트가 정상 응답과 구분한다. 작성자에 `unknown`을, 좋아요 수에 null을 둔다.
- **대체 수단이 장애를 키우지 않는다.** post 캐시(Redis) 쓰기는 응답 경로 밖에서 하고 실패해도 피드에 영향이 없다. Redis 읽기에는 시간 예산 계산으로 정한 짧은 상한을 둔다. Redis가 죽어도 post-service가 정상이면 피드는 정상이고, Redis 상태가 feed-service 헬스 체크를 내리지 않는다.
- **대체 이벤트를 남긴다.** 대체할 때마다 `fallback_applied` 로그(traceId, 의존성, 대체 방법)를 한 줄 남기고, 의존성별 영역 결과(정상, 캐시로 채움, stale, 빠짐) 지표와 post 캐시 읽기·쓰기, user 캐시 지표를 내보낸다.
- **설정값은 근거와 함께 정한다.** stale 허용 10분은 PRD 결정이다. Redis 읽기 상한, 캐시 쓰기 간격, user 캐시 TTL·최대 크기는 측정이나 계산으로 정해 design과 아키텍처 6장에 적는다.
- **실험 세트가 degrade 비율을 잰다.** 부하 도구가 응답의 `degradation`을 읽어 의존성별 degrade 비율을 리포트에 적고(지금은 "없음"), 대시보드에 degrade 행을 더한다. S-01의 판정에 "의존성별 degrade 비율 1% 미만" 기준을 더한다. 실험을 시작할 때 post 캐시를 비워 같은 상태에서 시작한다.
- **고도화 4의 기대를 적는다.** S-01~S-09 모두 가용성과 p99를 지키는 것이 기대다(S-01은 degrade 비율도). S-07의 Retry-After 준수, S-09의 재시도 폭주 없음처럼 시나리오마다의 따로 기대하는 동작은 F-005에서 다룬다.
- **Compose 환경에 Redis를 더한다.**
- **고도화 4 결과를 기록한다.** 실험 세트를 돌려 고도화 3과 비교한다(`docs/experiments/graceful-degradation.md`).
- **BREAKING (API·스펙):** 피드 응답 형식이 바뀐다(`degradation` 추가, `like.count` nullable, `myLike`에 `UNKNOWN`, `author.unknown`). `feed-aggregation`의 "하위 서비스 실패는 시간 안에 피드 실패로 끝난다"는 없어지고, 실패는 등급별 응답으로 바뀐다. `call-isolation`과 `fault-experiments`에서 피드 500을 전제로 쓴 시나리오도 함께 고친다.

## Capabilities

### New Capabilities

- `graceful-degradation`: feed-service가 하위 서비스 실패를 등급별로 대체하는 동작. 부분 응답 계약(`degradation`), post stale 캐시와 503, user 로컬 캐시와 "알 수 없는 사용자", like `UNKNOWN`, 선택 영역 빼기, 대체 수단의 격리(Redis 장애), fallback 이벤트 로그와 지표, 설정으로 바꾸는 값

### Modified Capabilities

- `feed-aggregation`: "피드 응답 구성"에 `degradation`과 nullable 필드를 더한다. "하위 서비스 실패는 시간 안에 피드 실패로 끝난다"를 지우고 실패 응답은 `graceful-degradation`이 정한다. "하위 서비스 호출 실패 로그"의 "응답은 500" 단언을 바꾼다.
- `call-isolation`: 피드 500을 단언하던 시나리오(예산 초과)를 등급별 응답으로 바꾼다. 요청 시간 예산을 넘긴 의존성은 실패로 보고 등급대로 대체한다.
- `fault-experiments`: 리포트의 degrade 비율, degrade 비율 판정 기준, 고도화 4의 기대, 시작할 때 post 캐시 비우기, "모든 요청이 실패한다" 시나리오(post 캐시도 쓸 수 없을 때), 대시보드 degrade 행, Compose의 Redis

## Impact

- **코드:** feed-service의 `FeedAssembler`(등급별 결과 구성), `FeedResponse`(응답 계약), `FeedController`(503), 새 패키지(post stale 캐시, user 로컬 캐시, 대체 로그·지표). 하위 서비스 5개는 바뀌지 않는다. 좋아요 토글은 그대로 실패 시 500이다.
- **API:** `GET /feed` 응답 형식이 바뀐다(BREAKING, 위). 하위 서비스가 실패해도 대부분 200이고, post를 대체할 수 없을 때만 503이다.
- **의존성:** feed-service에 `spring-boot-starter-data-redis`, `com.github.ben-manes.caffeine:caffeine`(Boot BOM), 테스트에 Testcontainers(`org.testcontainers:testcontainers`, Redis는 `GenericContainer`)를 더한다. Compose에 Redis 서비스를 더한다. 하네스 규칙 2-2의 "확인" 항목이라 이 change를 승인할 때 함께 확인한다.
- **실험:** `experiments/k6/contract.js`(빠진 영역 읽기), `run.sh`(degrade 비율, post 캐시 비우기), `lib/verdict.sh`·`slo.json`(degrade 기준), `expectations.json`(고도화 4), Grafana 대시보드 degrade 행, `compose.yaml`(Redis).
- **테스트:** 피드 500을 단언하던 기존 feed-service 시나리오 테스트와 e2e Compose·판정 테스트를 스펙 델타대로 고친다. 이미 커밋된 테스트라 Ralph 루프가 아니라 대화형(`/opsx:apply`)으로 고친다(하네스 규칙 2-2 훅).
- **문서:** 아키텍처 6장·7-4·9-1·9-3·12·13장, PRD F-002·F-004 체크와 10장 "남은 것"(user 캐시 TTL, post 캐시 키), `docs/experiments/graceful-degradation.md`, feed-service `CLAUDE.md`
- **하지 않는 것:**
  - 재시도, ad의 429·Retry-After와 쿼터(F-005). ad 429도 이 change에서는 "선택 영역 빼기"일 뿐이다
  - 좋아요 토글 실패의 응답 변경(F-003에서 상태 기반 API로 바꾼다)
  - user 프로필을 캐시에서 먼저 읽는 것(read-through). 캐시는 실패했을 때만 쓴다
  - Redis를 Toxiproxy 뒤에 두는 것과 Redis 장애 실험 시나리오. Redis 장애는 통합 테스트로 확인한다
  - 실험 데이터가 고정이라(게시글 200개, 작성자 50명, 최신 목록이 늘 같다) user 캐시 적중률이 현실보다 높게 나온다. 적중률 실험은 하지 않고 결과 문서에 한계로 적는다
