# weave

피드 집계 서비스 장애 격리 시스템

여러 하위 서비스에서 데이터를 모아 피드를 구성할 때, 일부 하위 서비스의 장애가 전체 장애로 번지지 않도록 격리한다.
외부 API 장애 대응(서킷 브레이커, 타임아웃, 재시도)을 실무 수준으로 설계하고 검증하는 것이 목표다.

## 핵심 과제

- **하위 서비스 장애 격리**: Resilience4j 서킷 브레이커 / 벌크헤드로 느리거나 죽은 하위 서비스를 격리
- **의존성 등급별 Graceful Degradation**: 하위 서비스를 필수/선택 등급으로 나누고, 등급에 따라 실패 시 응답 전략을 다르게 설계
- **좋아요 정합성**: 상태 기반 API와 멱등성 설계로 재시도·중복 요청에도 좋아요 상태를 정확하게 유지

## 기술 스택

Kotlin 2.3 · Spring Boot 4.1 · JDK 25 · Gradle 9 (Kotlin DSL)

## 모듈 구성

각 서비스는 독립적으로 띄울 수 있는 Spring Boot 애플리케이션이다. 공통 빌드 규칙은 `buildSrc`의 컨벤션 플러그인에 있다 (`weave.spring-service`, `weave.spring-library`).

| 모듈 | 포트 | 역할 |
|---|---|---|
| `feed-service` | 8080 | 피드 집계 (하위 서비스 호출 · 장애 격리) |
| `post-service` | 8081 | 게시글 |
| `user-service` | 8082 | 작성자 프로필 |
| `like-service` | 8083 | 좋아요 (상태 기반 · 멱등) |
| `recommendation-service` | 8084 | 추천 |
| `ad-service` | 8085 | 광고. 통제할 수 없는 외부 API 역할 (429 · Retry-After · 쿼터) |

| 라이브러리 | 역할 |
|---|---|
| `libs/observability` | 모든 서비스에 자동 적용되는 공통 로깅·트레이싱 설정 |
| `libs/fault-injection` | 하위 서비스 5개의 앱 수준 장애 주입(지연 분포, 에러율). `experiment` 프로필에서만 켠다 |

| 테스트 모듈 | 역할 |
|---|---|
| `e2e` | 서비스 6개를 bootJar 프로세스로 띄우는 종단 간 테스트 (`./gradlew :e2e:integrationTest`) |

비즈니스 DTO를 공유하는 공통 모듈은 두지 않는다. 서비스끼리는 HTTP 계약으로만 통신한다.

## 규칙

`conventions/`에 커밋, 브랜치, 스펙, 하네스 규칙이 있다.

## 실행

### 전체 (Docker Compose)

서비스 6개, MySQL, Toxiproxy, 관찰 인프라(Alloy, Loki, Tempo, Prometheus, Grafana)를 함께 띄운다. Docker가 필요하다. feed-service는 하위 서비스를 늘 Toxiproxy를 거쳐 부른다.

```bash
cp .env.example .env   # MySQL 계정(LIKE_DB_USERNAME, LIKE_DB_PASSWORD, MYSQL_ROOT_PASSWORD)을 채운다
./gradlew bootJar
docker compose up -d --build --wait
```

| 주소 | 무엇 |
|---|---|
| `http://localhost:8080/feed?size=10` (헤더 `X-User-Id: 1`) | 피드. 응답 헤더 `X-Trace-Id`가 그 요청의 traceId다 |
| `http://localhost:3000` | Grafana. Explore에서 Loki로 `{service=~".+"} \| traceId="<traceId>"`를 조회하고, 로그의 traceId 링크로 Tempo 트레이스를 연다 |
| `http://localhost:3000/d/weave-feed` | Grafana 피드 실험 대시보드 (피드 SLO, 의존성별 호출, 자원 사용량) |
| `http://localhost:9090` | Prometheus |
| `http://localhost:8474` | Toxiproxy 관리 API |

피드, 관찰 인프라, Toxiproxy 포트는 모두 `127.0.0.1`에만 열린다. 내릴 때는 `docker compose down -v`.

### 장애 실험

위의 Compose 환경을 띄운 뒤 돌린다. 호스트에 bash, `jq`, `gzip`, `docker compose`가 있어야 한다.

```bash
experiments/run.sh S-01 --rps 5 --warmup 5s --duration 20s   # 시나리오 하나를 짧게 (약 40초)
experiments/run.sh S-02                                      # 기본값(experiments/config.json)으로 (약 7분)
experiments/run-all.sh                                       # S-01~S-09 (약 1시간)
```

`run.sh`는 장애를 되돌리고 feed-service를 다시 띄운 뒤, k6로 부하를 주며 시나리오의 장애를 넣고 거둔다. 끝나면(멈춰도) 장애를 모두 되돌리고 `experiments/results/<시각>-<ID>/report.md`에 리포트를 남긴다. 종료 코드는 0 기대와 같음, 1 기대와 다름, 2 실행하지 못함이다. `run-all.sh`가 0으로 끝나면 장애 실험 세트가 통과한 것이다. 시나리오와 리포트 항목은 [아키텍처](docs/architecture.md) 9장에 있다.

### 서비스 하나

```bash
./gradlew :feed-service:bootRun
```

모듈 이름만 바꿔서 각각 띄운다. like-service는 MySQL이 필요하므로 `./gradlew :like-service:bootTestRun`으로 Testcontainers MySQL과 함께 띄운다(Docker 필요). 헬스 체크: `GET /actuator/health`

### 테스트

| 명령 | 무엇 |
|---|---|
| `./gradlew ktlintCheck build` | 린트, 단위·통합 테스트, 서비스 6개를 프로세스로 띄우는 종단 간 테스트 |
| `./gradlew :e2e:composeTest` | Compose 환경을 띄워 traceId 조회, 프록시 경로, 대시보드, 장애 실험 명령을 짧게 확인하고 내린다 |

## 진행 상황

멀티 모듈 뼈대 구성 완료.
