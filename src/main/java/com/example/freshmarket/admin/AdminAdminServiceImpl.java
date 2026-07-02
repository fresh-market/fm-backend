package com.example.freshmarket.admin;

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
}
