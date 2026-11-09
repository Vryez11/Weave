// S-10: 같은 좋아요 요청의 중복·동시·순서 뒤바뀜과 타임아웃 뒤 재전송 (F-003, F-005, add-idempotent-like design 결정 10).
//
// 부하가 아니라 경우 네 개를 차례로 돈다. run-like.sh가 부르고, 결과를 OUTPUT 파일(cases.json)에 쓴다. 형식은
// e2e ExperimentLikeRunComposeScenarioTest KDoc의 `cases`다. 모두 setup()에서 돌려 그 반환값을 handleSummary로 받는다.
// 요청은 feed-service를 거치고, 반영됐는지와 최종 상태는 Compose 네트워크 안에서 like-service를 직접 본다.
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { FaultSchedule } from './faults.js';

const FEED_URL = __ENV.FEED_URL || 'http://feed-service:8080';
const LIKE_URL = __ENV.LIKE_URL || 'http://like-service:8083';
const OUTPUT = __ENV.OUTPUT;

// like 응답 타임아웃(100ms)보다 긴 응답 지연. feed-service는 실패를 돌려주고 like-service는 반영한다
const TOXIC_LATENCY_MS = 300;
// 지연을 넣어 둔 채로 기다리는 시간. 그동안 반영 여부를 보고, 멈춤 신호를 받은 뒤의 되돌리기를 시험할 틈을 둔다
const FAULT_HOLD_SECONDS = 3;
const USER_ID = 1;

export const options = {
  vus: 1,
  iterations: 1,
  setupTimeout: '180s',
  batch: 20,
  batchPerHost: 20,
};

function request(postId, liked, version) {
  return [
    'PUT',
    `${FEED_URL}/feed/likes/${postId}`,
    JSON.stringify({ liked, version }),
    { headers: { 'Content-Type': 'application/json', 'X-User-Id': String(USER_ID) }, timeout: '5s' },
  ];
}

function put(postId, liked, version) {
  return http.request(...request(postId, liked, version));
}

function state(postId) {
  const res = http.get(`${LIKE_URL}/likes?postIds=${postId}`, { headers: { 'X-User-Id': String(USER_ID) } });
  if (res.status !== 200) {
    exec.test.abort(`like-service 조회 실패: ${res.status} ${res.body}`);
  }
  const { liked, likeCount } = res.json('likes')[0];
  return { liked, likeCount };
}

function mismatches(postId) {
  const res = http.get(`${LIKE_URL}/actuator/likeconsistency?postId=${postId}`);
  if (res.status !== 200) {
    exec.test.abort(`불일치 검사 실패: ${res.status} ${res.body}`);
  }
  return res.json('mismatches').length;
}

function judge(name, postId, expected, ok, extra) {
  const actual = state(postId);
  const count = mismatches(postId);
  const passed = ok && actual.liked === expected.liked && actual.likeCount === expected.likeCount && count === 0;
  return { name, passed, expected, actual, mismatches: count, ...extra };
}

/** 막 뜬 feed-service의 첫 호출이 응답 타임아웃에 걸려 결과를 흐리지 않게 데운다. 결과는 보지 않는다. */
function warmUp(postId) {
  for (let version = 1; version <= 30; version += 1) {
    put(postId, version % 2 === 1, version);
  }
}

function duplicate(postId) {
  const statuses = [];
  for (let attempt = 0; attempt < 5; attempt += 1) {
    statuses.push(put(postId, true, 1).status);
  }
  return judge('duplicate', postId, { liked: true, likeCount: 1 }, statuses.includes(200), { statuses });
}

function concurrent(postId) {
  const requests = [];
  for (let attempt = 0; attempt < 20; attempt += 1) {
    requests.push(request(postId, true, 1));
  }
  const statuses = http.batch(requests).map((res) => res.status);
  return judge('concurrent', postId, { liked: true, likeCount: 1 }, statuses.includes(200), { statuses });
}

function reorder(postId) {
  const statuses = [put(postId, true, 1).status, put(postId, false, 2).status, put(postId, true, 1).status];
  return judge('reorder', postId, { liked: false, likeCount: 0 }, statuses.includes(200), { statuses });
}

function timeoutResend(postId) {
  const schedule = new FaultSchedule(1);
  if (!schedule.apply({ action: 'latency', target: 'like', latencyMs: TOXIC_LATENCY_MS })) {
    exec.test.abort('like 프록시에 지연을 넣지 못했다');
  }
  const firstStatus = put(postId, true, 1).status;
  sleep(FAULT_HOLD_SECONDS);
  const appliedBeforeResend = state(postId).liked === true;
  if (!schedule.apply({ action: 'clear', target: 'like' })) {
    exec.test.abort('like 프록시의 지연을 거두지 못했다');
  }

  // 클라이언트는 성공할 때까지 같은 version으로 다시 보낸다
  let resendStatus = 0;
  for (let attempt = 0; attempt < 3 && resendStatus !== 200; attempt += 1) {
    resendStatus = put(postId, true, 1).status;
  }
  const ok = firstStatus >= 500 && appliedBeforeResend && resendStatus === 200;
  return judge('timeout-resend', postId, { liked: true, likeCount: 1 }, ok, { firstStatus, appliedBeforeResend, resendStatus });
}

export function setup() {
  // 실행마다 새 게시글을 써 앞 실행의 version이 섞이지 않게 한다
  const base = Date.now();
  warmUp(base);
  return {
    cases: [duplicate(base + 1), concurrent(base + 2), reorder(base + 3), timeoutResend(base + 4)],
  };
}

export default function () {}

export function handleSummary(data) {
  return { [OUTPUT]: JSON.stringify({ cases: data.setup_data.cases }) };
}
