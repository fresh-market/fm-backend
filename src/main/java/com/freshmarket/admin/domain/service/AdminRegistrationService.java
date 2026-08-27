package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.dto.AdminRegistrationRequest;
import com.freshmarket.admin.domain.dto.AdminRegistrationResponse;
import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminAuditLog;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminAuditLogRepository;
import com.freshmarket.admin.domain.repository.AdminRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminRegistrationService {

    private static final String SUPER_ADMIN_AUTHORITY = "ROLE_SUPER_ADMIN";
    private static final String AUDIT_ACTION = "ADMIN_ACCOUNT_CREATE";
    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final AdminRepository adminRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final PasswordEncoder passwordEncoder;

    /*
     * 계정 발급과 감사 로그를 같은 DB 트랜잭션에 둔다.
     * 감사 로그 저장이 실패했는데 계정만 생성되면 최고관리자 행위 추적이 끊기므로 둘 중 하나라도 실패하면 함께 롤백한다.
     */
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public AdminRegistrationResponse register(
            Long issuerAdminId,
            String issuerRole,
            AdminRegistrationRequest request) {
        Objects.requireNonNull(issuerAdminId, "issuerAdminId");
        Objects.requireNonNull(issuerRole, "issuerRole");
        Objects.requireNonNull(request, "request");

        if (!SUPER_ADMIN_AUTHORITY.equals(issuerRole)) {
            throw new AdminException(AdminErrorCode.SUPER_ADMIN_REQUIRED);
        }

        if (adminRepository.findByLoginId(request.loginId()).isPresent()) {
            throw new AdminException(AdminErrorCode.LOGIN_ID_DUPLICATED);
        }

        String passwordHash = passwordEncoder.encode(request.initialPassword());
        Admin admin = Admin.register(request.loginId(), passwordHash, request.name(), request.role());

        final Admin saved;
        try {
            // 사전 중복 검사 뒤 동시에 같은 아이디가 들어오는 경쟁 상황도 DB UNIQUE 제약으로 막고
            // 그 예외를 API 계약의 ADMIN-006으로 변환하기 위해 flush까지 이 메서드 안에서 수행한다.
            saved = adminRepository.saveAndFlush(admin);
        } catch (DataIntegrityViolationException e) {
            throw new AdminException(AdminErrorCode.LOGIN_ID_DUPLICATED, e);
        }

        adminAuditLogRepository.save(AdminAuditLog.of(
                issuerAdminId,
                AUDIT_ACTION,
                saved.getLoginId(),
                "role=" + saved.getRole().name()));

        return AdminRegistrationResponse.from(saved);
    }
}