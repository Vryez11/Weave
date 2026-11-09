// S-11: 인기 글 몰림 (F-003, add-idempotent-like design 결정 10).
//
// like-service를 직접 부른다. feed-service의 100ms 상한과 쓰기 상한이 비교할 대상을 잘라 버리기 때문이다. 반복마다 새 사용자가 눌러
// 모든 요청이 실제 수 갱신이 되게 한다. setup()에서 앱 수준 지연을 0으로 두고(DB 지연만 본다) 게시글을 한 번 눌러 카운터 행을 만들어,
// 첫 INSERT 경쟁을 비교에서 뺀다. run-like.sh가 준비와 측정을 따로 돌려 측정 구간의 잠금 대기만 잰다.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const LIKE_URL = __ENV.LIKE_URL || 'http://like-service:8083';
const POST_ID = __ENV.POST_ID;
const RPS = Number(__ENV.RPS);
const DURATION = __ENV.DURATION;
const TIMEOUT_SECONDS = Number(__ENV.TIMEOUT_SECONDS);

// 반복의 사용자 ID(1부터)와 겹치지 않게 큰 값을 쓴다
const PRIMING_USER_ID = 9000000000000;

const latency = new Trend('like_latency', true);
const failed = new Counter('like_failed');
// http_reqs는 setup()의 요청까지 세므로 부하 요청만 따로 센다
const requests = new Counter('like_requests');

// 요청 시간 초과까지 모두 묶여도 보낼 수 있는 가상 사용자 수(feed.js와 같은 계산)
const vus = Math.ceil(RPS * TIMEOUT_SECONDS * 1.1);

export const options = {
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: vus,
      maxVUs: vus,
      gracefulStop: `${TIMEOUT_SECONDS + 1}s`,
    },
  },
  setupTimeout: '60s',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

function put(userId) {
  return http.put(`${LIKE_URL}/likes/${POST_ID}`, JSON.stringify({ liked: true, version: 1 }), {
    headers: { 'Content-Type': 'application/json', 'X-User-Id': String(userId) },
    timeout: `${TIMEOUT_SECONDS}s`,
  });
}

export function setup() {
  const faults = http.post(`${LIKE_URL}/actuator/faults`, JSON.stringify({ latencyMedianMs: 0, latencyP99Ms: 0 }), {
    headers: { 'Content-Type': 'application/json' },
  });
  if (faults.status !== 200) {
    exec.test.abort(`앱 수준 지연을 끄지 못했다: ${faults.status} ${faults.body}`);
  }
  const primed = put(PRIMING_USER_ID);
  if (primed.status !== 200) {
    exec.test.abort(`카운터 행을 만들지 못했다: ${primed.status} ${primed.body}`);
  }
}

export default function () {
  const started = Date.now();
  const res = put(exec.scenario.iterationInTest + 1);
  latency.add(Date.now() - started);
  requests.add(1);
  failed.add(res.status === 200 ? 0 : 1);
}
