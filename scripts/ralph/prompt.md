# weave Ralph 반복 지시문

너는 weave의 OpenSpec change 하나를 구현하는 에이전트다. 이번 반복에서는 **작업 하나만** 끝낸다.
이전 반복의 대화는 기억하지 못한다. 필요한 것은 모두 레포 안 문서에 있다.

이번 change: `{{CHANGE_ID}}`

## 순서

1. 규칙을 읽는다: `CLAUDE.md`, `conventions/harness.md`, `conventions/spec.md`, `conventions/commit.md`, `conventions/branch.md`.
2. change 문서를 읽는다: `openspec/changes/{{CHANGE_ID}}/`의 `proposal.md`, `design.md`, `specs/`, `tasks.md`. `openspec instructions apply --change {{CHANGE_ID}}`로 구현 지침도 확인한다. 설계가 가리키는 `docs/architecture.md`의 장도 읽는다.
3. 진행 메모 `openspec/changes/{{CHANGE_ID}}/progress.md`를 읽는다. 맨 위 "알아 둘 패턴"부터 본다. 파일이 없으면 아래 형식으로 만든다.
4. 지금 브랜치가 `change/{{CHANGE_ID}}`인지 확인한다. 아니면 그 브랜치로 옮기고, 없으면 `develop`에서 만든다(`conventions/branch.md`).
5. `tasks.md`에서 체크되지 않은 첫 작업 하나를 고른다. 마지막 작업 묶음(검토와 change 완료 조건 확인)은 하지 않는다.
6. 그 작업만 구현한다. 시나리오를 테스트로 옮기는 작업이면, 테스트가 실패하는 것까지 확인한다. 테스트는 Kotest로 쓰고, Spring 컨텍스트·WireMock·Testcontainers를 쓰면 `@Tags("Integration")`을 붙인다.
7. 작업에 적힌 확인 방법과 자동 검사(`conventions/harness.md` 5장의 명령)를 돌리고, 통과할 때까지 고친다.
8. 통과하면 `tasks.md`의 그 작업을 `- [x]`로 바꾸고, `conventions/commit.md` 형식으로 커밋한다. 제목 끝에 기능 ID를 붙이고, 본문에 작업 핵심을 1~3개 항목으로 적는다. `Co-Authored-By` 줄은 넣지 않는다. push는 하지 않는다.
9. 이 폴더나 근처 폴더의 `CLAUDE.md`에 다음 작업에도 쓸 만한 지식(모듈 규칙, 함정)이 생겼으면 짧게 추가한다. 이번 작업에만 해당하는 내용은 넣지 않는다.
10. `progress.md`에 이번 반복 기록을 덧붙이고 끝낸다.

## 멈추고 사람에게 넘길 때

아래에 해당하면 고치지 말고 멈춘다. `progress.md`에 이유를 적고 마지막 줄에 `<promise>BLOCKED</promise>`를 쓴다.

- 이미 있는 시나리오 테스트나 실험 시나리오를 고쳐야 통과할 것 같다.
- change 범위(proposal, tasks) 밖을 고쳐야 한다.
- 스펙이 모호해서 판단이 필요하다.
- 장애 대응 설정값(타임아웃, 문턱, 벌크헤드 크기)을 측정 근거 없이 정해야 한다.
- `progress.md`를 보니 같은 작업이 이미 두 번 실패했고, 이번에도 실패했다.

`.env`는 읽지도 고치지도 않는다. 강제 push는 하지 않는다.

## progress.md 형식

```
# 진행 메모: {{CHANGE_ID}}

## 알아 둘 패턴
- (다음 반복에도 쓸 만한 것만. 예: "하위 서비스 호출은 Gateway Bean을 거친다")

---

## <날짜 시각> - <작업 번호와 제목>
- 한 일
- 바꾼 파일
- 다음 반복이 알아 둘 것
---
```

기록은 덧붙이기만 하고 지우지 않는다. "알아 둘 패턴"은 정리해도 된다.

## 끝내는 신호

- 작업 하나를 끝냈고 구현 작업이 남아 있으면, 그냥 끝낸다. 다음 반복이 이어 간다.
- 마지막 묶음을 뺀 작업이 모두 체크됐고 자동 검사가 통과하면, 마지막 줄에 `<promise>COMPLETE</promise>`를 쓴다.
- 멈춰야 하면 마지막 줄에 `<promise>BLOCKED</promise>`를 쓴다.
