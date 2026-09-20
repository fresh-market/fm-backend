package com.freshmarket.coupon.internal.redis;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * 재건 락의 값이다. 주인을 가리는 토큰과 재건이 시작된 시각을 함께 담는다.
 *
 * <p><b>시작 시각을 여기 둔 이유는 키를 안 늘리려고다.</b> 기여자는 자기가 얼마나 늦게 올렸는지를
 * 알아야 하는데, 그 기준이 되는 시각을 새 키에 두면 지울 자리가 하나 더 생긴다. 락은 재건이 도는
 * 동안 반드시 살아 있고 끝나면 사라지므로 이 값을 실을 자리로 맞다.
 *
 * <p><b>시각은 앱 시계로 잰다.</b> 순번 확보 스크립트가 Redis 시계를 쓰는 것과 다른 선택인데,
 * 그쪽은 회수가 살아 있는 요청의 번호를 뺏느냐를 가르는 판정이고 이쪽은 값을 좁히려고 모으는
 * 지표다. 인스턴스 사이 시계 차이는 밀리초 단위이고, 재려는 것은 수백 밀리초에서 수 초다.
 */
record RebuildLock(String token, long startedAtMillis) {

    private static final char SEPARATOR = '|';

    static RebuildLock start() {
        return new RebuildLock(UUID.randomUUID().toString(), System.currentTimeMillis());
    }

    String value() {
        return token + SEPARATOR + startedAtMillis;
    }

    /**
     * 락 값에서 시작 시각을 꺼낸다.
     *
     * <p><b>못 읽으면 비워서 준다.</b> 배포가 도는 동안에는 시작 시각이 없는 옛 형식의 락이 있을 수
     * 있고, 재건이 이미 끝나 락이 사라졌을 수도 있다. 지표 하나 때문에 기여를 막으면 안 된다.
     */
    static OptionalLong startedAtOf(String raw) {
        if (raw == null) {
            return OptionalLong.empty();
        }
        int at = raw.lastIndexOf(SEPARATOR);
        if (at < 0) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(raw.substring(at + 1)));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
