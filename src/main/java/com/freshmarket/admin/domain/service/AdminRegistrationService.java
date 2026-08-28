package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.dto.AdminRegistrationRequest;
import com.freshmarket.admin.domain.dto.AdminRegistrationResponse;
import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminAuditLog;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminAuditLogRepository;
import com.freshmarket.admin.domain.repository.AdminRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class AdminRegistrationService {

    private static final String SUPER_ADMIN_AUTHORITY = "ROLE_SUPER_ADMIN";
    private static final String AUDIT_ACTION = "ADMIN_ACCOUNT_CREATE";
    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final AdminRepository adminRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformTransactionManager transactionManager;

    /*
     * BCrypt 해시는 DB 트랜잭션 밖에서 계산한다.
     * 계정 저장과 감사 로그 저장만 같은 DB 트랜잭션에 묶어 둘 중 하나라도 실패하면 함께 롤백한다.
     */
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

        // 이미 존재하는 아이디에는 비용이 큰 BCrypt 계산을 하지 않는다.
        if (adminRepository.findByLoginId(request.loginId()).isPresent()) {
            throw new AdminException(AdminErrorCode.LOGIN_ID_DUPLICATED);
        }

        String passwordHash = passwordEncoder.encode(request.initialPassword());

        try {
            TransactionTemplate writeTransaction = new TransactionTemplate(transactionManager);
            writeTransaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
            return Objects.requireNonNull(
                    writeTransaction.execute(status -> saveAdminAndAudit(issuerAdminId, request, passwordHash)),
                    "registrationResult");
        } catch (DataIntegrityViolationException e) {
            // 사전 중복 검사 이후 동시에 같은 아이디가 생성되는 경쟁 상황은 DB UNIQUE 제약으로 최종 차단한다.
            throw new AdminException(AdminErrorCode.LOGIN_ID_DUPLICATED, e);
        }
    }

    private AdminRegistrationResponse saveAdminAndAudit(
            Long issuerAdminId,
            AdminRegistrationRequest request,
            String passwordHash) {
        AdminRole role = AdminRole.valueOf(request.role());
        Admin admin = Admin.register(request.loginId(), passwordHash, request.name(), role);
        Admin saved = adminRepository.saveAndFlush(admin);

        adminAuditLogRepository.save(AdminAuditLog.of(
                issuerAdminId,
                AUDIT_ACTION,
                saved.getLoginId(),
                "role=" + saved.getRole().name()));

        return AdminRegistrationResponse.from(saved);
    }
}