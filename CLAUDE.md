# weave

여러 하위 서비스에서 데이터를 모아 피드를 구성할 때, 하위 서비스 일부의 장애가 피드 전체 장애로 번지지 않도록 격리하는 프로젝트다.

## 먼저 읽을 것

- **개발 흐름:** [conventions/harness.md](conventions/harness.md). 스펙은 OpenSpec으로, 검증은 하네스로, 구현은 루프로 한다
- **규칙:** [spec.md](conventions/spec.md)(PRD와 OpenSpec), [commit.md](conventions/commit.md), [branch.md](conventions/branch.md)
- **제품:** [PRD](docs/prd.md)(단계, SLO, 의존성 등급, 기능 ID와 수용 기준), [아키텍처](docs/architecture.md)
- **동작 스펙:** `openspec/specs/`, 진행 중인 change는 `openspec/changes/`

## 구조

| 폴더 | 내용 |
|---|---|
| `feed-service/` (8080) | 피드 집계. 하위 서비스 호출과 장애 대응이 여기에 있다 |
| `post-service/` (8081), `user-service/` (8082), `like-service/` (8083), `recommendation-service/` (8084) | 하위 서비스 |
| `ad-service/` (8085) | 광고. 통제할 수 없는 외부 API 역할 |
| `e2e/` | 서비스 6개를 bootJar 프로세스로 띄우는 종단 간 테스트 |
| `libs/observability/` | 모든 서비스에 자동 적용되는 로그·트레이싱 설정 |
| `buildSrc/` | 컨벤션 플러그인. 의존성과 테스트 설정은 여기서 정한다 |
| `scripts/harness/` | Claude Code 훅 (`.claude/settings.json`에서 연결) |
| `scripts/git-hooks/` | 커밋 메시지·브랜치 검사 |
| `scripts/ralph/` | Ralph 루프 |

## 명령

| 목적 | 명령 |
|---|---|
| 처음 한 번 | `git config core.hooksPath scripts/git-hooks` |
| 린트·빌드·전체 테스트 | `./gradlew ktlintCheck build` |
| 포맷 | `./gradlew ktlintFormat` |
| 단위 테스트 | `./gradlew test` (모듈 하나는 `./gradlew :feed-service:test`) |
| 통합 테스트 | `./gradlew integrationTest` |
| 기능 ID로 거르기 | `./gradlew test -Pkotest.filter.tests='*F-003*'` |
| 스펙 검사 | `openspec validate --all --strict --no-interactive` |
| 종단 간 테스트 | `./gradlew :e2e:integrationTest` (서비스 6개를 프로세스로 띄운다. Docker 필요) |
| 서비스 실행 | `./gradlew :feed-service:bootRun` (like-service는 `./gradlew :like-service:bootTestRun`으로 MySQL과 함께 띄운다) |

훅은 `jq`를 쓴다.

## 지킬 것

- 작업은 `develop`에서 만든 브랜치에서 한다. `main`에는 직접 커밋하지 않는다.
- 커밋 메시지는 [커밋 컨벤션](conventions/commit.md) 형식을 따른다. git `commit-msg` 훅이 검사한다.
- `.env`는 읽거나 고치지 않는다.
- 기능은 OpenSpec change를 기준으로, 시나리오를 테스트로 먼저 만들고 구현한다.
- 테스트는 Kotest로 쓴다. Spring 컨텍스트, WireMock, Testcontainers를 쓰는 테스트에는 `@Tags("Integration")`을 붙인다.
- 장애 대응 설정값은 근거(측정값이나 계산) 없이 정하지 않는다.
