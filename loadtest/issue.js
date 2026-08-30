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
 * 503 을 받은 사람이 몇 번까지 다시 누르는가.
 *
 * 503 은 "잠시 후 다시 시도해주세요" 라고 답하고 서버가 Retry-After 헤더까지 준다.
 * 재시도를 안 하면 그 안내를 무시하는 셈이고, 실제보다 부하를 낮게 잡는다.
 * 반대로 무한히 재시도하면 사람이 아니라 봇을 흉내 내게 된다.
 *
 * 5 는 첫 시도와 재시도 네 번이다. 실제 사람이 몇 번쯤 다시 누르다 포기하는지는
 * 재 본 적이 없어 정한 값이고, 회차 기록에 함께 적어야 비교가 된다.
 */
const MAX_ATTEMPTS = parseInt(__ENV.MAX_ATTEMPTS || '5', 10);

/*
 * 재시도 전에 쉬는 시간이다. 서버가 Retry-After 로 값을 주면 그것을 따르고,
 * 없을 때만 이 값을 쓴다. 지금 서버는 1초를 준다.
 */
const RETRY_FALLBACK = parseFloat(__ENV.RETRY_AFTER || '1');

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
/*
 * 200 이지만 이번에 발급된 것이 아니라 원래 갖고 있던 순번을 돌려받은 경우다.
 * 앞선 시도가 503 으로 답했는데 그 티켓이 큐에 남아 결국 써졌을 때 여기 잡힌다.
 * 이 값이 크면 요청 예산이 실제 처리 시간보다 짧다는 뜻이다.
 */
const alreadyIssued = new Counter('coupon_already_issued');
// 503 을 받고 다시 쏜 횟수. 사람 수가 아니라 시도 횟수다
const retried = new Counter('coupon_retried');
// 재시도 상한까지 갔는데도 못 받고 끝난 사람 수
const gaveUp = new Counter('coupon_gave_up');
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

/*
 * VU 마다 따로 갖는 상태다. k6 는 VU 하나에 자바스크립트 런타임 하나를 주므로
 * 모듈 수준 변수가 VU 사이에 공유되지 않는다. SharedArray 가 따로 있는 이유가 그것이다.
 */
let settled = false;   // 이 사람의 결과가 확정됐나
let attempts = 0;      // 이 사람이 쏜 횟수

export default function () {
  /*
   * 토큰을 VU 번호로 고른다. VU 하나가 사람 하나다.
   *
   * 반복 번호로 고르면 안 된다. VU 는 한 번 쏜 뒤에도 살아 있어 다시 도는데, 그때 반복
   * 번호가 올라가 뒤에 올 사람 몫의 토큰을 먼저 가져간다. 그러면 램프가 끝나기 전에
   * 2만 건이 소진되어 ramp-up 60초가 성립하지 않는다.
   * 실제로 그렇게 돌렸을 때 10초에서 25초 사이에 끝났다 (2026-08-30).
   *
   * VU 를 20,000 보다 줄여 예비 시험을 하면 시도하는 사람 수도 함께 준다.
   * 요구 조건을 재는 회차에서는 VUS 를 덮어쓰지 않는다.
   */
  const index = exec.vu.idInTest - 1;
  if (index >= tokens.length) {
    return;
  }

  // 이 사람은 이미 끝났다. 남은 시간 동안 접속만 유지한다
  if (settled) {
    sleep(1);
    return;
  }

  const user = tokens[index];
  const res = http.post(`${BASE_URL}/v1/coupons/${COUPON_ID}/issues`, null, {
    headers: { Authorization: `Bearer ${user.token}` },
    tags: { name: 'issue' },
  });
  attempts += 1;

  if (res.status === 200) {
    /*
     * 본문의 alreadyIssued 로 가른다. 문자열로 보는 것은 res.json() 이 2만 VU 에서
     * 파싱 비용을 무시할 수 없기 때문이다.
     */
    if (res.body && res.body.indexOf('"alreadyIssued":true') >= 0) {
      alreadyIssued.add(1);
    } else {
      issued.add(1);
    }
    settled = true;
  } else if (res.status === 409) {
    // 소진은 최종이다. 다시 시도해도 같다
    soldOut.add(1);
    settled = true;
  } else if (res.status === 503) {
    congested.add(1);
    if (attempts >= MAX_ATTEMPTS) {
      gaveUp.add(1);
      settled = true;
    } else {
      /*
       * 서버가 Retry-After 로 얼마나 기다릴지 알려 준다. 그것을 따른다.
       * 임의로 더 빨리 다시 쏘면 서버가 요청한 배압을 무시하는 셈이다.
       */
      const hinted = parseFloat(res.headers['Retry-After']);
      retried.add(1);
      sleep(Number.isFinite(hinted) && hinted > 0 ? hinted : RETRY_FALLBACK);
    }
  } else if (res.status === 422 || res.status === 404) {
    // 자격이 아니다. 최종이다
    rejected.add(1);
    settled = true;
  } else if (res.status === 0) {
    /*
     * 연결조차 못 했다. 앱이 아니라 생성기가 모자란 것이라 재시도로 덮지 않는다.
     * 덮으면 생성기의 한계가 지표에서 사라진다.
     */
    connectFailed.add(1);
    settled = true;
  } else {
    unexpected.add(1);
    settled = true;
    console.error(`예상 밖 응답 status=${res.status} body=${res.body}`);
  }
}
