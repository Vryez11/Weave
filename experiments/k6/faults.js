// 장애 넣기·거두기·되돌리기 (design 결정 5, 6).
// feed.js의 faults 시나리오·setup()·teardown()과 reset.js가 함께 쓴다. k6는 Compose 네트워크 안에서 돌아
// Toxiproxy 관리 API와 하위 서비스 Actuator를 서비스 이름으로 부른다.
//
// 장애 요청에는 모두 `kind: fault`, `action`, `target` 태그를 붙인다. lib/windows.sh와 리포트가 k6 원본에서
// 이 태그로 넣은 시각과 거둔 시각을 읽는다. 되돌리기는 `action: reset`이다.
import http from 'k6/http';

const TOXIPROXY_URL = __ENV.TOXIPROXY_URL || 'http://toxiproxy:8474';

// 의존성 이름 → 하위 서비스 주소. Toxiproxy 프록시 이름도 의존성 이름이다(docker/toxiproxy/toxiproxy.json)
export const SERVICES = {
  post: 'http://post-service:8081',
  user: 'http://user-service:8082',
  like: 'http://like-service:8083',
  recommendation: 'http://recommendation-service:8084',
  ad: 'http://ad-service:8085',
};

// 넣은 톡식은 거둘 때 이름으로 지운다
const TOXIC_NAMES = { latency: 'weave_latency', timeout: 'weave_timeout' };

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function request(method, url, body, action, target) {
  const params = { headers: JSON_HEADERS, tags: { kind: 'fault', action, target } };
  const res = http.request(method, url, body === null ? null : JSON.stringify(body), params);
  const ok = res.status >= 200 && res.status < 300;
  if (!ok) {
    console.error(`장애 요청 실패: ${action} ${target} ${method} ${url} → ${res.status} ${res.body}`);
  }
  return ok;
}

function proxyUrl(target) {
  return `${TOXIPROXY_URL}/proxies/${target}`;
}

function actuatorUrl(target, endpoint) {
  const base = SERVICES[target];
  if (!base) {
    throw new Error(`알 수 없는 의존성: ${target}`);
  }
  return `${base}/actuator/${endpoint}`;
}

/** 장애 하나의 요청을 만든다. 거둘 때 쓰려고 넣은 요청의 반대 요청도 함께 돌려준다. */
function plan(fault, rps) {
  const { action, target } = fault;
  switch (action) {
    case 'latency':
      return {
        inject: ['POST', `${proxyUrl(target)}/toxics`, {
          name: TOXIC_NAMES.latency, type: 'latency', stream: 'downstream', toxicity: 1,
          attributes: { latency: fault.latencyMs, jitter: 0 },
        }],
        undo: ['DELETE', `${proxyUrl(target)}/toxics/${TOXIC_NAMES.latency}`, null],
      };
    case 'timeout':
      // timeout 0: 연결을 받고 응답을 흘리지 않으며 닫지도 않는다(무응답, design 결정 1)
      return {
        inject: ['POST', `${proxyUrl(target)}/toxics`, {
          name: TOXIC_NAMES.timeout, type: 'timeout', stream: 'downstream', toxicity: 1,
          attributes: { timeout: 0 },
        }],
        undo: ['DELETE', `${proxyUrl(target)}/toxics/${TOXIC_NAMES.timeout}`, null],
      };
    case 'down':
      return {
        inject: ['POST', proxyUrl(target), { enabled: false }],
        undo: ['POST', proxyUrl(target), { enabled: true }],
      };
    case 'errorRate':
      return {
        inject: ['POST', actuatorUrl(target, 'faults'), { errorRate: fault.errorRate }],
        undo: ['DELETE', actuatorUrl(target, 'faults'), null],
      };
    case 'quota':
      return {
        inject: ['POST', actuatorUrl(target, 'quota'), {
          limit: Math.floor(rps * fault.windowSeconds * fault.limitRatio),
          windowSeconds: fault.windowSeconds,
        }],
        undo: ['DELETE', actuatorUrl(target, 'quota'), null],
      };
    default:
      throw new Error(`알 수 없는 장애 동작: ${action}`);
  }
}

/** 시간표를 `at` 순서로 정렬한다. 같은 시각은 파일에 적은 순서를 지킨다. */
export function sortByTime(faults) {
  return faults.map((fault, index) => ({ fault, index }))
    .sort((a, b) => a.fault.at - b.fault.at || a.index - b.index)
    .map(({ fault }) => fault);
}

/**
 * 시나리오 JSON의 장애 시간표가 올바른지 미리 본다. 시간표를 돌다 중간에 멈추지 않게 init에서 부른다.
 * `clear`는 그 의존성에 먼저 넣은 장애가 있어야 한다.
 */
export function validate(faults, rps) {
  const injected = {};
  sortByTime(faults).forEach((fault) => {
    if (typeof fault.at !== 'number' || fault.at < 0) {
      throw new Error(`장애 시각(at)이 0 이상의 숫자가 아니다: ${JSON.stringify(fault)}`);
    }
    if (!SERVICES[fault.target]) {
      throw new Error(`알 수 없는 의존성: ${JSON.stringify(fault)}`);
    }
    if (fault.action === 'clear') {
      if (!injected[fault.target]) {
        throw new Error(`거둘 장애가 없는 clear: ${JSON.stringify(fault)}`);
      }
      injected[fault.target] = false;
    } else {
      plan(fault, rps);
      injected[fault.target] = true;
    }
  });
}

/**
 * 시간표의 장애를 하나씩 넣는다. `clear`는 그 의존성에 넣은 장애를 넣은 반대 순서로 모두 거둔다.
 * 되돌리기(`resetAll`) 뒤에 시작하므로 그 의존성의 장애는 이 시간표가 넣은 것뿐이다.
 */
export class FaultSchedule {
  constructor(rps) {
    this.rps = rps;
    this.injected = {};
  }

  apply(fault) {
    const { action, target } = fault;
    if (action === 'clear') {
      const undos = (this.injected[target] || []).reverse();
      this.injected[target] = [];
      return undos.map(([method, url, body]) => request(method, url, body, 'clear', target)).every(Boolean);
    }
    const { inject, undo } = plan(fault, this.rps);
    const [method, url, body] = inject;
    const ok = request(method, url, body, action, target);
    (this.injected[target] = this.injected[target] || []).push(undo);
    return ok;
  }
}

/**
 * 모든 장애를 되돌린다: Toxiproxy `POST /reset`(프록시 켬, 톡식 지움), 하위 서비스 5개 `DELETE /actuator/faults`,
 * ad `DELETE /actuator/quota`. 하나라도 2xx가 아니면 false다.
 */
export function resetAll() {
  const results = [request('POST', `${TOXIPROXY_URL}/reset`, null, 'reset', 'all')];
  Object.keys(SERVICES).forEach((target) => {
    results.push(request('DELETE', actuatorUrl(target, 'faults'), null, 'reset', target));
  });
  results.push(request('DELETE', actuatorUrl('ad', 'quota'), null, 'reset', 'ad'));
  return results.every(Boolean);
}
