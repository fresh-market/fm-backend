package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminAuditLog;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminAuditLogRepository;
import com.freshmarket.admin.domain.repository.AdminRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 관리자 로그아웃의 DB 작업만 짧은 트랜잭션으로 처리한다.
 * Redis 작업은 AdminAuthService가 이 트랜잭션 밖에서 수행한다.
 */
@Service
@RequiredArgsConstructor
class AdminLogoutTransactionService {

    private final AdminRepository adminRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;

    /**
     * DB를 관리자 Refresh Token의 최종 판정 기준으로 사용하기 위해 먼저 DB 백업을 폐기한다.
     * 같은 관리자에 대한 로그인/로그아웃 갱신이 겹쳐 토큰 상태를 덮어쓰지 않도록 관리자 행을 잠근다.
     * Redis 정리에 사용할 기존 해시는 트랜잭션이 끝난 뒤 호출자가 사용할 수 있도록 반환한다.
     */
    @Transactional(timeout = 1)
    LogoutDbState revokeRefreshToken(Long adminId) {
        Objects.requireNonNull(adminId, "adminId");

        Admin admin = adminRepository.findByIdForUpdate(adminId)
                .orElseThrow(() -> new AdminException(AdminErrorCode.ADMIN_NOT_FOUND));

        String refreshTokenHash = admin.getRefreshTokenHash();
        admin.revokeRefreshToken();

        return new LogoutDbState(refreshTokenHash);
    }


    /** Access Token 차단까지 확정된 뒤 성공 감사 로그를 별도 짧은 트랜잭션으로 기록한다. */
    @Transactional(timeout = 1)
    void recordSuccess(Long adminId) {
        adminAuditLogRepository.save(
                AdminAuditLog.of(adminId, "ADMIN_LOGOUT", String.valueOf(adminId), null));
    }

    record LogoutDbState(String refreshTokenHash) {
    }
}