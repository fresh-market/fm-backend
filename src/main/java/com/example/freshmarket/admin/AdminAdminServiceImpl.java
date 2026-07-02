package com.example.freshmarket.admin;

import com.example.freshmarket.auditlog.AuditLog;
import com.example.freshmarket.auditlog.AuditLogRepository;
import com.example.freshmarket.common.exception.BusinessException;
import com.example.freshmarket.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAdminServiceImpl implements AdminAdminService {

    private final AdminRepository adminRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;

    // 현재 시큐리티 미적용
    @Override
    public AdminLoginResponse login(String loginId, String password) {

        Admin admin = adminRepository.findByLoginId(loginId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));

        if (!passwordEncoder.matches(password, admin.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        return AdminLoginResponse.from(admin);
    }

    @Override
    @Transactional
    public AdminRegisterResponse register(AdminRegisterRequest request, Long id) {

        Admin admin = adminRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));

        if (admin.getRole() != AdminRole.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.NOT_SUPER_ADMIN);
        }

        if (adminRepository.existsByLoginId(request.loginId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_LOGIN_ID);
        }

        String encodedPassword = passwordEncoder.encode(request.password());
        Admin savedAdmin = adminRepository.save(request.toEntity(encodedPassword));

        auditLogRepository.save(AuditLog.create(
                id,
                "관리자 등록",
                savedAdmin.getId().toString() + "번 관리자",
                null));

        return AdminRegisterResponse.from(savedAdmin);
    }

//    @Override
//    @Transactional
//    public void changeRole(Long targetAdminId, AdminRole newRole, Long requesterId) {
//        Admin admin = adminRepository.findById(requesterId)
//                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));
//
//        if (admin.getRole() != AdminRole.SUPER_ADMIN) {
//            throw new BusinessException(ErrorCode.NOT_SUPER_ADMIN);
//        }
//
//        Admin targetAdmin = adminRepository.findById(targetAdminId)
//                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));
//
//        targetAdmin.updateRole(newRole);
//    }

    @Override
    @Transactional
    public void deleteAdmin(Long targetAdminId, Long requesterId) {

        Admin admin = adminRepository.findById(requesterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_NOT_FOUND));

        if (admin.getRole() != AdminRole.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.NOT_SUPER_ADMIN);
        }

        if (!adminRepository.existsById(targetAdminId)) {
            throw new BusinessException(ErrorCode.ADMIN_NOT_FOUND);
        }

        auditLogRepository.save(AuditLog.create(
                requesterId,
                "관리자 삭제",
                targetAdminId + "번 관리자",
                null));

        adminRepository.deleteById(targetAdminId);
    }
}
