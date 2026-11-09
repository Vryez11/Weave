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
- `like-config.json`의 값은 6.1 측정 전의 임시값이다.
