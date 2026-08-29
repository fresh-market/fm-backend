// 선착순 쿠폰 발급 부하 시험.
//
//   k6 run loadtest/issue.js
//
// 재는 것은 둘이다. 하나는 재고 1만에 2만이 몰려도 정확히 1만 장만 나가는가이고,
// 다른 하나는 docs/coupon/coupon.md 3장이 "재서 정할 값" 으로 열어 둔 것들이다.
//
// 앞의 것은 이 시나리오가 답을 못 낸다. k6 는 응답만 보고, 실제로 몇 장이 나갔는지는 DB 가 안다.
// 시험이 끝나면 loadtest/README.md 의 확인 쿼리를 돌려야 한다.

import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const COUPON_ID = __ENV.COUPON_ID || '900001';

// 요구사항이 정한 값이다. 기계가 2만 VU 를 못 버티면 줄여서 먼저 모양을 본다
const VUS = parseInt(__ENV.VUS || '20000', 10);
const RAMP = __ENV.RAMP || '60s';
// 램프가 끝난 뒤 밀린 큐가 빠지는 것까지 본다
const HOLD = __ENV.HOLD || '30s';

/*
 * 토큰을 SharedArray 로 읽는다.
 * 평범한 배열로 두면 VU 마다 사본이 생겨 2만 VU 에서 메모리가 그만큼 곱해진다.
 */
const tokens = new SharedArray('tokens', function () {
  const text = open('./tokens.csv');
  const lines = text.split('\n');
  const parsed = [];
  // 첫 줄은 헤더다
  for (let i = 1; i < lines.length; i++) {
    const line = lines[i].trim();
    if (line.length === 0) {
      continue;
    }
    const comma = line.indexOf(',');
    parsed.push({ memberId: line.substring(0, comma), token: line.substring(comma + 1) });
  }
  return parsed;
});

/*
 * 한 번 쏜 VU 가 다음 사람을 집기까지 쉬는 시간이다.
 * VU 를 2만까지 못 올리는 기계에서는 이 값이 곧 시도가 도착하는 속도를 정한다.
 * 짧게 자고 다시 도는 것은 시험이 끝날 때 k6 가 VU 를 거둘 수 있게 하려는 것이기도 하다.
 */
const IDLE_SECONDS = parseInt(__ENV.IDLE || '1', 10);

const issued = new Counter('coupon_issued');
const soldOut = new Counter('coupon_sold_out');
const congested = new Counter('coupon_congested');
const rejected = new Counter('coupon_rejected');
const unexpected = new Counter('coupon_unexpected');
/*
 * 부하 발생기가 연결조차 못 한 경우다. k6 는 이때 status 0 을 준다.
 * 앱이 낸 500 과 갈라야 한다. 앞은 시험 환경이 모자란 것이고 뒤는 앱이 잘못한 것이다.
 */
const connectFailed = new Counter('coupon_connect_failed');

export const options = {
  scenarios: {
    rush: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP, target: VUS },
        { duration: HOLD, target: VUS },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    // 7장의 요청 예산이다. 이걸 넘기면 사용자가 기다려 준 시간 밖에서 답한 것이다
    'http_req_duration{expected_response:true}': ['p(95)<2000'],
    // 소진과 혼잡은 정상 응답이라 실패로 안 센다. 여기 걸리는 것은 진짜 오류다
    http_req_failed: ['rate<0.01'],
    coupon_unexpected: ['count==0'],
  },
};

/*
 * 소진(409)과 혼잡(503)은 설계가 정한 정상 응답이다.
 * 이걸 안 알려 주면 k6 가 둘을 실패로 세서 http_req_failed 가 뜻을 잃는다.
 */
http.setResponseCallback(http.expectedStatuses(200, 409, 422, 503));

export default function () {
  /*
   * 토큰을 VU 번호가 아니라 반복 번호로 고른다.
   * VU 번호로 묶으면 VU 를 줄일 때 시도하는 사람 수도 같이 줄어, 재고 1만에 2만이 몰리는
   * 조건 자체가 사라진다. 반복 번호로 고르면 VU 수는 동시성만 정하고 시도는 언제나 2만이다.
   *
   * 한 사람이 두 번 쏘지 않는다. uk_mc_coupon_member 가 두 장을 막아 재요청은 같은 순번을
   * 그대로 돌려받는데, 그 응답이 섞이면 "몇 장이 나갔나" 를 응답만 보고는 못 읽는다.
   */
  const index = exec.scenario.iterationInTest;
  if (index >= tokens.length) {
    // 다 쏘고 남은 VU 다. 접속만 유지한다
    sleep(IDLE_SECONDS);
    return;
  }

  const user = tokens[index];
  const res = http.post(`${BASE_URL}/v1/coupons/${COUPON_ID}/issues`, null, {
    headers: { Authorization: `Bearer ${user.token}` },
    tags: { name: 'issue' },
  });

  if (res.status === 200) {
    issued.add(1);
  } else if (res.status === 409) {
    soldOut.add(1);
  } else if (res.status === 503) {
    congested.add(1);
  } else if (res.status === 422 || res.status === 404) {
    rejected.add(1);
  } else if (res.status === 0) {
    connectFailed.add(1);
  } else {
    unexpected.add(1);
    console.error(`예상 밖 응답 status=${res.status} body=${res.body}`);
  }

  sleep(IDLE_SECONDS);
}
