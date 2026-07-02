package com.example.freshmarket.member;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void 회원가입시_필수값이_비어있으면_예외가_발생한다() {
        // given, when, then
        assertThatThrownBy(() -> Member.register(
                "", "encodedPassword", "김철수", "chulsoo",
                "010-1234-5678", 1L, true
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 회원가입시_등급이_없으면_예외가_발생한다() {
        // given, when, then
        assertThatThrownBy(() -> Member.register(
                "kim@example.com", "encodedPassword", "김철수", "chulsoo",
                "010-1234-5678", null, true
        )).isInstanceOf(NullPointerException.class);
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

    @Test
    void 차단된_회원을_재활성화하면_상태가_ACTIVE로_바뀐다() {
        // given
        Member member = Member.register(
                "kim@example.com", "encodedPassword", "김철수", "chulsoo",
                "010-1234-5678", 1L, true
        );
        member.block();

        // when
        member.reactivate();

        // then
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void 닉네임_전화번호_등급을_변경할_수_있다() {
        // given
        Member member = Member.register(
                "kim@example.com", "encodedPassword", "김철수", "chulsoo",
                "010-1234-5678", 1L, true
        );

        // when
        member.changeNickname("newNickname");
        member.changePhone("010-9999-8888");
        member.changeGrade(2L);

        // then
        assertThat(member.getNickname()).isEqualTo("newNickname");
        assertThat(member.getPhone()).isEqualTo("010-9999-8888");
        assertThat(member.getGradeId()).isEqualTo(2L);
    }

    @Test
    void 탈퇴한_회원은_차단할_수_없다() {
        // given
        Member member = Member.register(
                "kim@example.com", "encodedPassword", "김철수", "chulsoo",
                "010-1234-5678", 1L, true
        );
        member.withdraw();

        // when, then
        assertThatThrownBy(member::block).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 탈퇴한_회원은_재활성화할_수_없다() {
        // given
        Member member = Member.register(
                "kim@example.com", "encodedPassword", "김철수", "chulsoo",
                "010-1234-5678", 1L, true
        );
        member.withdraw();

        // when, then
        assertThatThrownBy(member::reactivate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 탈퇴한_회원은_닉네임_전화번호_등급을_변경할_수_없다() {
        // given
        Member member = Member.register(
                "kim@example.com", "encodedPassword", "김철수", "chulsoo",
                "010-1234-5678", 1L, true
        );
        member.withdraw();

        // when, then
        assertThatThrownBy(() -> member.changeNickname("newNickname"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> member.changePhone("010-9999-8888"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> member.changeGrade(2L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 영속화_전인_서로_다른_신규_회원은_같지_않다() {
        // given
        Member member1 = Member.register(
                "kim@example.com", "encodedPassword", "김철수", "chulsoo",
                "010-1234-5678", 1L, true
        );
        Member member2 = Member.register(
                "lee@example.com", "encodedPassword", "이영희", "younghee",
                "010-1111-2222", 1L, true
        );

        // when, then
        assertThat(member1).isNotEqualTo(member2);
    }
}
