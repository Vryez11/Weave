# Tasks

## 1. 재현과 시나리오 테스트

시나리오(WHEN/THEN)를 실패하는 테스트로 옮긴다. 구현이 없으니 새 동작을 단언하는 테스트는 `integrationTest`에서 실패하고, `test`(Stop 훅)는 통과해야 한다. 테스트 이름에 `F-003`을 넣고, 다른 기능의 요구사항을 옮긴 것은 그 기능 ID도 넣는다(design 결정 11).

**1.1~1.5는 커밋된 테스트를 고치거나 지우므로 Ralph 루프가 아니라 대화형(`/opsx:apply`)으로 먼저 끝낸다**(하네스 규칙 2-2의 훅). Ralph가 이 다섯 중 체크되지 않은 작업을 만나면 구현하지 말고 progress.md에 적은 뒤 `<promise>BLOCKED</promise>`로 멈춘다.

- [x] 1.1 (대화형) 토글의 문제를 재현하고 기록한다(design 결정 8).
  - `like-service`에 `ToggleBreakageReproductionTest`(Integration, 실제 HTTP 포트): 같은 요청을 다시 보내면 의도가 뒤집힌다, 서로 다른 사용자 50명의 동시 토글에서 수가 행 수보다 작다(갱신 유실), 같은 사용자의 동시 첫 토글 일부가 5xx다. 수치는 단언하지 않고 "깨진다"만 단언하며 값을 출력한다.
  - `docs/experiments/idempotent-like.md`의 "토글의 문제" 절에 수치를 적는다. 해시는 1.2에서 적는다.
  - 확인: `./gradlew :like-service:integrationTest -Pkotest.filter.tests='*F-003*'`이 통과하고 문서에 세 경우의 수치가 있다.
- [x] 1.2 (대화형) 재현 테스트를 지우고 like-service의 기존 테스트를 새 API로 옮긴다.
  - 문서에 1.1의 커밋 해시를 적고 `ToggleBreakageReproductionTest`를 지운다.
  - `PostLikeScenarioTest`: GIVEN과 "사용자 식별"을 `PUT /likes/{postId}`로 바꾸고 토글 시나리오를 지운다. "좋아요 영속"은 그대로 둔다.
  - `TestLikeServiceApplicationTest`도 `PUT`으로 바꾼다. `LikeServiceTest`는 조회 구현에 묶인 목 테스트라 지우고, "요청 순서대로"는 `PostLikeScenarioTest`로 옮긴다.
  - 확인: `./gradlew :like-service:test`가 통과하고 `:like-service:integrationTest`에서 `PUT`을 쓰는 시나리오만 실패한다.
- [x] 1.3 (대화형) 벌크헤드 이름을 `like-toggle`에서 `like-write`로 바꾼다(동작과 값은 그대로, design 결정 9).
  - `IsolatedCalls`(`LIKE_TOGGLE` → `LIKE_WRITE`), `application.yml`, 테스트 설정, `IsolationSettingsTest`, `ResilienceAutoConfigurationTest`, `DownstreamFailureTest`, Grafana 패널 설명.
  - 확인: `./gradlew :feed-service:test :feed-service:integrationTest`가 통과하고 `grep -rn "like-toggle" feed-service docker`가 비어 있다.
- [x] 1.4 (대화형) feed-service 테스트를 델타대로 옮긴다.
  - `DownstreamWireMock`(`stubLikeState`, `stubLikeStateFailure`), `TraceHttp`와 `FeedHttpClient`(`putLike`).
  - `FeedAggregationScenarioTest`: "좋아요 상태 전달"(본문 검증, 토글 API는 없다 포함). `FeedRetryScenarioTest`: "좋아요 상태 변경은 다시 부르지 않는다". `FeedCallFailureLogScenarioTest`, `FeedTraceLogScenarioTest`, `FeedUnhandledExceptionLogScenarioTest`의 토글 호출. `FeedLikeToggleIsolationScenarioTest`는 `FeedLikeWriteIsolationScenarioTest`로 이름을 바꿔 옮긴다.
  - 확인: `./gradlew :feed-service:test`가 통과하고 `:feed-service:integrationTest`에서 `PUT /feed/likes`를 부르는 시나리오만 실패한다.
