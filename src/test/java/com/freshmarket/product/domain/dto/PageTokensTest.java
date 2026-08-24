package com.freshmarket.product.domain.dto;

import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// PageTokens 의 인코딩/디코딩 왕복과 잘못된 입력 처리를 확인한다
class PageTokensTest {

    @Test
    void 커서를_인코딩하고_디코딩하면_원래_값으로_돌아온다() {
        PageCursor cursor = new PageCursor(12L, "2026-08-19T10:00:00");

        String token = PageTokens.encode(cursor);

        assertThat(PageTokens.decode(token)).isEqualTo(cursor);
    }

    @Test
    void null_커서는_null_토큰이_된다() {
        assertThat(PageTokens.encode(null)).isNull();
    }

    @Test
    void null_토큰을_디코딩하면_null이다() {
        assertThat(PageTokens.decode(null)).isNull();
    }

    @Test
    void 빈_토큰을_디코딩하면_null이다() {
        assertThat(PageTokens.decode("")).isNull();
    }

    @Test
    void 형식이_어긋난_토큰을_디코딩하면_null이다() {
        assertThat(PageTokens.decode("이건-유효한-Base64가-아니다!!")).isNull();
    }

    @Test
    void 접두사가_다른_토큰을_디코딩하면_null이다() {
        String other = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("x:12|abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(PageTokens.decode(other)).isNull();
    }

    @Test
    void 구분자가_없는_토큰을_디코딩하면_null이다() {
        String malformed = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("p:12".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(PageTokens.decode(malformed)).isNull();
    }
}