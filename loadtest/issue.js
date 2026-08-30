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
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const COUPON_ID = __ENV.COUPON_ID || '900001';

/*
 * 요구사항이 정한 값이다. 사람 한 명이 VU 하나다.
 *
 *   재고 10,000장에 20,000명이 요청, ramp-up 60초
 *
 * ramp-up 은 전체 사용자 수에 도달하기까지의 시간이다(JMeter 의 정의와 같다).
 * 그래서 60초 뒤에 VU 가 20,000 이어야 하고, 그때까지 초당 333명씩 들어온다.
 */
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
    /*
     * VU 하나가 사람 하나다. 60초에 걸쳐 20,000명까지 올린다.
     *
     * 도착률은 VU 가 생기는 속도가 정한다. 아래 default 함수가 VU 당 한 번만 쏘므로
     * 초당 333명이 생기는 것이 곧 초당 333건이 도착하는 것이다.
     */
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
    /*
     * coupon.md 8장의 합격 기준을 그대로 옮긴 것이다.
     * "요구 부하를 걸었을 때 처리된 발급 응답의 p99 가 1초 이하다."
     *
     * 전에는 p(95)<2000 이었다. 분위수도 임계도 SLO 보다 느슨해서, 이 임계를 통과해도
     * 합격인지 알 수 없었다. 실제로 5,000 VU 회차가 이 임계는 통과하고 SLO 는 미달이었다
     * (2026-08-30, p99 1.127초). 사람이 Prometheus 를 따로 뒤져야 드러났다.
     */
    'http_req_duration{expected_response:true}': ['p(99)<1000'],
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
   * 토큰을 VU 번호로 고른다. VU 하나가 사람 하나이므로 한 사람이 정확히 한 번만 쏜다.
   *
   * 반복 번호로 고르면 안 된다. VU 는 한 번 쏜 뒤에도 살아 있어 다시 도는데, 그때 반복
   * 번호가 올라가 뒤에 올 사람 몫의 토큰을 먼저 가져간다. 그러면 램프가 끝나기 전에
   * 2만 건이 소진되어 ramp-up 60초가 성립하지 않는다.
   * 실제로 그렇게 돌렸을 때 10초에서 25초 사이에 끝났다 (2026-08-30).
   *
   * 한 사람이 두 번 쏘면 안 되는 이유는 따로 있다. uk_mc_coupon_member 가 두 장을 막아
   * 재요청은 같은 순번을 그대로 돌려받는데, 그 응답이 섞이면 "몇 장이 나갔나" 를
   * 응답만 보고는 못 읽는다.
   *
   * VU 를 20,000 보다 줄여 예비 시험을 하면 시도하는 사람 수도 함께 준다.
   * 요구 조건을 재는 회차에서는 VUS 를 덮어쓰지 않는다.
   */
  const index = exec.vu.idInTest - 1;
  if (index >= tokens.length) {
    return;
  }

  // 이 VU 는 이미 쏘았다. 남은 시간 동안 접속만 유지한다
  if (exec.vu.iterationInInstance > 0) {
    sleep(1);
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
}
