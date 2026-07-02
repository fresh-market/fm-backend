package com.example.freshmarket.member;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberTest {

    @Test
    void 회원가입하면_상태가_ACTIVE로_시작한다() {
        // given, when
        Member member = Member.register(
                "kim@example.com", "encodedPassword", "김철수", "chulsoo",
                "010-1234-5678", 1L, true
        );

        // then
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.getDeletedAt()).isNull();
    }

    @Test
    void 탈퇴하면_상태가_WITHDRAWN이_되고_deletedAt이_기록된다() {
        // given
        Member member = Member.register(
                "kim@example.com", "encodedPassword", "김철수", "chulsoo",
                "010-1234-5678", 1L, true
        );

        // when
        member.withdraw();

        // then
        assertThat(member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(member.getDeletedAt()).isNotNull();
        assertThat(member.isWithdrawn()).isTrue();
    }

    @Test
    void 차단하면_상태만_BLOCKED로_바뀌고_deletedAt은_그대로다() {
        // given
        Member member = Member.register(
                "kim@example.com", "encodedPassword", "김철수", "chulsoo",
                "010-1234-5678", 1L, true
        );

        // when
        member.block();

        // then
        assertThat(member.getStatus()).isEqualTo(MemberStatus.BLOCKED);
        assertThat(member.getDeletedAt()).isNull();
    }
}