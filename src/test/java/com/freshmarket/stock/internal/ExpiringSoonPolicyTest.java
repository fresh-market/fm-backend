package com.freshmarket.stock.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/*
 * 기준일이 호스트 시간대가 아니라 한국 시간대로 정해지는지 본다.
 *
 * 배치가 확정한 기준일과 조회가 찾는 기준일이 어긋나면 그날 목록이 통째로 비어 보인다.
 * 그 어긋남은 호스트 시간대가 KST 가 아닐 때만 드러나므로, 시간대를 바꿔 가며 검증한다.
 */
class ExpiringSoonPolicyTest {

    /*
     * 한국 자정 직후. UTC 로는 아직 전날 15시다.
     * 호스트 시간대를 따라가면 하루 이전 날짜가 나온다.
     */
    private static final Instant 한국_자정_직후 = Instant.parse("2026-08-31T15:00:30Z");

    @Test
    void 호스트가_UTC_여도_한국_날짜를_준다() {
        Clock utcClock = Clock.fixed(한국_자정_직후, ZoneId.of("UTC"));

        assertThat(ExpiringSoonPolicy.businessToday(utcClock))
                .isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void 호스트가_KST_여도_같은_날짜를_준다() {
        Clock kstClock = Clock.fixed(한국_자정_직후, ZoneId.of("Asia/Seoul"));

        assertThat(ExpiringSoonPolicy.businessToday(kstClock))
                .isEqualTo(LocalDate.of(2026, 9, 1));
    }

    /*
     * 한국 자정 직전. 아직 전날이어야 한다.
     * 경계 한쪽만 보면 시간대를 통째로 하루 밀어도 통과하므로 반대편도 함께 잠근다.
     */
    @Test
    void 한국_자정_직전은_아직_전날이다() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T14:59:30Z"), ZoneId.of("UTC"));

        assertThat(ExpiringSoonPolicy.businessToday(clock))
                .isEqualTo(LocalDate.of(2026, 8, 31));
    }

    // 호스트 시간대가 무엇이든 같은 순간에는 같은 날짜여야 한다
    @Test
    void 호스트_시간대가_달라도_결과가_같다() {
        Instant 같은_순간 = Instant.parse("2026-09-01T05:00:00Z");

        LocalDate utc = ExpiringSoonPolicy.businessToday(Clock.fixed(같은_순간, ZoneId.of("UTC")));
        LocalDate ny = ExpiringSoonPolicy.businessToday(Clock.fixed(같은_순간, ZoneId.of("America/New_York")));
        LocalDate kst = ExpiringSoonPolicy.businessToday(Clock.fixed(같은_순간, ZoneId.of("Asia/Seoul")));

        assertThat(utc).isEqualTo(ny).isEqualTo(kst);
    }
}
