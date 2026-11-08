# 진행 메모: add-retry-policy

## 알아 둘 패턴
- 셸의 `ls`는 eza 별칭이라 경로 인자에서 깨진다. `command ls`를 쓴다.
- 테스트 기본 설정(`feed-service/src/test/resources/config/application.yml`)은 재시도를 끈다(재시도 인스턴스 넷 `max-attempts: 1`, design 결정 12). 재시도를 보는 시나리오만 속성으로 켠다.

## 반복 기록
