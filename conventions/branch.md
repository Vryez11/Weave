# 브랜치 컨벤션

`main`은 언제든 빌드·테스트·장애 실험 세트가 통과하는 상태로 두고, 개발은 `develop`에 모은다. OpenSpec change 하나는 브랜치 하나에서 구현한다.

"장애 실험 세트 통과"는 Compose 환경에서 `experiments/run-all.sh`를 시나리오 없이 돌려 0으로 끝나는 것이다. 모든 시나리오가 SLO를 지킨다는 뜻이 아니라, 시나리오마다의 결과가 그 단계의 기대(`experiments/expectations.json`)와 같다는 뜻이다. 장애 대응 change는 자기가 고친 시나리오의 기대를 `meet`으로 바꾼다([하네스 규칙](harness.md) 5장).

## 브랜치

| 브랜치 | 역할 | 어디서 만드나 | 어디로 합치나 |
|---|---|---|---|
| `main` | 언제든 빌드·테스트·장애 실험 세트가 통과하는 상태. 단계(MVP, 고도화)마다 태그를 붙인다 | — | — |
| `develop` | change를 모으는 곳 | `main` | `main` (릴리스) |
| `change/<change-id>` | OpenSpec change 하나 구현. Ralph 루프가 일하는 곳 | `develop` | `develop` |
| `fix/<짧은-설명>` | change가 아닌 작은 버그 수정 | `develop` | `develop` |
| `chore/<짧은-설명>` | 기능이 아닌 개발 환경·설정 작업 (하네스, CI, 빌드 설정, 로컬 인프라 등) | `develop` | `develop` |
| `hotfix/<짧은-설명>` | `main`의 급한 수정 | `main` | `main`과 `develop` 둘 다 |

`<change-id>`는 OpenSpec change 이름을 그대로 쓴다. 예) `change/add-call-isolation`
`<짧은-설명>`은 영어 소문자와 하이픈으로 쓴다. 예) `fix/missing-trace-id`

## 흐름

```mermaid
gitGraph
    commit id: "초기 구성"
    branch develop
    checkout develop
    commit id: "문서 수정"
    branch change-add-call-isolation
    checkout change-add-call-isolation
    commit id: "test F-001"
    commit id: "feat F-001"
    checkout develop
    merge change-add-call-isolation
    checkout main
    merge develop tag: "v0.1.0"
    branch hotfix-retry-storm
    checkout hotfix-retry-storm
    commit id: "fix"
    checkout main
    merge hotfix-retry-storm tag: "v0.1.1"
    checkout develop
    merge hotfix-retry-storm
```

그림에서는 Mermaid 문법 때문에 `/` 대신 `-`로 적었다. 실제 이름은 `change/add-call-isolation`, `hotfix/retry-storm`이다.

## 규칙

1. **change:** `develop`에서 `change/<change-id>`를 만든다. 구현, 검토, archive까지 이 브랜치에서 끝내고 `develop`으로 PR을 연다([하네스 규칙](harness.md)).
2. **문서만 바꿀 때:** 브랜치 없이 `develop`에 바로 커밋한다.
3. **릴리스:** `develop` → `main` PR을 릴리스 템플릿으로 열고(`gh pr create --base main --template release.md`), CI와 장애 실험 세트가 통과하면 merge한다. merge 뒤 `main`에 태그를 붙인다.
4. **hotfix:** `main`에서 만들어 `main`에 merge하고 패치 태그를 붙인 뒤, 같은 브랜치를 `develop`에도 merge한다.
5. **직접 커밋 금지:** `main`에는 직접 커밋하지 않는다. 릴리스와 hotfix PR로만 바뀐다. `develop`에는 문서 커밋만 직접 한다.
6. **병합 방식:** squash하지 않고 merge commit으로 합친다. Ralph가 작업마다 남긴 커밋을 보존해 작업 단위로 되돌릴 수 있게 한다.
7. **정리:** merge한 `change/`, `fix/`, `chore/`, `hotfix/` 브랜치는 지운다.
8. **보호:** GitHub 브랜치 보호로 `main`과 `develop`은 PR로만 합치고 CI 통과를 필수로 한다(2026-11-04 설정).

   | | `main` | `develop` |
   |---|---|---|
   | PR 필수 | 예 (승인 0명. 혼자 개발해 자기 PR을 승인할 수 없다) | 예 (승인 0명) |
   | 필수 검사 | CI `backend`, `spec`. 브랜치가 최신이어야 merge | 같음 |
   | 관리자 예외 | 없음. 관리자도 PR로만 바꾼다 | 있음. 2번(문서만 바꿀 때 바로 커밋)을 위한 것이고, 문서가 아닌 변경은 관리자라도 PR로 한다 |
   | 강제 push, 삭제 | 막음 | 막음 |

## 태그

`v<MAJOR>.<MINOR>.<PATCH>` 형식을 쓴다.

| 올리는 자리 | 언제 |
|---|---|
| MINOR | `develop` → `main` 릴리스. MVP와 고도화 단계 하나가 끝날 때 (PRD 1장) |
| PATCH | hotfix |
| MAJOR | 고도화 1~6을 모두 끝냈을 때 (`v1.0.0`) |

MVP 릴리스가 `v0.1.0`이고, 고도화 1이 `v0.2.0`, 고도화 6이 `v0.7.0`이다.
