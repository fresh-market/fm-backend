package com.freshmarket.common.exception;

import org.springframework.http.HttpStatus;

// 공통 코드는 CommonErrorCode이며 도메인마다 자기 ErrorCode enum 을 두고 해당 인터페이스를 구현
public interface ErrorCode {

    HttpStatus getHttpStatus();

    String getCode();

    String getMessage();

    /**
     * 이 실패가 <b>정상 운영에서 예상되는 답</b>인가. 기본은 아니다.
     *
     * <p>참이면 로그를 남기지 않는다. 선착순의 소진과 혼잡이 그렇다. 재고가 1만인데 2만 명이
     * 오면 절반은 소진을 받는 것이 설계이고, 몰릴 때 일부를 빠르게 거절하는 것도 설계다.
     * <b>정해진 결과를 이상으로 남기면 로그가 그 규모만큼 늘어난다.</b>
     *
     * <p>이 경로에서 로그 볼륨은 이미 한 번 장애를 냈다. 2026-08-30 부하 시험에서 혼잡
     * 24,000건이 로그 큐를 채워 요청 스레드가 거기서 막혔다.
     *
     * <p>세는 일은 지표가 한다. {@code coupon_issue_results_total} 이 어느 갈래로 몇 건인지를
     * 정확히 세므로 로그가 그것을 비싸게 다시 셀 이유가 없다. 잠깐 자세히 봐야 하면
     * {@code /actuator/loggers} 로 해당 로거만 DEBUG 로 올린다.
     */
    default boolean isExpectedTraffic() {
        return false;
    }
}
