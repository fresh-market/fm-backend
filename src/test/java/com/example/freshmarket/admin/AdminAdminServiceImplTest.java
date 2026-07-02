package com.example.freshmarket.admin;

import com.example.freshmarket.common.exception.BusinessException;
import com.example.freshmarket.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAdminServiceImplTest {

    @Mock
    private AdminRepository adminRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AdminAdminServiceImpl adminAdminService;

    @Test
    void 로그인_성공() {

        // given
        Admin admin = Admin.builder()
                .loginId("admin")
                .passwordHash("password")
                .name("관리자")
                .role(AdminRole.ADMIN)
                .build();

        when(adminRepository.findByLoginId("admin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("password", "password")).thenReturn(true);

        // when
        AdminLoginResponse response = adminAdminService.login("admin", "password");

        // then
        assertThat(response.name()).isEqualTo("관리자");
        assertThat(response.role()).isEqualTo(AdminRole.ADMIN);
    }

    @Test
    void 로그인_실패_잘못된ID입력() {

        // given
        when(adminRepository.findByLoginId(any())).thenReturn(Optional.empty());

        // when then
        assertThatThrownBy(() -> adminAdminService.login("admin", "password"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_NOT_FOUND);
    }

    @Test
    void 로그인실패_잘못된PW입력() {

        // given
        Admin admin = Admin.builder()
                .loginId("admin")
                .passwordHash("password")
                .name("관리자")
                .role(AdminRole.ADMIN)
                .build();

        when(adminRepository.findByLoginId("admin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("admin", "password")).thenReturn(false);

        // when then
        assertThatThrownBy(() -> adminAdminService.login("admin", "wrongPassword"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_PASSWORD);


    }
}