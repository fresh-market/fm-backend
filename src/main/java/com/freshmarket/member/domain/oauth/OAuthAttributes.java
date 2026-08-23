package com.freshmarket.member.domain.oauth;

import com.freshmarket.member.domain.entity.Member;
import com.freshmarket.member.domain.entity.SocialType;
import java.util.Map;
import lombok.Builder;

/** 카카오 OIDC 응답에서 필요한 값(sub)만 뽑아내는 어댑터. */
@Builder
public record OAuthAttributes(
        Map<String, Object> attributes,
        String nameAttributeKey,
        SocialType provider,
        String providerUserId
) {

    public static OAuthAttributes of(SocialType provider, String userNameAttributeName, Map<String, Object> attributes) {
        return switch (provider) {
            case KAKAO -> ofKakao(provider, userNameAttributeName, attributes);
        };
    }

    private static OAuthAttributes ofKakao(SocialType provider, String userNameAttributeName, Map<String, Object> attributes) {
        return OAuthAttributes.builder()
                .attributes(attributes)
                .nameAttributeKey(userNameAttributeName)
                .provider(provider)
                .providerUserId(String.valueOf(attributes.get(userNameAttributeName)))
                .build();
    }

    public Member toEntity(Long memberGradeId) {
        return Member.register(provider, providerUserId, memberGradeId);
    }
}
