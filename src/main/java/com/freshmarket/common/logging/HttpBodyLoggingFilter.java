package com.freshmarket.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 요청/응답 바디를 로그로 남기되, 민감정보는 마스킹해서 찍는 필터.
 *
 * - ContentCachingRequestWrapper/ResponseWrapper로 스트림을 감싸서 바디를 다시 읽을 수 있게 만든다
 *   (서블릿 InputStream/OutputStream은 원래 한 번만 읽고 버려짐).
 * - password/token/phone/address류 "키"는 값을 통째로 REDACTED 처리(자유 형식 텍스트라 부분 마스킹이
 *   애매해서), email/전화번호는 키 이름과 무관하게 본문 전체에서 패턴으로 찾아 부분 마스킹(catch-all).
 * - 정상 응답(2xx/3xx)은 상태코드+소요시간만 INFO로 남기고, 에러 응답(4xx/5xx)만 바디까지 남긴다
 *   (로그 볼륨 관리). DEBUG 레벨이면 정상 응답도 바디까지 남긴다 — logAccess() 참고.
 * - 다만 AccessLogSignal 이 붙은 요청은 4xx/5xx 여도 DEBUG로 내린다 — 선착순의 소진/혼잡처럼
 *   정상 운영에서 예상되는 답이라, 그 규모가 곧 로그 규모가 되는 것을 막는다.
 * - 응답은 반드시 copyBodyToResponse()로 실제 클라이언트에게 흘려보내야 한다 — 안 하면 빈 응답이 나감.
 * - 바이너리(이미지 업로드 등)나 SSE처럼 긴 스트리밍 응답은 본문 로깅 대상에서 제외.
 *
 * MdcLoggingFilter(HIGHEST_PRECEDENCE)보다 뒤에 실행되게 순서를 한 칸 늦췄다 — traceId가 먼저 MDC에 박혀 있어야
 * 이 필터가 남기는 로그도 같은 traceId로 묶인다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class HttpBodyLoggingFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_LOG_LENGTH = 2000;

    // ContentCachingRequestWrapper가 실제로 메모리에 캐싱해둘 최대 바이트 수. MAX_BODY_LOG_LENGTH(문자 수
    // 기준 자르기)보다 넉넉하게 잡는다 — UTF-8에서 한글 등 멀티바이트 문자는 1자당 최대 3바이트라,
    // 바이트 기준 캐시 한도를 문자 수랑 똑같이 잡으면 로그로 남기기도 전에 글자가 중간에 잘릴 수 있다.
    private static final int MAX_BODY_CACHE_BYTES = 8192;

    private static final List<String> EXCLUDED_PATH_PREFIXES = List.of(
            "/notifications/stream" // SSE 등 스트리밍 응답은 캐싱하면 안 됨
    );

    // (OBS-3-05) 이 목록에 없는 Content-Type은 캐싱/로깅 대상에서 뺀다. 텍스트 계열이 아닌
    // 요청(이미지 업로드 등 multipart/octet-stream)까지 무조건 ContentCachingRequestWrapper로
    // 감싸면, 4xx 응답에서 바이너리를 UTF-8로 억지로 디코드해 깨진 문자를 그대로 로그에 남기게
    // 된다 — 정상 응답이라 실제로는 안 남기는 경우조차 캐싱 자체는 매 요청 일어나는 것도 낭비다.
    private static final List<String> LOGGABLE_CONTENT_TYPE_PREFIXES = List.of(
            "application/json", "text/", "application/x-www-form-urlencoded"
    );

    // "password":"1234", "refreshToken": "eyJ..." 같은 키-값 쌍의 값을 통째로 마스킹.
    // phone/address류(recipient/zipcode/roadAddress/detailAddress)도 자유 형식 텍스트라 이메일처럼
    // 부분 마스킹하기 애매해서 password/token과 똑같이 통째로 REDACTED 처리한다.
    // authorizationCode/state/nonce/code(카카오 로그인·탈퇴 재인증 요청 바디)도 같은 이유로 넣는다 —
    // 로그인 실패는 흔한 4xx 경로라 이 필드들이 없으면 WARN 로그에 카카오 인가 코드가 상시 평문으로 남는다.
    // (2026-08-20, SEC-4-04/OBS-3-04) name도 추가한다 — DDL이 email/name/phone을 암호화 대상으로
    // 못박은 컬럼인데 name만 빠져 있었다. nickname은 안 넣는다 — 카카오/본인이 정하는 공개
    // 표시용 값이라 DDL의 암호화 대상 목록에도 없고, PII로 볼 근거가 약하다.
    // 값 부분을 별도 캡처 그룹(3번)으로 빼서 PiiMasker.redact()에 그대로 넘긴다 — REDACTED 리터럴을
    // 여기 직접 하드코딩하면 마스킹 문자열이 필요할 때마다 여러 곳에서 따로 관리돼서, 나중에
    // 마스킹 표기를 바꾸거나 정책을 통일할 때 여기만 고치고 끝나지 않는다.
    private static final Pattern SENSITIVE_JSON_FIELD = Pattern.compile(
            "(?i)(\"(password|accessToken|refreshToken|token|secret|authorization|idToken|clientSecret"
                    + "|phone|address|recipient|zipcode|roadAddress|detailAddress|name"
                    + "|authorizationCode|state|nonce)\"\\s*:\\s*\")([^\"]*)(\")");

    // (OBS-3-04/SEC-4-02) application/x-www-form-urlencoded 바디("password=1234&token=eyJ...")는
    // 위 JSON 전용 패턴("key":"value")에 안 걸려서 그대로 새어나갔다 — 로그인 폼 등 form-urlencoded로
    // 오는 요청의 민감 키도 동일한 키 목록으로 잡아서 "key=" 뒤 값(다음 "&" 전까지) 전체를 REDACTED 처리한다.
    // SENSITIVE_JSON_FIELD와 같은 키 목록을 쓴다 — 인코딩만 다를 뿐 같은 값이 새면 위험도가 같다.
    private static final Pattern SENSITIVE_FORM_FIELD = Pattern.compile(
            "(?i)((?:^|&)(?:password|accessToken|refreshToken|token|secret|authorization|idToken|clientSecret"
                    + "|phone|address|recipient|zipcode|roadAddress|detailAddress|name"
                    + "|authorizationCode|state|nonce)=)([^&]*)");

    // 위 키-값 패턴에 안 걸린 이메일/전화번호도 한 번 더 잡아서 부분 마스킹(키 이름이 다르거나
    // 문자열 안에 섞여 나오는 경우 대비 catch-all).
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE_PATTERN = Pattern.compile("01[016789]-?\\d{3,4}-?\\d{4}");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (EXCLUDED_PATH_PREFIXES.stream().anyMatch(uri::startsWith)) {
            return true;
        }
        String contentType = request.getContentType();
        // GET/DELETE처럼 바디가 없는 요청은 contentType이 null이다 — 감쌀 필요는 있지만(응답
        // 바디는 여전히 로깅 대상) 바이너리 위험이 없어 그대로 통과시킨다. 값이 있는데 텍스트
        // 계열이 아니면(이미지 업로드 등) 이 필터를 건너뛴다.
        return contentType != null
                && LOGGABLE_CONTENT_TYPE_PREFIXES.stream().noneMatch(contentType::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 이 버전의 스프링은 무제한 캐싱하는 단일 인자 생성자를 없애고 캐시 한도를 강제한다 —
        // 요청 바디가 얼마나 크든 무제한으로 메모리에 다 올려두는 걸 막기 위한 변경.
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, MAX_BODY_CACHE_BYTES);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            logAccess(wrappedRequest, wrappedResponse, durationMs);

            // 캐싱 래퍼로 감쌌으니 실제 응답 스트림으로 반드시 다시 흘려보내야 클라이언트가 정상 응답을 받는다
            wrappedResponse.copyBodyToResponse();
        }
    }

    /**
     * 정상 응답(2xx/3xx)은 상태코드+소요시간만 INFO로 남긴다 — 매 요청마다 바디를 통째로 남기면
     * 운영 환경에서 로그 볼륨이 감당이 안 된다. 에러 응답(4xx/5xx)은 원인 파악이 중요해서 바디까지
     * 남기되, GlobalExceptionHandler의 "4xx=WARN, 5xx=ERROR" 관례를 그대로 따른다.
     *
     * 정상 응답이라도 DEBUG 레벨이 켜져 있으면 바디까지 남긴다 — 운영 중 특정 순간만 원인을
     * 자세히 봐야 할 때 `/actuator/loggers`로 이 로거만 즉시 DEBUG로 올리면 재배포 없이 바디를
     * 볼 수 있게 하기 위함이다(DEBUG를 계속 켜둔 채로 잊으면 다시 로그 폭탄이 되니 임시 디버깅
     * 용도로만 쓸 것 — 상시 DEBUG 운영은 이 메서드를 만든 취지에 어긋난다).
     */
    private void logAccess(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response, long durationMs) {
        int status = response.getStatus();
        /*
         * 예외를 다루는 쪽이 "이건 예상된 답" 이라고 남겨 둔 요청은 상태 코드를 안 따른다.
         *
         * 이 필터는 상태 코드밖에 못 보는데, 선착순의 소진과 혼잡은 4xx/5xx 이면서 정상 운영에서
         * 나오는 답이다. 그대로 두면 재고 1만짜리 이벤트 한 번이 만 줄을 남기고 그 줄마다 바디를
         * 싣는다. 세는 일은 coupon_issue_results_total 이 더 정확히 한다.
         */
        boolean expected = AccessLogSignal.isExpected(request);
        boolean needsBody = (status >= 400 && !expected) || log.isDebugEnabled();

        String reqBody = needsBody ? mask(extractBody(request.getContentAsByteArray())) : null;
        String resBody = needsBody ? mask(extractBody(response.getContentAsByteArray())) : null;

        if (expected) {
            log.debug("event=HTTP_ACCESS status={} durationMs={} reqBody=\"{}\" resBody=\"{}\"",
                    status, durationMs, reqBody, resBody);
        } else if (status >= 500) {
            log.error("event=HTTP_ACCESS status={} durationMs={} reqBody=\"{}\" resBody=\"{}\"",
                    status, durationMs, reqBody, resBody);
        } else if (status >= 400) {
            log.warn("event=HTTP_ACCESS status={} durationMs={} reqBody=\"{}\" resBody=\"{}\"",
                    status, durationMs, reqBody, resBody);
        } else if (log.isDebugEnabled()) {
            log.debug("event=HTTP_ACCESS status={} durationMs={} reqBody=\"{}\" resBody=\"{}\"",
                    status, durationMs, reqBody, resBody);
        } else if (log.isInfoEnabled()) {
            log.info("event=HTTP_ACCESS status={} durationMs={}", status, durationMs);
        }
    }

    private String extractBody(byte[] content) {
        if (content == null || content.length == 0) {
            return "";
        }
        String body = new String(content, StandardCharsets.UTF_8);
        return body.length() > MAX_BODY_LOG_LENGTH
                ? body.substring(0, MAX_BODY_LOG_LENGTH) + "...(truncated)"
                : body;
    }

    private String mask(String body) {
        if (body.isBlank()) {
            return body;
        }
        // group(3)=값(빈 문자열이면 PiiMasker.redact()가 그대로 통과시킴 — "값이 있는데 가렸다"는
        // 오해를 방지), group(4)=닫는 따옴표.
        String masked = SENSITIVE_JSON_FIELD.matcher(body).replaceAll(
                mr -> mr.group(1) + PiiMasker.redact(mr.group(3)) + mr.group(4));
        // group(1)="&key=" 또는 "key="(구분자+키+등호), group(2)=값. JSON 패턴과 별개로 폼 인코딩
        // 바디에도 동일하게 적용 — 두 패턴은 문법이 달라 서로 겹쳐 매치되지 않는다.
        masked = SENSITIVE_FORM_FIELD.matcher(masked).replaceAll(
                mr -> mr.group(1) + PiiMasker.redact(mr.group(2)));
        masked = maskPattern(masked, EMAIL_PATTERN, PiiMasker::maskEmail);
        masked = maskPattern(masked, PHONE_PATTERN, PiiMasker::maskPhone);
        return masked;
    }

    private String maskPattern(String body, Pattern pattern, UnaryOperator<String> masker) {
        Matcher matcher = pattern.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masker.apply(matcher.group())));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
