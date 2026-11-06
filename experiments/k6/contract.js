// 피드 응답에서 빠진 영역 찾기 (design 결정 5, 7).
//
// 부분 응답(degrade)에서 빠진 영역을 의존성 이름(post, user, like, recommendation, ad)으로 돌려준다.
// feed.js가 `degraded_sections` 카운터에 `section` 태그로 센다.
//
// MVP 계약에는 부분 응답이 없다. 하위 서비스 하나라도 실패하면 피드 전체가 500이고, 200 응답에는 모든 영역이 있다.
// 그래서 늘 빈 배열이고 리포트의 degrade 비율은 "없음"이다. 부분 응답을 도입하는 change(F-002)가
// 응답에 빠진 영역 표시를 정하면 여기서 읽는다.
export const PARTIAL_RESPONSE = false;

export function missingSections(res) {
  return [];
}
