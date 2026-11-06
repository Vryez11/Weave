// 피드 부하·장애 시간표·판정 (design 결정 5).
//
// 환경 변수(run.sh가 넘긴다. 없으면 config.json 값):
//   SCENARIO  시나리오 ID(필수). scenarios/<ID>.json을 읽는다
//   RPS, WARMUP, DURATION  초당 요청 수, 준비 시간, 측정 시간(k6 시간 형식. 예: 5s, 1m, 1m30s)
//   FEED_URL, TOXIPROXY_URL  Compose 네트워크 안의 주소(기본 http://feed-service:8080, http://toxiproxy:8474)
//
// k6 시나리오 세 개:
//   warmup   준비 시간 동안 같은 RPS. 판정과 리포트에 들어가지 않는다
//   measure  준비 시간 뒤 측정 시간 동안 같은 RPS. threshold는 이 시나리오의 태그로만 건다
//   faults   가상 사용자 1명. measure와 함께 시작해 시간표대로 장애를 넣고 거둔다
// setup()과 teardown()이 장애를 모두 되돌린다(reset.js와 같은 함수).
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { FaultSchedule, resetAll, sortByTime, validate } from './faults.js';
import { missingSections } from './contract.js';

const config = JSON.parse(open('../config.json'));
const slo = JSON.parse(open('../slo.json'));

const scenarioId = __ENV.SCENARIO;
if (!scenarioId) {
  throw new Error('SCENARIO 환경 변수(시나리오 ID)가 없다');
}
const scenario = JSON.parse(open(`../scenarios/${scenarioId}.json`));

const rps = Number(__ENV.RPS || config.rps);
const warmup = __ENV.WARMUP || config.warmup;
const duration = __ENV.DURATION || config.duration;
const requestTimeout = config.requestTimeout;
const feedUrl = `${__ENV.FEED_URL || 'http://feed-service:8080'}/feed`;

/** k6 시간 형식(`1m30s`, `500ms`, `5s`)을 초로 바꾼다. */
function seconds(value) {
  const units = { ms: 0.001, s: 1, m: 60, h: 3600 };
  const parts = String(value).match(/\d+(\.\d+)?(ms|s|m|h)/g);
  if (!parts || parts.join('') !== String(value)) {
    throw new Error(`시간 형식이 아니다: ${value}`);
  }
  return parts.reduce((sum, part) => {
    const [, number, , unit] = part.match(/^(\d+(\.\d+)?)(ms|s|m|h)$/);
    return sum + Number(number) * units[unit];
  }, 0);
}

if (!(rps > 0)) {
  throw new Error(`RPS가 양수가 아니다: ${rps}`);
}
const durationSeconds = seconds(duration);
const timeoutSeconds = seconds(requestTimeout);
seconds(warmup);

// 리틀의 법칙: 동시에 떠 있는 요청은 최대 RPS × 요청 시간 초과. 10%를 더해 처음부터 다 만든다(design 결정 5)
const vus = Math.ceil(rps * timeoutSeconds * 1.1);

const faultSchedule = sortByTime(scenario.faults || []);
validate(faultSchedule, rps);

const load = {
  executor: 'constant-arrival-rate',
  rate: rps,
  timeUnit: '1s',
  preAllocatedVUs: vus,
  maxVUs: vus,
  exec: 'feed',
};

export const options = {
  scenarios: {
    // 준비 구간이 끝날 때 남은 요청은 기다리지 않는다. 기다리면 measure와 겹쳐 가상 사용자를 두 배로 만든다
    warmup: { ...load, duration: warmup, gracefulStop: '0s' },
    measure: { ...load, startTime: warmup, duration, gracefulStop: `${timeoutSeconds + 1}s` },
    faults: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      startTime: warmup,
      maxDuration: duration,
      gracefulStop: '10s',
      exec: 'faults',
    },
  },
  thresholds: {
    // 판정 기준은 slo.json(PRD 2장)뿐이다. 시나리오마다 바꾸지 않는다(design 결정 8)
    [`http_req_duration{scenario:measure}`]: [`p(99)<${slo.p99Ms}`],
    [`availability{scenario:measure}`]: [`rate>=${slo.availability}`],
    // 측정 구간의 요청 수와 보내지 못한 요청 수를 요약에 남기려는 것이다. 늘 통과한다
    [`http_reqs{scenario:measure}`]: ['count>=0'],
    [`dropped_iterations{scenario:measure}`]: ['count>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

// 200을 받은 요청 비율. 200이 아닌 응답, 연결 실패, 요청 시간 초과는 실패다.
// http_req_failed는 장애 요청(Toxiproxy·Actuator)도 섞이므로 피드 요청만 따로 센다
const availability = new Rate('availability');
// 응답에서 빠진 영역(contract.js). 의존성 이름을 `section` 태그로 붙인다
const degraded = new Counter('degraded_sections');

export function setup() {
  if (!resetAll()) {
    exec.test.abort('setup: 장애를 되돌리지 못했다');
  }
}

export function feed() {
  const userId = config.userIds.min + Math.floor(Math.random() * (config.userIds.max - config.userIds.min + 1));
  const res = http.get(feedUrl, { headers: { 'X-User-Id': String(userId) }, timeout: requestTimeout });
  availability.add(res.status === 200);
  missingSections(res).forEach((section) => degraded.add(1, { section }));
}

export function faults() {
  const schedule = new FaultSchedule(rps);
  const start = Date.now();
  for (const fault of faultSchedule) {
    if (fault.at >= durationSeconds) {
      // 측정 시간을 줄여 돌리면 측정 구간 밖의 항목은 넣지 않는다(리포트에 "건너뜀", design 결정 9)
      break;
    }
    const wait = fault.at - (Date.now() - start) / 1000;
    if (wait > 0) {
      sleep(wait);
    }
    schedule.apply(fault);
  }
}

export function teardown() {
  if (!resetAll()) {
    exec.test.abort('teardown: 장애를 되돌리지 못했다');
  }
}
