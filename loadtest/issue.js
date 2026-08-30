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
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const COUPON_ID = __ENV.COUPON_ID || '900001';

/*
 * 요구사항이 정한 것은 "재고 1만에 2만 명이 ramp-up 60초로 몰린다" 이다.
 * 그래서 정하는 값은 동시 사용자 수가 아니라 도착률이다.
 *
 *   20,000 명 / 60 초 = 333 건/초
 *
 * 램프 끝의 목표 도착률을 그 두 배로 둔다. 0 에서 선형으로 올라가므로 60초 동안의
 * 넓이가 곧 총 건수이고, 삼각형이라 목표가 667 이어야 20,000 이 된다.
 */
const TARGET_RATE = parseInt(__ENV.RATE || '667', 10);
const RAMP = __ENV.RAMP || '60s';
// 램프가 끝난 뒤 밀린 큐가 빠지는 것까지 본다
const HOLD = __ENV.HOLD || '30s';

/*
 * k6 가 도착률을 맞추려고 빌려 쓰는 VU 다. 부하 수준이 아니라 여유분이다.
 *
 * 응답이 느려지면 한 건이 VU 를 오래 쥐므로 같은 도착률에 더 많은 VU 가 필요하다.
 * 모자라면 k6 가 dropped_iterations 를 올리고 도착률이 무너진다. 그때 나온 수치는
 * 앱이 아니라 생성기의 한계를 잰 것이라 못 쓴다.
 */
const PRE_VUS = parseInt(__ENV.PRE_VUS || '2000', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || '20000', 10);

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
     * ramping-vus 가 아니라 도착률 실행기를 쓴다.
     *
     * 앞의 것은 동시 사용자 수만 정하고 도착률은 응답 속도가 정한다. VU 가 응답을 기다리는
     * 동안 다음 요청을 못 보내기 때문이다. 그래서 앱이 느려지면 부하가 저절로 약해지고,
     * 빠르면 세진다. 시험이 스스로 봐주고 잘하면 벌을 주는 셈이다.
     *
     * 실제로 같은 VUS=20000 으로 돌린 두 회차가 364 건/초와 2,000 건/초로 갈렸다
     * (2026-08-30). 5 배 차이라 회차 간 비교가 성립하지 않았다.
     *
     * 도착률 실행기는 초당 몇 건을 보낼지 k6 가 지켜 준다. 앱이 느려도 부하가 안 줄어든다.
     */
    rush: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      stages: [
        { duration: RAMP, target: TARGET_RATE },
        { duration: HOLD, target: TARGET_RATE },
      ],
      preAllocatedVUs: PRE_VUS,
      maxVUs: MAX_VUS,
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
    /*
     * 도착률을 못 맞춘 회차는 결과를 못 쓴다. VU 가 모자라 k6 가 발사를 건너뛴 것이라
     * 앱이 아니라 생성기를 잰 셈이 된다. 그래서 임계로 걸어 자동으로 걸리게 한다.
     */
    dropped_iterations: ['count==0'],
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
    // 2만 명을 다 쏘았다. 남은 반복은 아무 일도 하지 않는다
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
