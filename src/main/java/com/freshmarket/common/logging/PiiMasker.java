package com.freshmarket.common.logging;

import java.util.function.Function;

/**
 * 응답 DTO / 로그에서 개인정보를 부분 마스킹하기 위한 유틸.
 *
 * Jackson 커스텀 시리얼라이저(@JsonSerialize) 방식 대신 DTO의 from() 팩토리 메서드에서
 * 명시적으로 호출하는 방식을 택했다 — "이 필드는 마스킹된다"는 게 어노테이션 뒤에 숨지 않고
 * 코드에 그대로 드러나야, 리뷰할 때나 나중에 필드를 추가할 때 실수로 놓치지 않는다.
 *
 * 예: 본인 전용 응답("내 정보 조회")은 마스킹 없이 원본을 그대로 주고, 엔티티 toString()이나
 *     로그에는 maskEmail()/maskName() 등으로 마스킹해서 남긴다. 남에게 보여주는 공개 프로필
 *     응답이 생기면 그 응답 DTO에서도 이 패턴을 재사용한다.
 */
public final class PiiMasker {

    private PiiMasker() {
    }

    /** hong.gildong@gmail.com -> ho******@gmail.com */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return maskGeneric(email, 1, 0);
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        int keep = Math.min(2, local.length());
        return local.substring(0, keep) + "*".repeat(Math.max(local.length() - keep, 3)) + domain;
    }

    /** 01012345678 / 010-1234-5678 -> 010****5678 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 7) {
            return maskGeneric(phone, 0, 0);
        }
        String prefix = digits.substring(0, 3);
        String suffix = digits.substring(digits.length() - 4);
        return prefix + "*".repeat(digits.length() - 7) + suffix;
    }

    /** 홍길동 -> 홍*동, 홍 -> 홍(2자 이하는 마스킹 의미 없어 그대로) */
    public static String maskName(String name) {
        if (name == null || name.isBlank() || name.length() <= 1) {
            return name;
        }
        if (name.length() == 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }

    /** 앞 keepPrefix자, 뒤 keepSuffix자만 남기고 나머지는 * 처리하는 범용 마스킹. */
    public static String maskGeneric(String value, int keepPrefix, int keepSuffix) {
        if (value == null) {
            return null;
        }
        int len = value.length();
        if (len <= keepPrefix + keepSuffix) {
            return "*".repeat(len);
        }
        String prefix = value.substring(0, keepPrefix);
        String suffix = keepSuffix == 0 ? "" : value.substring(len - keepSuffix);
        return prefix + "*".repeat(len - keepPrefix - keepSuffix) + suffix;
    }

    /** JWT, API 키처럼 일부 노출도 위험한 값은 완전 마스킹. */
    public static String redact(String value) {
        return value == null || value.isBlank() ? value : "***REDACTED***";
    }

    /** 카카오 회원번호(provider_user_id) 등 외부 식별자 로깅용. 1234567890 -> 12******90 */
    public static String maskProviderId(String providerId) {
        return maskGeneric(providerId, 2, 2);
    }

    /**
     * (2026-08-20, SEC-3-02/FUN-3-01) 조회 응답이 마스킹된 값만 내려주는 필드(name/email/phone
     * 등)는, 수정 폼이 그 값을 그대로 채워둔 채로 다시 제출되면 서버가 마스킹 문자열을 진짜 값인
     * 것처럼 저장해버릴 수 있다. 수정 요청으로 들어온 값이 "현재 저장된 값을 마스킹한 결과"와
     * 똑같다면, 사용자가 실제로 바꾼 게 아니라 화면에 떠 있던 표시값을 그대로 돌려보낸 것으로
     * 보고 미변경 취급한다.
     */
    public static boolean isMaskedEchoOf(String submitted, String current, Function<String, String> masker) {
        return submitted != null && current != null && submitted.equals(masker.apply(current));
    }
}
