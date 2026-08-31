package com.freshmarket.admin.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AdminTest {

    @Test
    void toAuthority_는_ROLE_접두사를_붙인다() {
        assertThat(AdminRole.ADMIN.toAuthority()).isEqualTo("ROLE_ADMIN");
        assertThat(AdminRole.SUPER_ADMIN.toAuthority()).isEqualTo("ROLE_SUPER_ADMIN");
    }

    @Test
    void 관리자_등록시_활성_상태로_생성된다() {
        Admin admin = Admin.register("admin.kim", "hash", "관리자", AdminRole.ADMIN);

        assertThat(admin.isActive()).isTrue();
        assertThat(admin.getLoginId()).isEqualTo("admin.kim");
        assertThat(admin.getRole()).isEqualTo(AdminRole.ADMIN);
    }

    @Test
    void 관리자_등록시_필수값이_없으면_예외가_발생한다() {
        assertThatThrownBy(() -> Admin.register(null, "hash", "관리자", AdminRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Admin.register("admin.kim", "", "관리자", AdminRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Admin.register("admin.kim", "hash", " ", AdminRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Admin.register("admin.kim", "hash", "관리자", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 관리자_등록시_길이_상한을_넘으면_예외가_발생한다() {
        // login_id VARCHAR(50), password_hash VARCHAR(255), name VARCHAR(50) (V1__init_schema.sql)
        String over50 = "a".repeat(51);
        String over255 = "a".repeat(256);

        assertThatThrownBy(() -> Admin.register(over50, "hash", "관리자", AdminRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Admin.register("admin.kim", over255, "관리자", AdminRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Admin.register("admin.kim", "hash", over50, AdminRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);

        // 상한과 정확히 같은 길이는 통과해야 한다 (경계값)
        assertThat(Admin.register("a".repeat(50), "hash", "a".repeat(50), AdminRole.ADMIN)).isNotNull();
    }

    @Test
    void 리프레시_토큰_발급시_필수값이_없으면_예외가_발생한다() {
        Admin admin = AdminFixture.active("admin.kim", "hash", AdminRole.ADMIN);
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(1);

        assertThatThrownBy(() -> admin.issueRefreshToken(null, expiresAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.issueRefreshToken("hash", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 로그아웃용_토큰_폐기는_계정_상태를_바꾸지_않고_멱등적이다() {
        Admin admin = AdminFixture.active("admin.kim", "hash", AdminRole.ADMIN);
        admin.issueRefreshToken("a".repeat(64), LocalDateTime.now().plusDays(1));

        admin.revokeRefreshToken();
        admin.revokeRefreshToken();

        assertThat(admin.isActive()).isTrue();
        assertThat(admin.getDeletedAt()).isNull();
        assertThat(admin.getRefreshTokenHash()).isNull();
        assertThat(admin.getRefreshTokenExpiresAt()).isNull();
    }

    @Test
    void 비활성화_시각이_없으면_예외가_발생한다() {
        Admin admin = AdminFixture.active("admin.kim", "hash", AdminRole.ADMIN);

        assertThatThrownBy(() -> admin.deactivate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 이미_비활성화된_계정을_다시_비활성화하면_예외가_발생한다() {
        // given
        Admin admin = AdminFixture.inactive("admin.kim", "hash", AdminRole.ADMIN);

        LocalDateTime deactivatedAt = LocalDateTime.now();

        // when, then
        assertThatThrownBy(() -> admin.deactivate(deactivatedAt))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 활성_계정을_비활성화하면_상태와_리프레시_토큰이_함께_정리된다() {
        // given
        Admin admin = AdminFixture.active("admin.kim", "hash", AdminRole.ADMIN);
        admin.issueRefreshToken("a".repeat(64), LocalDateTime.now().plusDays(1));

        // when
        admin.deactivate(LocalDateTime.now());

        // then
        assertThat(admin.isActive()).isFalse();
        assertThat(admin.getRefreshTokenHash()).isNull();
        assertThat(admin.getRefreshTokenExpiresAt()).isNull();
    }
}