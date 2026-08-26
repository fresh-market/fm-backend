package com.freshmarket.member.domain.service.kakao;

import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import com.freshmarket.member.domain.exception.MemberErrorCode;
import com.freshmarket.member.domain.exception.MemberException;
import com.freshmarket.member.domain.repository.KakaoUnlinkFailureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 포기된 카카오 unlink 실패 건의 운영자 확인 완료를 기록한다. */
@Service
@RequiredArgsConstructor
public class KakaoUnlinkFailureResolutionService {

    private final KakaoUnlinkFailureRepository failureRepository;

    @Transactional(readOnly = true)
    public Page<KakaoUnlinkFailure> getStuckFailures(Pageable pageable) {
        return failureRepository.findByAttemptCountGreaterThanEqualAndResolvedFalse(
                KakaoUnlinkFailure.MAX_RETRY_ATTEMPTS, pageable);
    }

    @Transactional
    public void resolve(Long failureId) {
        KakaoUnlinkFailure failure = failureRepository.findById(failureId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.KAKAO_UNLINK_FAILURE_NOT_FOUND));
        if (!failure.shouldGiveUp()) {
            throw new MemberException(MemberErrorCode.KAKAO_UNLINK_FAILURE_NOT_GAVE_UP);
        }
        failure.resolve();
    }
}
