package com.freshmarket.member.domain.client;

import com.freshmarket.common.logging.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

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
        } catch (Exception e) {
            log.warn("event=KAKAO_LOGOUT_FAILED kakaoUserId={}", PiiMasker.maskProviderId(kakaoUserId), e);
        }
    }
}