- [x] 1.5 (대화형) e2e 테스트를 델타대로 고친다.
  - `FeedEndToEndScenarioTest`를 `PUT`으로. `ExperimentVerdictScenarioTest`의 "지금 단계의 기대"를 고도화 6으로.
  - 확인: `./gradlew :e2e:test`가 통과하고 `:e2e:integrationTest`에서 이 둘만 실패한다.
- [x] 1.6 상태 지정, 반복, 늦게 도착한 요청의 시나리오를 옮긴다.
  - `PostLikeStateScenarioTest`(Integration): "좋아요 상태 지정"(일곱), "같은 요청의 반복"(둘), "늦게 도착한 요청"(넷). "어떤 순서로 몇 번이 와도"는 `checkAll`로 임의 순서·중복을 게시글마다 보낸다.
  - 확인: 새 시나리오가 `:like-service:integrationTest`에서 단언 실패(컴파일 오류가 아니다)하고 `:like-service:test`는 통과한다.
- [x] 1.7 동시성, 불일치 검사, 카운터 방식의 시나리오를 옮긴다.
  - `PostLikeConcurrencyScenarioTest`: "동시 요청의 정합성"(둘, 실제 HTTP 포트와 스레드). `PostLikeConsistencyScenarioTest`: "좋아요 수 불일치 검사"(넷, 어긋남은 SQL로 만든다). `PostLikeCounterScenarioTest`: "좋아요 수 갱신 방식"(셋, 속성 `weave.like.counter.strategy`, `weave.like.counter.shards`).
  - 확인: 1.6과 같다.
- [x] 1.8 S-10, S-11 실험 스크립트의 판정·실행 시나리오를 옮긴다.
  - `ExperimentLikeVerdictScenarioTest`: `lib/like-verdict.sh`의 인자와 출력 형식을 KDoc에 정하고 "정합성을 보장하는 방식이 어긋난다", "기준선이 깨지지 않았다"를 픽스처로.
  - `ExperimentLikeRunComposeScenarioTest`(Compose): S-10의 셋, S-11의 "방식별 비교 리포트", "방식 하나만 돌린다", "like-service 설정을 되돌린다". KDoc에 `report.json` 형식을 정하고 짧은 부하 옵션을 쓴다.
  - 확인: `./gradlew :e2e:compileTestKotlin`이 통과하고 판정 테스트는 `:e2e:integrationTest`에서 스크립트가 없어 실패한다. Compose 테스트는 5.3에서 돌린다.

## 2. like-service 상태 저장과 API

- [x] 2.1 스키마를 바꾸고 조회를 맞춘다(design 결정 3, 6).
  - Flyway `V2`: `post_like`에 `liked`(기본 true), `version`(기본 0), `(post_id, liked)` 인덱스. `post_like_count`에 `shard`(기본 0), 기본 키 `(post_id, shard)`. 엔티티를 맞추고, 배치 조회는 `liked = true`와 `SUM ... GROUP BY post_id`로 한다.
  - 확인: `PostLikeScenarioTest`의 "좋아요 배치 조회" 시나리오가 통과한다(GIVEN의 쓰기는 임시로 실패하므로 `PUT`이 필요 없는 것만). `./gradlew :like-service:compileKotlin`이 통과한다.
- [ ] 2.2 상태 전이 판단을 만든다(design 결정 2).
  - 지금 상태와 요청으로 "반영할지, 수를 얼마나 바꿀지"를 돌려주는 순수 함수.
  - 확인: 단위 테스트 `LikeIntentTest`(`checkAll`: 임의 순서·중복의 최종 상태가 가장 큰 `version`의 값, 수의 합이 최종 상태와 같다)가 통과한다.
