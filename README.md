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

각 모듈은 독립적으로 띄울 수 있는 Spring Boot 애플리케이션이다. 공통 빌드 규칙은 `buildSrc`의 `weave.spring-service` 컨벤션 플러그인에 있다.

| 모듈 | 포트 | 역할 |
|---|---|---|
| `feed-service` | 8080 | 피드 집계 (하위 서비스 호출 · 장애 격리) |
| `post-service` | 8081 | 게시글 |
| `user-service` | 8082 | 작성자 프로필 |
| `like-service` | 8083 | 좋아요 (상태 기반 · 멱등) |
| `recommendation-service` | 8084 | 추천 |

## 실행

```bash
./gradlew :feed-service:bootRun
```

모듈 이름만 바꿔서 각각 띄운다. 헬스 체크: `GET /actuator/health`

## 진행 상황

멀티 모듈 뼈대 구성 완료.
