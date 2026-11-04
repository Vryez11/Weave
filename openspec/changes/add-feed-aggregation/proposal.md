# Proposal

- **기능:** F-006 피드 집계 기본 흐름
- **단계:** MVP ([PRD](../../../docs/prd.md) 1장, 6장 F-006)

## Why

고도화 단계는 "문제 → 측정 → 해결 → 재측정" 순서로 간다. 장애를 넣어 무너지는 모습을 재려면, 먼저 보호 장치 없이 끝까지 동작하는 피드가 있어야 한다. 지금은 서비스 6개가 빈 애플리케이션이라 서로 부르는 곳이 없다.

## What Changes

- 하위 서비스 5개에 피드 구성에 필요한 최소 API를 만든다.
  - post: 최신 게시글 목록
  - user: 프로필 배치 조회
  - like: 좋아요 배치 조회, 토글
  - recommendation: 추천 목록
  - ad: 광고 목록
- post, user, recommendation, ad는 서비스가 시작할 때 만드는 고정 데이터로 응답한다.
- like는 MySQL에 저장하고, 누를 때마다 상태가 뒤집히는 **토글 API**로 둔다.
  - 동시 요청과 재시도에서 깨지는 방식(읽고 고쳐 저장하기)을 일부러 그대로 둔다. 고도화 6(F-003)에서 이 문제를 재현하기 위해서다.
- feed-service가 PRD 3장의 호출 흐름대로 하위 서비스를 불러 피드 하나를 만든다.
  - post, recommendation, ad를 먼저 병렬로 부르고, post 결과로 user와 like를 병렬로 부른다.
  - 피드 응답은 게시글(작성자, 좋아요 수, 내 좋아요 상태 포함), 추천, 광고로 이루어진다.
- feed-service가 좋아요 토글을 like-service로 넘기는 API를 둔다. 클라이언트는 feed-service만 부른다.
- **보호 장치를 두지 않는다.**
  - 타임아웃은 라이브러리 기본값을 쓰고, 서킷 브레이커, 벌크헤드, 재시도, fallback은 없다. 병렬 호출은 공유 스레드 풀 하나(Boot의 `applicationTaskExecutor`)에서 돈다.
  - 하위 서비스 하나가 실패하면 피드 전체가 실패한다.
  - 그 기본값(풀 크기, 큐 크기, HttpClient 타임아웃)을 실제로 확인해 아키텍처 문서에 기록한다. 고도화 2의 기준선이 이 상태다.
- 평시 용량(커넥션 풀, 공유 스레드 풀 크기)은 리틀의 법칙으로 계산해 정한다. 보호 장치가 없다는 것이 평시 용량이 모자라도 된다는 뜻은 아니다.
- 서비스 6개를 실제 프로세스로 띄워 피드 하나를 끝까지 받는 종단 간 통합 테스트를 둔다.

## Capabilities

### New Capabilities

- `feed-aggregation`: feed-service가 하위 서비스를 불러 피드를 만들고, 좋아요 토글을 넘기는 동작. 호출 순서, 응답 구성, 실패할 때의 동작을 맡는다. 고도화 3·4·5에서 격리, degradation, 재시도 요구사항이 여기에 더해진다.
- `post-catalog`: post-service의 최신 게시글 목록 조회
- `user-profile`: user-service의 프로필 배치 조회
- `post-like`: like-service의 좋아요 배치 조회와 토글, 저장. 고도화 6에서 상태 기반 API로 바뀐다.
- `recommendation`: recommendation-service의 추천 목록 조회
- `ad-serving`: ad-service의 광고 목록 조회. 고도화 2·5에서 429, Retry-After, 쿼터 흉내가 더해진다.

### Modified Capabilities

없음. `openspec/specs/`에 아직 스펙이 없다.

## Impact

- **코드:** 서비스 6개의 `src/main`, `src/test`. 새 Gradle 모듈 `e2e`(종단 간 통합 테스트 전용)
- **API:**
  - 새 HTTP API: feed `GET /feed`, `POST /feed/likes/{postId}/toggle`
  - 하위 서비스: post `GET /posts`, user `GET /users`, like `GET /likes`, `POST /likes/{postId}/toggle`, recommendation `GET /recommendations`, ad `GET /ads`
- **의존성:** design.md 7절의 목록. 추가 항목은 이 change를 승인할 때 함께 확인한다(하네스 규칙 2-2).
  - feed: RestClient 스타터, Apache HttpClient 5, WireMock
  - like: JPA, Flyway, MySQL 드라이버, Testcontainers
  - buildSrc: kotlin-noarg
- **데이터:** like-service의 MySQL 스키마(`post_like`, `post_like_count`)를 Flyway로 만든다.
- **문서:** `docs/architecture.md` 4-2에 MVP 기본값과 용량 계산을 기록한다. `CLAUDE.md`와 하네스 규칙 5장에 like-service 로컬 실행과 종단 간 테스트 명령을 더한다.
- **하네스:** `scripts/harness/stop-check.sh`의 모듈 목록에 `e2e`를 더한다.
- **하지 않는 것:** traceId 로그(F-004), Docker Compose와 Toxiproxy(F-004·F-007), 장애 대응(F-001·F-002·F-005), 상태 기반 좋아요(F-003)
