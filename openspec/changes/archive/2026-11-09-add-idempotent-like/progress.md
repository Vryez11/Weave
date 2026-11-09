# add-idempotent-like 진행 메모

## 다시 쓸 패턴

- like-service의 새 시나리오 테스트는 `LikeHttp.kt`(`likeHttp()`, `clearLikes()`, `likedRows()`, `concurrently`)로 실제 HTTP 포트를 부른다.
- 불일치 검사를 보는 테스트는 `management.endpoints.web.exposure.include`에 `likeconsistency`를 준다.
- zsh에서는 `${PIPESTATUS[0]}`이 없다. 검사가 통과해야 커밋하려면 `if ./gradlew ...; then git commit ...; fi`로 쓴다.
- `openspec validate`는 MODIFIED 블록에서 시나리오 이름이 사라지면 막는다. 시나리오 이름을 바꿔야 하면 REMOVED + ADDED(새 요구사항 이름)로 옮긴다.

## 기록

### 대화형 1~5장 (2026-11-09)

- 토글 재현은 커밋 `98f193f`에 있고 수치는 `docs/experiments/idempotent-like.md`에 적었다.
- `LikeServiceTest`(목 테스트)는 조회 구현에 묶여 지우고 순서 시나리오를 `PostLikeScenarioTest`로 옮겼다.
- 본문의 `liked`, `version`은 nullable로 받는다. Kotlin non-null 원시 타입은 필드가 빠져도 false·0이 될 수 있어서다.
- `like-config.json`의 값은 6.1 측정으로 400 RPS, 준비 1분, 측정 2분으로 정했다(`docs/experiments/idempotent-like.md`).

### 측정과 검토 (2026-11-09)

- S-11은 400 RPS로 비교해 기본 `atomic`, 분할 8로 정했다. S-10은 네 경우 모두 기대와 같다.
- S-01~S-09는 모두 기대와 같고 p99는 고도화 5와 4ms 안이다. S-03 가용성은 0.999233(503 92건, 장애 시작 순간)이다.
- 독립 검토의 막음 3건(입력 검증, 동시 요청 단언, 리포트 줄 테스트)을 8.4에서 고쳤다.
- `kotest.filter.tests='*F-003*'`는 일부 스펙의 테스트를 모두 건너뛴 채 통과할 수 있다. 수정 확인은 `--tests`로 클래스를 지정했다.

**PR 설명용 요약:** 장애 실험 세트 9개가 모두 기대와 같다(`run-all.sh` 0 종료, 커밋 `f283231`). 고도화 5 대비 p99는 4ms 안이다. 지켜볼 값은 S-03 가용성 1 → 0.999233(SLO 안, 503 92건은 장애 시작 순간의 feed-service 멈춤과 캐시 읽기 실패가 겹친 모습)과 S-05 like 요청 배율 최댓값 0.762 → 1.101(상한 1.2 안)이다. 쓰기 실패는 like 서킷을 조회와 함께 세므로 쓰기 몰림의 막힘이 조회의 `UNKNOWN`으로 번질 수 있다.
