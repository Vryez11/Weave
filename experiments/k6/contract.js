// 피드 응답에서 대체된 영역 찾기 (add-fault-experiments design 결정 5, 7, add-graceful-degradation design 결정 2, 12).
//
// 고도화 4(F-002)부터 중요·선택 등급의 의존성이 실패해도 피드는 200이고, 응답의 `degradation`이 대체한 영역을 알린다.
//   "degradation": {"missing": ["recommendation"], "stale": [{"section": "post", "cachedAt": "..."}]}
// 빠진 영역(missing)과 stale 캐시로 채운 영역(stale[].section)을 합쳐 의존성 이름(post, user, like, recommendation, ad)으로
// 돌려준다(중복 없이). user를 로컬 캐시로 모두 채운 응답은 둘 다에 없어 degrade가 아니다.
// feed.js가 `degraded_sections` 카운터에 `section` 태그로 세고, 하나라도 있으면 `degraded_responses`로 센다.
// 200이 아닌 응답과 본문을 읽지 못한 응답은 빈 배열이다(가용성에서 실패로 센다).
export const PARTIAL_RESPONSE = true;

export const SECTIONS = ['post', 'user', 'like', 'recommendation', 'ad'];

export function degradedSections(res) {
  if (res.status !== 200) {
    return [];
  }
  let degradation;
  try {
    degradation = res.json('degradation');
  } catch (e) {
    return [];
  }
  if (!degradation) {
    return [];
  }
  const missing = Array.isArray(degradation.missing) ? degradation.missing : [];
  const stale = Array.isArray(degradation.stale) ? degradation.stale.map((entry) => entry && entry.section) : [];
  const sections = [];
  for (const section of missing.concat(stale)) {
    if (SECTIONS.includes(section) && !sections.includes(section)) {
      sections.push(section);
    }
  }
  return sections;
}
