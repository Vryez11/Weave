# Spec Delta

## Purpose

ad-service가 피드의 광고 영역에 들어갈 광고를 내준다. 우리가 통제할 수 없는 외부 API 역할을 하며, 피드에서 선택 등급 의존성이다([PRD](../../../../../../docs/prd.md) 3장).

## ADDED Requirements

### Requirement: 광고 목록 조회 (F-006)

ad-service는 `GET /ads`에 광고 목록을 돌려줘야 한다(SHALL).

- 광고마다 `id`, `title`, `imageUrl`, `landingUrl`이 있다.
- `size` 파라미터로 개수를 정한다. 기본값은 2이고 1~5만 받는다.

#### Scenario: 기본 개수로 조회

- **WHEN** `GET /ads`를 부른다
- **THEN** 200과 함께 광고 2개가 오고, 광고마다 `id`, `title`, `imageUrl`, `landingUrl`이 있다

#### Scenario: 범위를 벗어난 개수

- **WHEN** `size`를 0이나 6으로 `GET /ads`를 부른다
- **THEN** 400이 온다

### Requirement: 고정 광고 데이터 (F-006)

ad-service는 시작할 때 정해진 광고 데이터를 만들고, 같은 버전이면 다시 띄워도 같은 데이터로 응답해야 한다(SHALL).

#### Scenario: 다시 띄워도 같은 데이터

- **WHEN** ad-service를 두 번 따로 띄워 각각 `GET /ads?size=5`를 부른다
- **THEN** 두 응답이 같다
