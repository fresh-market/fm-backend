package com.freshmarket.member.domain.controller;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.domain.service.MemberWithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 카카오 연결 해제 웹훅 — 응답은 항상 200이어야 하는 카카오 스펙이라 ResponseEnvelope를 쓰지 않는다. */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "카카오 연동", description = "카카오 연결 해제 웹훅")
class KakaoUnlinkWebhookController {

    private final MemberWithdrawalService memberWithdrawalService;

    @Value("${kakao.app-id}")
    private String ourKakaoAppId;

    @Value("${kakao.admin-key}")
    private String adminKey;

    @RequestMapping(value = "/webhook/kakao/unlink", method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(summary = "카카오 연결 해제 웹훅", description = "카카오가 연결 해제를 알리면 회원 탈퇴 처리를 수행한다. 카카오 규격에 따라 처리 실패에도 200을 반환한다.")
    @ApiResponse(responseCode = "200", description = "웹훅 수신 완료")
    public ResponseEntity<Void> handleUnlink(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam("app_id") String appId,
            @RequestParam("user_id") String userId,
            @RequestParam(value = "referrer_type", required = false) String referrerType
    ) {
        if (!ourKakaoAppId.equals(appId)) {
            log.warn("event=KAKAO_UNLINK_WEBHOOK_APP_ID_MISMATCH receivedAppId={} userId={}", appId, PiiMasker.maskProviderId(userId));
            return ResponseEntity.ok().build();
        }

        if (!("KakaoAK " + adminKey).equals(authorization)) {
            log.warn("event=KAKAO_UNLINK_WEBHOOK_AUTH_MISMATCH userId={}", PiiMasker.maskProviderId(userId));
            return ResponseEntity.ok().build();
        }

        log.info("event=KAKAO_UNLINK_WEBHOOK userId={} referrerType={}", PiiMasker.maskProviderId(userId), referrerType);

        try {
            memberWithdrawalService.withdrawByKakaoWebhook(userId);
        } catch (Exception e) {
            log.error("event=KAKAO_UNLINK_WEBHOOK_PROCESSING_FAILED userId={}", PiiMasker.maskProviderId(userId), e);
        }

        return ResponseEntity.ok().build();
    }
}