- [ ] 2.3 `PUT /likes/{postId}`를 만들고 토글을 지운다(design 결정 1, 4).
  - READ COMMITTED 트랜잭션, `ON DUPLICATE KEY UPDATE`로 잠금, 원자적 UPDATE 카운터(방식 전환은 3.1). `POST /likes/{postId}/toggle`과 `toggle` 메서드를 지운다.
  - 확인: `PostLikeScenarioTest`, `PostLikeStateScenarioTest`, `PostLikeConcurrencyScenarioTest`, `TestLikeServiceApplicationTest`가 통과한다.
- [ ] 2.4 불일치 검사 엔드포인트를 만든다(design 결정 7).
  - `likeconsistency` Actuator 엔드포인트, `application-experiment.yml` 노출 목록, 100건 제한.
  - 확인: `PostLikeConsistencyScenarioTest`가 통과한다.
- [ ] 2.5 아키텍처 8장과 `like-service/CLAUDE.md`를 구현에 맞춘다.
  - 8장의 표와 "다룰 것"을 정한 것으로 바꾼다. 유니크 위반을 쓰지 않는 이유를 적는다.
  - 확인: 문서의 열·엔드포인트 이름이 코드에 있다(`grep`).

## 3. 카운터 방식

- [ ] 3.1 갱신 방식을 설정으로 고르게 한다(design 결정 6).
  - `read-modify-write`, `atomic`, `sharded`와 `weave.like.counter.*` 속성 구조. 잘못된 값과 `shards` < 1은 시작할 때 실패한다. 값은 기본 `atomic`, 분할 수는 6.2에서 넣는다.
  - 확인: `PostLikeCounterScenarioTest`가 통과하고, 세 방식의 칸 선택과 갱신을 보는 단위 테스트가 통과한다.
- [ ] 3.2 아키텍처 8장에 세 방식, 칸 선택, 조회 합계를 적는다.
  - 확인: 문서의 속성 이름이 코드에 있다(`grep`).

## 4. feed-service 전달

- [ ] 4.1 `PUT /feed/likes/{postId}`를 만들고 토글을 지운다(design 결정 5).
  - `FeedController`(본문 검증), `LikeClient`, `LikeGateway`(`callBlocking("like", LIKE_WRITE)`). `POST /feed/likes/{postId}/toggle`을 지운다.
  - 확인: `./gradlew :feed-service:test :feed-service:integrationTest`와 `:e2e:integrationTest`의 `FeedEndToEndScenarioTest`가 통과한다.
- [ ] 4.2 아키텍처 4-2, 5장과 feed-service `CLAUDE.md`의 토글 설명을 상태 변경으로 고치고, 다시 부르지 않는 이유를 적는다.
  - 확인: `grep -n "토글" docs/architecture.md feed-service/CLAUDE.md`에 MVP 기록 말고 남은 것이 없다.

## 5. 실험 스크립트

스크립트는 `experiments/CLAUDE.md`의 bash 3.2, 신호, 표준 출력 규칙을 따른다.

- [ ] 5.1 S-10을 만든다(design 결정 10).
  - `compose.yaml`의 `LIKE_SPRING_APPLICATION_JSON`, `experiments/like-config.json`, `k6/like-s10.js`, `run-like.sh S-10`(리포트, 장애 되돌리기 trap).
  - 확인: `/bin/bash -n experiments/run-like.sh`와 `docker compose run --rm -T --no-deps k6 inspect k6/like-s10.js`가 통과한다.
- [ ] 5.2 S-11을 만든다(design 결정 10).
  - `k6/like-s11.js`, `run-like.sh S-11 [--strategy] [--rps] [--warmup] [--duration]`, `lib/like-verdict.sh`, 잠금 대기·교착 읽기, HikariCP 대기·CPU 기록, 방식별 재기동과 복원.
  - 확인: `ExperimentLikeVerdictScenarioTest`가 통과하고 `/bin/bash -n`과 k6 `inspect`가 통과한다.
- [ ] 5.3 Compose 테스트를 돌리고 아키텍처 9-1, 9-3과 `conventions/harness.md` 5장에 명령을 적는다.
  - 확인: `./gradlew :e2e:composeTest`가 통과한다(`ExperimentLikeRunComposeScenarioTest` 포함).

## 6. 측정과 설정값

