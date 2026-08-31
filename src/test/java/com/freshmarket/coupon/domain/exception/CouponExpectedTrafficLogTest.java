package com.freshmarket.coupon.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.freshmarket.common.exception.GlobalExceptionHandler;
import com.freshmarket.common.logging.AccessLogSignal;
import com.freshmarket.common.logging.HttpBodyLoggingFilter;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/*
 * 선착순의 소진과 혼잡이 로그를 남기지 않는지 본다.
 *
 * 두 줄이 함께 내려가야 볼륨이 준다. 예외를 다루는 쪽만 내리면 요청/응답 바디를 싣는 접근 로그가
 * 그대로 남는다. 그 접근 로그는 상태 코드밖에 못 보므로 AccessLogSignal 이 다리를 놓는다.
 *
 * 이 시험이 common 이 아니라 coupon 아래에 있는 이유가 있다. 무엇을 안 남길지는 CouponErrorCode
 * 가 정하는 쿠폰의 정책이고, common 의 두 클래스는 그 정책을 실어 나를 뿐이다.
 *
 * 로그 볼륨은 이 경로에서 이미 한 번 장애를 냈다. 2026-08-30 부하 시험에서 혼잡 24,000건이
 * 로그 큐를 채워 요청 스레드가 거기서 막혔다.
 */
class CouponExpectedTrafficLogTest {

    private GlobalExceptionHandler handler;
    private HttpBodyLoggingFilter filter;
    private MockHttpServletRequest request;

    private Logger handlerLogger;
    private Logger filterLogger;
    private ListAppender<ILoggingEvent> handlerLog;
    private ListAppender<ILoggingEvent> filterLog;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        filter = new HttpBodyLoggingFilter();
        request = new MockHttpServletRequest("POST", "/v1/coupons/900001/issues");
        request.setContentType("application/json");

        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        filterLogger = (Logger) LoggerFactory.getLogger(HttpBodyLoggingFilter.class);
        handlerLog = attach(handlerLogger);
        filterLog = attach(filterLogger);
        // 운영과 같은 수준에서 잰다. DEBUG 를 켜면 필터가 정상 응답에도 바디를 실어 동작이 달라진다
        handlerLogger.setLevel(Level.INFO);
        filterLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(handlerLog);
        filterLogger.detachAppender(filterLog);
        handlerLogger.setLevel(null);
        filterLogger.setLevel(null);
    }

    /*
     * 이 시험이 이 변경의 핵심이다.
     * 재고가 1만인데 2만 명이 오면 절반이 소진을 받는 것이 설계다. 정해진 결과가 두 줄씩 남으면
     * 그 규모가 곧 로그 규모가 된다.
     */
    @Test
    void 소진은_두_로거_어디에도_안_남는다() throws Exception {
        handler.handleBusiness(new CouponException(CouponErrorCode.SOLD_OUT), request);
        접근_로그를_흘린다(409);

        assertThat(handlerLog.list).isEmpty();
        assertThat(filterLog.list).isEmpty();
    }

    // 회수할 것도 없는 최종 소진이다. 재시도를 끊으려고 410 으로 답하는 정해진 결과다
    @Test
    void 최종_소진도_안_남는다() throws Exception {
        handler.handleBusiness(new CouponException(CouponErrorCode.SOLD_OUT_FINAL), request);
        접근_로그를_흘린다(410);

        assertThat(handlerLog.list).isEmpty();
        assertThat(filterLog.list).isEmpty();
    }

    // 혼잡은 503 이라 접근 로그가 ERROR 로 찍고 있었다. 서버 결함이 아니라 우리가 만든 배압이다
    @Test
    void 혼잡은_5xx_지만_안_남는다() throws Exception {
        assertThat(CouponErrorCode.CONGESTED.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        handler.handleBusiness(new CouponException(CouponErrorCode.CONGESTED), request);
        접근_로그를_흘린다(503);

        assertThat(handlerLog.list).isEmpty();
        assertThat(filterLog.list).isEmpty();
    }

    /*
     * 접근 로그까지 내리려면 신호가 건너가야 한다.
     * 이것이 빠지면 두 줄 중 바디를 싣는 큰 쪽이 그대로 남아 볼륨이 절반밖에 안 준다.
     */
    @Test
    void 예외를_다루는_쪽이_접근_로그에_알린다() {
        handler.handleBusiness(new CouponException(CouponErrorCode.SOLD_OUT), request);

        assertThat(AccessLogSignal.isExpected(request)).isTrue();
    }

    // 대상 등급이 아닌 것은 클라이언트나 설정이 잘못된 것이다. 드물게 나오고 나올 때 봐야 한다
    @Test
    void 예상되지_않은_실패는_그대로_남는다() throws Exception {
        handler.handleBusiness(new CouponException(CouponErrorCode.NOT_TARGET_GRADE), request);
        접근_로그를_흘린다(422);

        assertThat(handlerLog.list).hasSize(1);
        assertThat(handlerLog.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(filterLog.list).hasSize(1);
        assertThat(filterLog.list.get(0).getLevel()).isEqualTo(Level.WARN);
        // 원인을 봐야 하는 실패라 바디까지 남는다
        assertThat(filterLog.list.get(0).getFormattedMessage()).contains("resBody=\"소진되었습니다\"");
    }

    /*
     * 안 남기는 것이 영영 못 보는 것이면 곤란하다.
     * 필터 주석이 적어 둔 대로 /actuator/loggers 로 이 로거만 DEBUG 로 올리면 다시 보인다.
     */
    @Test
    void DEBUG_를_켜면_다시_보인다() throws Exception {
        filterLogger.setLevel(Level.DEBUG);

        handler.handleBusiness(new CouponException(CouponErrorCode.SOLD_OUT), request);
        접근_로그를_흘린다(409);

        assertThat(filterLog.list).hasSize(1);
        assertThat(filterLog.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
    }

    /*
     * 예상된 갈래를 넓히면 안 보이는 실패가 는다.
     * 지금 둘뿐이라는 것을 시험이 들고 있어야, 나중에 늘 때 이 줄이 걸린다.
     */
    @Test
    void 예상된_답은_소진_둘과_혼잡_셋뿐이다() {
        assertThat(Arrays.stream(CouponErrorCode.values())
                .filter(CouponErrorCode::isExpectedTraffic)
                .toList())
                .containsExactlyInAnyOrder(CouponErrorCode.SOLD_OUT,
                        CouponErrorCode.SOLD_OUT_FINAL, CouponErrorCode.CONGESTED);
    }

    private static ListAppender<ILoggingEvent> attach(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    // 예외를 다룬 뒤 필터가 finally 에서 도는 순서를 그대로 흉내 낸다
    private void 접근_로그를_흘린다(int status) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCharacterEncoding(StandardCharsets.UTF_8);

        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws IOException, ServletException {
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(status);
                res.getWriter().write("소진되었습니다");
                super.doFilter(req, res);
            }
        };
        filter.doFilter(request, response, chain);
    }
}
