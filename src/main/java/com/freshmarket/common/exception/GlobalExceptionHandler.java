package com.freshmarket.common.exception;

import com.freshmarket.common.response.ResponseEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/*
 * Controller 경계까지 전파된 예외를 공통의 HTTP 응답으로 변환한다.
 * 예측 가능한 비즈니스 예외는 자기 ErrorCode 가 정한 상태와 문구를 그대로 쓴다.
 * 예측 못한 예외는 내부 상세를 감추고 공통 500 응답으로 바꾼다.
 * 응답에 담기는 문구는 언제나 ErrorCode 에서 나온다. 여기서 문장을 지어내지 않는다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 재시도까지 기다릴 초. 선착순처럼 몰리는 경로에서 곧바로 되돌아오는 것을 늦춘다
    private static final int RETRY_AFTER_SECONDS = 1;

    // 도메인이 스스로 정의한 실패이므로 상태와 문구를 ErrorCode 에 맡긴다
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("business exception. code={}", errorCode.getCode(), e);
        return toResponse(errorCode);
    }

    /*
     * 요청 본문의 Bean Validation 실패다. MethodArgumentNotValidException 이 BindException 을 상속해 함께 걸린다.
     * 어느 필드가 왜 틀렸는지는 로그에만 남기고 응답에는 넣지 않는다.
     * 거절된 값 자체는 비밀번호처럼 남기면 안 되는 것이 섞이므로 필드명과 사유만 찍는다.
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleBind(BindException e) {
        String fields = e.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("invalid body. fields=[{}]", fields);
        return toResponse(CommonErrorCode.INVALID_INPUT);
    }

    // 경로 변수와 쿼리 파라미터의 검증 실패
    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ResponseEnvelope<Void>> handleValidation(Exception e) {
        log.warn("invalid parameter. detail={}", e.getMessage());
        return toResponse(CommonErrorCode.INVALID_INPUT);
    }

    /*
     * 도메인 엔티티가 자기 불변식을 스스로 지키려고 던지는 방어적 검증 실패다 (예: Category.validateName).
     * @Valid 가 먼저 걸러내는 경로가 대부분이지만, 그걸 우회해 여기까지 오는 경우를 위한 두 번째 방어선이다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("invalid argument. detail={}", e.getMessage());
        return toResponse(CommonErrorCode.INVALID_INPUT);
    }

    /*
     * 요청을 읽는 단계에서 깨진 것이라 검증까지 가지 못한 경우다.
     * 본문이 JSON 이 아니거나, 타입이 맞지 않거나, 필수 파라미터가 빠졌다.
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ResponseEnvelope<Void>> handleMalformedRequest(Exception e) {
        log.warn("malformed request. detail={}", e.getMessage());
        return toResponse(CommonErrorCode.MALFORMED_REQUEST);
    }

    /*
     * 인증과 인가 실패는 두 곳에서 난다. @PreAuthorize 같은 메서드 보안과, 토큰을 검사하는 보안 필터다.
     * @RestControllerAdvice 는 디스패처 서블릿 안에서만 도는데, 필터는 그 바깥이다.
     * 그래서 필터에서 난 것은 원래 여기까지 오지 않는다.
     * SecurityConfig 가 그것을 MVC 예외 처리로 다시 넘겨 주기 때문에 둘 다 여기로 모인다.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleAuthentication(AuthenticationException e) {
        log.warn("unauthenticated. detail={}", e.getMessage());
        return toResponse(CommonErrorCode.UNAUTHENTICATED);
    }

    // 대상의 존재 여부를 드러내지 않도록 상세를 응답에 담지 않는다 (API-7-05)
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("permission denied. detail={}", e.getMessage());
        return toResponse(CommonErrorCode.PERMISSION_DENIED);
    }

    // 매핑된 핸들러가 없는 경로이며, 리소스를 못 찾은 것과 다르다
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("endpoint not found. path={}", e.getResourcePath());
        return toResponse(CommonErrorCode.ENDPOINT_NOT_FOUND);
    }

    // 경로는 있으나 HTTP 메서드가 다르다
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.warn("method not allowed. method={}", e.getMethod());
        return toResponse(CommonErrorCode.METHOD_NOT_ALLOWED);
    }

    // 업로드 크기 상한 초과
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleContentTooLarge(MaxUploadSizeExceededException e) {
        log.warn("content too large. maxBytes={}", e.getMaxUploadSize());
        return toResponse(CommonErrorCode.CONTENT_TOO_LARGE);
    }

    // Content-Type 을 처리할 수 없다
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        log.warn("unsupported media type. contentType={}", e.getContentType());
        return toResponse(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /*
     * 위 어디에도 걸리지 않은 예외다. 여기 도달했다는 것 자체가 예상하지 못했다는 뜻이라 스택을 남긴다.
     * 응답에는 예외 종류도 메시지도 싣지 않는다. 내부 구조의 단서가 되기 때문이다 (API-7-04).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("unhandled exception. method={}, uri={}", request.getMethod(), request.getRequestURI(), e);
        return toResponse(CommonErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ResponseEnvelope<Void>> toResponse(ErrorCode errorCode) {
        HttpStatus status = errorCode.getHttpStatus();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);

        /*
         * 잠시 뒤면 되는 상태에는 언제 다시 오라는 값을 붙인다.
         * 도메인 지식이 아니라 상태 코드가 정하는 것이라 여기에 둔다. 이것이 없으면 클라이언트가
         * 곧바로 다시 눌러, 몰려서 못 받은 요청이 다시 몰리는 원인이 된다.
         */
        if (status == HttpStatus.SERVICE_UNAVAILABLE || status == HttpStatus.TOO_MANY_REQUESTS) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
        }
        return builder.body(ResponseEnvelope.fail(errorCode));
    }
}
