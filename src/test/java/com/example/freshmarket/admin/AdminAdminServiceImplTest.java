package com.example.freshmarket.admin;

import com.example.freshmarket.auditlog.AuditLogRepository;
import com.example.freshmarket.common.exception.BusinessException;
import com.example.freshmarket.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAdminServiceImplTest {

    @Mock
    private AdminRepository adminRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AdminAdminServiceImpl adminAdminService;

    // ===== 테스트용 Admin 생성 헬퍼 =====

    private Admin createAdmin(String loginId, String passwordHash, String name, AdminRole role) {
        return Admin.builder()
                .loginId(loginId)
                .passwordHash(passwordHash)
                .name(name)
                .role(role)
                .build();
    }

    private Admin createSuperAdmin() {
        return createAdmin("superadmin", "encodedPassword", "최고관리자", AdminRole.SUPER_ADMIN);
    }

    private Admin createNormalAdmin() {
        return createAdmin("normal", "encodedPassword", "일반관리자", AdminRole.ADMIN);
    }

    @Test
    void 로그인_성공() {

        // given
        Admin admin = createAdmin("admin", "password", "관리자", AdminRole.ADMIN);

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
        Admin admin = createAdmin("admin", "password", "관리자", AdminRole.ADMIN);

        when(adminRepository.findByLoginId("admin")).thenReturn(Optional.of(admin));
        // login("admin", "wrongPassword") 호출 시 실제로는
        // matches("wrongPassword", admin.getPasswordHash()="password")가 호출된다
        when(passwordEncoder.matches("wrongPassword", "password")).thenReturn(false);

        // when then
        assertThatThrownBy(() -> adminAdminService.login("admin", "wrongPassword"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_PASSWORD);

    }

    @Test
    void 관리자등록_성공() {

        // given
        AdminRegisterRequest request = new AdminRegisterRequest(
                "admin",
                "password",
                "관리자");

        // 요청을 보내는 주체(SUPER_ADMIN) - 등록되는 대상 admin과는 별개
        Admin superAdmin = createSuperAdmin();

        // save()로 실제 저장됐다고 가정할 결과 admin
        Admin savedAdmin = createAdmin(request.loginId(), "encodedPassword", request.name(), AdminRole.ADMIN);
        // id는 Builder로 못 받음(DB가 채워주는 값) -> register()가 savedAdmin.getId()를 쓰므로
        // 실제 저장된 것처럼 테스트에서만 강제로 채워줌
        ReflectionTestUtils.setField(savedAdmin, "id", 1L);

        // when
        when(adminRepository.findById(1L)).thenReturn(Optional.of(superAdmin));
        when(adminRepository.existsByLoginId("admin")).thenReturn(false);
        when(passwordEncoder.encode("password")).thenReturn("encodedPassword");
        // request.toEntity(...)는 호출할 때마다 새 인스턴스를 만들기 때문에(equals 미구현),
        // 구체적인 인스턴스로 매칭하면 실제 register() 내부 호출과 절대 일치하지 않는다 -> any() 사용
        when(adminRepository.save(any(Admin.class))).thenReturn(savedAdmin);

        AdminRegisterResponse response = adminAdminService.register(request, 1L);

        // then
        assertThat(response.name()).isEqualTo("관리자");
        assertThat(response.role()).isEqualTo(AdminRole.ADMIN);
    }

    @Test
    void 관리자등록_실패_요청자없음() {

        // given
        AdminRegisterRequest request = new AdminRegisterRequest("admin", "password", "관리자");
        when(adminRepository.findById(1L)).thenReturn(Optional.empty());

        // when then
        assertThatThrownBy(() -> adminAdminService.register(request, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_NOT_FOUND);
    }

    @Test
    void 관리자등록_실패_요청자가_SUPER_ADMIN_아님() {

        // given
        AdminRegisterRequest request = new AdminRegisterRequest("admin", "password", "관리자");
        Admin normalAdmin = createNormalAdmin();

        when(adminRepository.findById(1L)).thenReturn(Optional.of(normalAdmin));

        // when then
        assertThatThrownBy(() -> adminAdminService.register(request, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_SUPER_ADMIN);
    }

    @Test
    void 관리자등록_실패_로그인아이디중복() {

        // given
        AdminRegisterRequest request = new AdminRegisterRequest("admin", "password", "관리자");
        Admin superAdmin = createSuperAdmin();

        when(adminRepository.findById(1L)).thenReturn(Optional.of(superAdmin));
        when(adminRepository.existsByLoginId("admin")).thenReturn(true);

        // when then
        assertThatThrownBy(() -> adminAdminService.register(request, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DUPLICATE_LOGIN_ID);

        // 중복이면 save까지 가면 안 됨
        verify(adminRepository, never()).save(any(Admin.class));
    }

    @Test
    void 관리자삭제_성공() {

        // given
        Admin superAdmin = createSuperAdmin();

        when(adminRepository.findById(1L)).thenReturn(Optional.of(superAdmin));
        when(adminRepository.existsById(2L)).thenReturn(true);

        // when
        adminAdminService.deleteAdmin(2L, 1L);

        // then
        verify(auditLogRepository).save(any());
        verify(adminRepository).deleteById(2L);
    }

    @Test
    void 관리자삭제_실패_요청자없음() {

        // given
        when(adminRepository.findById(1L)).thenReturn(Optional.empty());

        // when then
        assertThatThrownBy(() -> adminAdminService.deleteAdmin(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_NOT_FOUND);

        verify(adminRepository, never()).deleteById(any());
    }

    @Test
    void 관리자삭제_실패_요청자가_SUPER_ADMIN_아님() {

        // given
        Admin normalAdmin = createNormalAdmin();

        when(adminRepository.findById(1L)).thenReturn(Optional.of(normalAdmin));

        // when then
        assertThatThrownBy(() -> adminAdminService.deleteAdmin(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_SUPER_ADMIN);

        verify(adminRepository, never()).deleteById(any());
    }

    @Test
    void 관리자삭제_실패_대상관리자없음() {

        // given
        Admin superAdmin = createSuperAdmin();

        when(adminRepository.findById(1L)).thenReturn(Optional.of(superAdmin));
        when(adminRepository.existsById(2L)).thenReturn(false);

        // when then
        assertThatThrownBy(() -> adminAdminService.deleteAdmin(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ADMIN_NOT_FOUND);

        verify(adminRepository, never()).deleteById(any());
    }
}
