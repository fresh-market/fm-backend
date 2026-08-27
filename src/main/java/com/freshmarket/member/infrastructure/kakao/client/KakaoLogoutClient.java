package com.freshmarket.member.infrastructure.kakao.client;

import com.freshmarket.common.logging.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** 서버(Admin Key) 주도로 카카오 쪽 access token을 무효화한다(연결 자체는 유지). */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoLogoutClient {

    private static final String LOGOUT_URL = "https://kapi.kakao.com/v1/user/logout";

    private final WebClient kakaoApiWebClient;

    @Value("${kakao.admin-key}")
    private String adminKey;

    public void logout(String kakaoUserId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("target_id_type", "user_id");
        form.add("target_id", kakaoUserId);

        try {
            kakaoApiWebClient.post()
                    .uri(LOGOUT_URL)
                    .header("Authorization", "KakaoAK " + adminKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException e) {
            /*
             * (2026-08-27) 401/403은 이번 요청이 잘못된 게 아니라 Admin Key 자체가 무효/만료됐다는
             * 뜻이다 — unlink와 같은 이유(KakaoUnlinkClient 참고)로 다른 실패와 이벤트명을 분리해서
             * 사람이 알아채게 한다. logout은 원래도 실패를 삼키고 넘어가는 정책(연결 자체는 유지되고
             * 우리 쪽 세션만 정리된 상태라 심각도가 낮음)이라 여기서도 던지지는 않는다.
             */
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                log.error("event=KAKAO_ADMIN_KEY_REJECTED api=logout status={} kakaoUserId={} — Admin Key 확인 필요",
                        e.getStatusCode(), PiiMasker.maskProviderId(kakaoUserId), e);
            } else {
                log.warn("event=KAKAO_LOGOUT_FAILED status={} kakaoUserId={}",
                        e.getStatusCode(), PiiMasker.maskProviderId(kakaoUserId), e);
            }
        } catch (Exception e) {
            log.warn("event=KAKAO_LOGOUT_FAILED kakaoUserId={}", PiiMasker.maskProviderId(kakaoUserId), e);
        }
    }
}
