package com.freshmarket.common.logging;

import jakarta.servlet.ServletRequest;

/**
 * 접근 로그의 수준을 낮추라는 표시다. 예외를 다루는 쪽이 남기고 {@link HttpBodyLoggingFilter} 가 읽는다.
 *
 * <p><b>다리가 필요한 이유는 필터가 상태 코드밖에 못 보기 때문이다.</b> 소진도 혼잡도 4xx/5xx 라,
 * 필터만 두면 정상 운영에서 예상되는 답까지 바디를 실어 남긴다. 어느 실패가 예상된 것인지는
 * {@code ErrorCode} 가 알고 있고, 그 앎이 필터까지 닿아야 두 줄이 함께 내려간다.
 *
 * <p>요청 속성을 쓰는 것은 필터가 예외 처리보다 <b>바깥에서 나중에</b> 돌기 때문이다. 필터의
 * {@code finally} 가 도는 시점에는 이 표시가 이미 남아 있다.
 */
public final class AccessLogSignal {

    private static final String EXPECTED_TRAFFIC = AccessLogSignal.class.getName() + ".expectedTraffic";

    private AccessLogSignal() {
    }

    /** 예외를 다루는 쪽이 부른다. 이 응답이 정상 운영에서 예상되는 답이라는 뜻이다. */
    public static void markExpected(ServletRequest request) {
        request.setAttribute(EXPECTED_TRAFFIC, Boolean.TRUE);
    }

    public static boolean isExpected(ServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(EXPECTED_TRAFFIC));
    }
}
