package com.freshmarket.product.domain.dto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/*
 * 페이지 토큰 인코딩. 커서(정렬 기준값 + id)를 클라이언트가 해석할 수 없는
 * 문자열로 감싼다 (API-5-02). 정렬 기준값을 같이 담는 이유는 정렬(created_at, 가격)이
 * id 와 다른 축이라 id 하나만으로는 정확한 다음 페이지를 가리킬 수 없기 때문이다 (API-3-04).
 */
public final class PageTokens {

    private static final String PREFIX = "p:";
    private static final String DELIMITER = "|";

    private PageTokens() {
    }

    // 커서를 토큰으로 만든다
    public static String encode(PageCursor cursor) {
        if (cursor == null || cursor.id() == null) {
            return null;
        }
        String payload = PREFIX + cursor.id() + DELIMITER
                + (cursor.sortValue() == null ? "" : cursor.sortValue());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    // 토큰에서 커서를 꺼낸다. 형식이 어긋나면 첫 페이지로 본다 (null 반환)
    public static PageCursor decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(PREFIX)) {
                return null;
            }
            String body = decoded.substring(PREFIX.length());
            int delimiterIndex = body.indexOf(DELIMITER);
            if (delimiterIndex < 0) {
                return null;
            }
            Long id = Long.valueOf(body.substring(0, delimiterIndex));
            String sortValue = body.substring(delimiterIndex + 1);
            return new PageCursor(id, sortValue.isEmpty() ? null : sortValue);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}