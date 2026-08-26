package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminRepository;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 로그인에서 DB 잠금과 Refresh Token 백업 갱신만 짧은 트랜잭션으로 처리한다.
 * JWT 생성과 Redis 저장은 AdminAuthService가 이 트랜잭션 밖에서 수행한다.
 */
@Service
@RequiredArgsConstructor
class AdminLoginTransactionService {

    private final AdminRepository adminRepository;

    /**
     * 비밀번호 검증이 끝난 관리자 행을 잠근 뒤 현재 활성 상태를 다시 확인하고
     * 로그인에 사용할 Refresh Token 해시/만료시각만 DB에 기록한다.
     */
    @Transactional(timeout = 1)
    LoginDbState issueRefreshToken(Long adminId, String refreshTokenHash, LocalDateTime expiresAt) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(refreshTokenHash, "refreshTokenHash");
        Objects.requireNonNull(expiresAt, "expiresAt");

        Admin admin = adminRepository.findByIdForUpdate(adminId)
                .orElseThrow(() -> new AdminException(AdminErrorCode.LOGIN_FAILED));
        if (!admin.isActive()) {
            throw new AdminException(AdminErrorCode.LOGIN_FAILED);
        }

        admin.issueRefreshToken(refreshTokenHash, expiresAt);
        return new LoginDbState(admin.getId(), admin.getLoginId(), admin.getName(), admin.getRole());
    }

    /**
     * 지연 정리 또는 로그아웃 재처리 시 사용한다.
     * 현재 DB의 Refresh Token hash가 지정한 hash와 같을 때만 제거한다.
     */
    @Transactional(timeout = 1)
    void clearRefreshTokenIfMatches(Long adminId, String refreshTokenHash) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(refreshTokenHash, "refreshTokenHash");
        adminRepository.clearRefreshTokenIfMatches(adminId, refreshTokenHash);
    }

    record LoginDbState(Long adminId, String loginId, String name, AdminRole role) {
    }
}