실측이라 대화형으로 한다. 값마다 커밋을 나누고 근거가 된 측정값을 커밋 본문에 적는다([커밋 컨벤션](../../../conventions/commit.md)).

- [ ] 6.1 S-11의 부하 크기를 정한다(design 결정 10).
  - `atomic`으로 RPS를 단계적으로 올려 p99, 행 잠금 대기, like-service·MySQL CPU를 재고 `like-config.json`에 값을 넣는다.
  - 확인: `docs/experiments/idempotent-like.md`에 단계별 표와 고른 이유가 있다.
- [ ] 6.2 세 방식을 비교하고 분할 수를 정한다.
  - `run-like.sh S-11`로 `read-modify-write`, `atomic`, `sharded` 4·8·16을 돌린다.
  - 확인: 결과 문서에 방식별 처리량, p99, 실패, 잠금 대기, 교착, 수 불일치 표가 있고 `atomic`·`sharded`의 불일치와 교착이 0이다. 교착이 있으면 멈추고 원인을 본다(design 결정 4).
- [ ] 6.3 기본 방식을 정한다.
  - `weave.like.counter.*` 기본값만 바꾸는 커밋.
  - 확인: `./gradlew :like-service:integrationTest`가 통과하고 결과 문서에 고른 이유가 있다.
- [ ] 6.4 `like-write` 상한과 like 커넥션 풀을 다시 정한다(design 결정 9).
  - 리틀의 법칙으로 계산해 `application.yml`만 바꾸는 커밋. 아키텍처 5-6의 근거를 고친다. `IsolationSettingsTest`가 값을 단언하므로 대화형으로 함께 고친다.
  - 확인: `./gradlew :feed-service:test`가 통과하고 5-6 표의 값과 `application.yml`이 같다.
- [ ] 6.5 S-10을 돌려 기록한다.
  - 확인: `experiments/run-like.sh S-10`이 0으로 끝나고 결과 문서에 네 경우의 표가 있다.

## 7. 실험 세트와 PRD

- [ ] 7.1 `experiments/expectations.json`의 단계를 고도화 6으로 바꾼다.
  - 확인: `ExperimentVerdictScenarioTest`의 "지금 단계의 기대"가 통과한다.
- [ ] 7.2 장애 실험 세트를 돌려 기록한다(design 결정 12).
  - 확인: `experiments/run-all.sh`가 0으로 끝나고 결과 문서에 고도화 5와의 비교 표가 있다.
- [ ] 7.3 PRD와 README를 고친다.
  - F-003 수용 기준 체크, F-005의 "F-003에서 정한다"를 결정 5로, 10장에 정한 것(순서 판단, S-11 부하, 분할 수, 쓰기 재시도), 남은 것 정리. 아키텍처 12·13장, README의 고도화 6 줄과 "남은 것".
  - 확인: PRD 6장의 F-003 항목이 모두 체크되고 `grep -n "고도화 6" README.md`의 "예정"이 남아 있지 않다.

## 8. 검토와 완료 조건

- [ ] 8.1 자동 검사를 모두 돌린다.
  - 확인: `./gradlew ktlintCheck build`와 `openspec validate --all --strict --no-interactive`가 통과한다.
- [ ] 8.2 구현에 참여하지 않은 리뷰어가 검토 루프를 돌린다(하네스 규칙 3-3).
  - 확인: 8개 항목을 확인했고 막는 지적이 없다. 있으면 작업을 더해 고친다.
- [ ] 8.3 change 완료 조건을 확인한다(하네스 규칙 4장).
  - 확인: 체크리스트를 모두 채웠고 실험 세트 결과를 PR 설명에 넣을 요약이 있다.

## Workflow follow-up

- change 브랜치에서 `/opsx:archive`로 스펙을 합친다. `openspec/specs/post-like/spec.md`의 Purpose에 있는 "정합성은 고도화 6에서 다룬다"를 직접 고친다.
- `develop`으로 PR을 열고 CI 통과 뒤 merge한다.
- `develop` → `main` 릴리스 뒤 `v0.7.0` 태그를 붙인다. 고도화 1~6이 끝나므로 `v1.0.0`과 PRD 고정 시점도 그때 정한다.
