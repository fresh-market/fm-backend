package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.dto.AdminAccountIssueResponse;
import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminAuditLog;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminAuditLogRepository;
import com.freshmarket.admin.domain.repository.AdminRepository;
import com.freshmarket.common.logging.PiiMasker;
import java.security.SecureRandom;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class AdminAccountService {

    private static final String SUPER_ADMIN_AUTHORITY = AdminRole.SUPER_ADMIN.toAuthority();
    private static final int TEMPORARY_PASSWORD_LENGTH = 16;
    private static final char[] UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final char[] LOWER = "abcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final char[] DIGITS = "23456789".toCharArray();
    private static final char[] SYMBOLS = "!@#$%^&*".toCharArray();
    private static final char[] ALL = (
            "ABCDEFGHJKLMNPQRSTUVWXYZ"
                    + "abcdefghijkmnopqrstuvwxyz"
                    + "23456789"
                    + "!@#$%^&*").toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminAuditLogRepository adminAuditLogRepository;

    public AdminAccountService(
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            AdminAuditLogRepository adminAuditLogRepository) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminAuditLogRepository = adminAuditLogRepository;
    }

    /**
     * 최고관리자가 새 관리자 계정을 발급한다.
     * 임시 비밀번호 원문은 응답으로 한 번만 돌려주고 DB에는 BCrypt 해시만 저장한다.
     */
    @Transactional(timeout = 5)
    public AdminAccountIssueResponse issue(
            Long issuerAdminId,
            String issuerRole,
            String loginId,
            String name,
            AdminRole role) {

        if (issuerAdminId == null || issuerRole == null || issuerRole.isBlank()) {
            throw new AdminException(AdminErrorCode.SUPER_ADMIN_REQUIRED);
        }
        if (!SUPER_ADMIN_AUTHORITY.equals(issuerRole)) {
            throw new AdminException(AdminErrorCode.SUPER_ADMIN_REQUIRED);
        }

        validateIssueInput(loginId, name, role);

        if (adminRepository.existsByLoginId(loginId)) {
            throw new AdminException(AdminErrorCode.LOGIN_ID_DUPLICATED);
        }

        String temporaryPassword = generateTemporaryPassword();
        String passwordHash = passwordEncoder.encode(temporaryPassword);
        Admin admin = Admin.register(loginId, passwordHash, name, role);

        try {
            // 사전 중복 검사 직후 다른 요청이 같은 아이디를 넣는 경쟁 상황도 UNIQUE KEY로 한 번 더 막는다.
            adminRepository.saveAndFlush(admin);
        } catch (DataIntegrityViolationException e) {
            throw new AdminException(AdminErrorCode.LOGIN_ID_DUPLICATED, e);
        }

        adminAuditLogRepository.save(AdminAuditLog.of(
                issuerAdminId,
                "ADMIN_ACCOUNT_ISSUE",
                String.valueOf(admin.getId()),
                "role=" + admin.getRole().name()));

        log.info("event=ADMIN_ACCOUNT_ISSUE success=true issuerAdminId={} loginId={} role={}",
                issuerAdminId, PiiMasker.maskGeneric(admin.getLoginId(), 2, 1), admin.getRole());

        return new AdminAccountIssueResponse(
                admin.getLoginId(),
                admin.getName(),
                admin.getRole(),
                temporaryPassword);
    }

    private static void validateIssueInput(String loginId, String name, AdminRole role) {
        Objects.requireNonNull(loginId, "loginId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");

        if (loginId.isBlank()) {
            throw new IllegalArgumentException("loginId 는 필수다");
        }
        if (loginId.length() > 50) {
            throw new IllegalArgumentException("loginId 는 50자를 넘을 수 없다: " + loginId.length());
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 은 필수다");
        }
        if (name.length() > 50) {
            throw new IllegalArgumentException("name 은 50자를 넘을 수 없다: " + name.length());
        }
    }

    private String generateTemporaryPassword() {
        char[] password = new char[TEMPORARY_PASSWORD_LENGTH];
        password[0] = pick(UPPER);
        password[1] = pick(LOWER);
        password[2] = pick(DIGITS);
        password[3] = pick(SYMBOLS);

        for (int i = 4; i < password.length; i++) {
            password[i] = pick(ALL);
        }

        for (int i = password.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char temp = password[i];
            password[i] = password[j];
            password[j] = temp;
        }
        return new String(password);
    }

    private char pick(char[] candidates) {
        return candidates[RANDOM.nextInt(candidates.length)];
    }
}