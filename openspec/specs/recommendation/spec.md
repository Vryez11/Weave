# recommendation Specification

## Purpose

recommendation-service가 피드의 추천 섹션에 들어갈 게시글 목록을 내준다. 피드에서 선택 등급 의존성이다([PRD](../../../docs/prd.md) 3장).

## Requirements

### Requirement: 추천 목록 조회 (F-006)

recommendation-service는 `GET /recommendations`에 추천 항목 목록을 돌려줘야 한다(SHALL).

- 항목마다 `postId`, `title`, `reason`이 있다.
- `size` 파라미터로 개수를 정한다. 기본값은 5이고 1~20만 받는다.
- `X-User-Id` 헤더는 받아도 되고 없어도 된다. 추천 결과는 사용자와 관계없이 같다(개인화는 PRD 8장에서 제외).

#### Scenario: 기본 개수로 조회

- **WHEN** `GET /recommendations`를 부른다
- **THEN** 200과 함께 항목 5개가 오고, 항목마다 `postId`, `title`, `reason`이 있다

#### Scenario: 사용자와 관계없다

- **WHEN** `X-User-Id: 1`, `X-User-Id: 2`, 헤더 없이 각각 `GET /recommendations`를 부른다
- **THEN** 세 응답이 같다

#### Scenario: 범위를 벗어난 개수

- **WHEN** `size`를 0이나 21로 `GET /recommendations`를 부른다
- **THEN** 400이 온다

### Requirement: 고정 추천 데이터 (F-006)

recommendation-service는 시작할 때 정해진 추천 데이터를 만들고, 같은 버전이면 다시 띄워도 같은 데이터로 응답해야 한다(SHALL).

#### Scenario: 다시 띄워도 같은 데이터

- **WHEN** recommendation-service를 두 번 따로 띄워 각각 `GET /recommendations?size=20`을 부른다
- **THEN** 두 응답이 같다
