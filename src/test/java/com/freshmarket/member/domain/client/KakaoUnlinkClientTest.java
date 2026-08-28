package com.freshmarket.member.domain.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * (2026-08-27, PR 리뷰 P1) unlink()가 4xx(429 제외)를 재시도 대상에서 뺄지 판단하는 기준을
 * 직접 검증한다 — WebClient를 목킹하지 않고도 이 판단 로직만 볼 수 있게 isNonRetryableRejection을
 * package-private으로 열어뒀다.
 */
class KakaoUnlinkClientTest {

    @Test
    void 카카오가_정상적으로_거절한_4xx는_재시도_대상이_아니다() {
        assertThat(KakaoUnlinkClient.isNonRetryableRejection(HttpStatus.BAD_REQUEST)).isTrue();
        assertThat(KakaoUnlinkClient.isNonRetryableRejection(HttpStatus.UNAUTHORIZED)).isTrue();
        assertThat(KakaoUnlinkClient.isNonRetryableRejection(HttpStatus.FORBIDDEN)).isTrue();
        assertThat(KakaoUnlinkClient.isNonRetryableRejection(HttpStatus.NOT_FOUND)).isTrue();
    }

    @Test
    void 상태코드_429는_앱_전체_쿼터_문제라_재시도_대상으로_남긴다() {
        assertThat(KakaoUnlinkClient.isNonRetryableRejection(HttpStatus.TOO_MANY_REQUESTS)).isFalse();
    }

    @Test
    void 상태코드_5xx는_일시적일_수_있어_재시도_대상으로_남긴다() {
        assertThat(KakaoUnlinkClient.isNonRetryableRejection(HttpStatus.INTERNAL_SERVER_ERROR)).isFalse();
        assertThat(KakaoUnlinkClient.isNonRetryableRejection(HttpStatus.BAD_GATEWAY)).isFalse();
        assertThat(KakaoUnlinkClient.isNonRetryableRejection(HttpStatus.SERVICE_UNAVAILABLE)).isFalse();
    }
}
