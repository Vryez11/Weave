// 장애 되돌리기만 한다 (design 결정 6).
//
// run.sh가 실행 앞과 `trap`(EXIT, INT, TERM)에서 Compose의 k6 서비스로 돌린다. 하위 서비스 Actuator는 호스트에
// 열려 있지 않아 Compose 네트워크 안에서 부른다. feed.js의 setup()·teardown()과 같은 함수다.
// 다섯 곳(Toxiproxy, 하위 서비스, ad 한도) 중 하나라도 2xx가 아니면 k6를 0이 아닌 코드로 끝낸다.
import exec from 'k6/execution';
import { resetAll } from './faults.js';

export const options = {
  vus: 1,
  iterations: 1,
};

export default function () {
  if (!resetAll()) {
    exec.test.abort('장애를 되돌리지 못했다');
  }
}
