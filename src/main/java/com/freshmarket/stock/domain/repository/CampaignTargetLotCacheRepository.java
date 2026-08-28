package com.freshmarket.stock.domain.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.stock.domain.dto.ExpiringSoonResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 회원용 떨이 쿠폰 대상 조회 결과를 캐시한다.
 *
 * <p>이 목록은 자정 배치가 확정한 뒤 하루 종일 바뀌지 않는데, 캐시가 없으면 요청마다
 * campaign_target_lot 조회 → stock_lot 조회 → ProductApi(product 도메인) 호출을 다시 탄다.
 * 선착순 쿠폰 오픈 직후 이 조회에 트래픽이 몰리는 것을 감안해 결과를 통째로 담아둔다.
 *
 * <p><b>TTL 은 다음 자정까지다.</b> 배치가 그 시각에 대상을 새로 확정하므로, 날짜가 바뀌면
 * 캐시도 함께 사라져야 한다. 키에 기준일이 들어가 있어 날짜가 넘어가면 어차피 다른 키를
 * 보지만, 지난 날짜 키가 Redis 에 계속 남지 않도록 TTL 로도 끊는다.
 *
 * <p><b>빈 결과는 캐시하지 않는다.</b> 자정 직후 배치가 아직 커밋하기 전에 들어온 요청이
 * 빈 목록을 그날 하루치로 굳혀버리는 것을 막기 위해서다. 빈 응답은 값이 싸므로 매번 DB 를
 * 다시 보는 편이 낫다.
 *
 * <p><b>Redis 장애를 호출부로 전파하지 않는다.</b> RefreshTokenRepository 처럼 실패가 곧
 * 기능 실패인 저장소와 달리, 여기서는 캐시 실패와 캐시 미스가 호출부에 같은 의미다 —
 * 둘 다 "DB 에서 다시 구해라" 이다. 캐시 때문에 조회가 죽으면 캐시를 두는 목적과 어긋난다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CampaignTargetLotCacheRepository {

    private static final String KEY_PREFIX = "campaignTargetLots:";

    /*
     * 캐시 전용 매퍼를 직접 든다. Spring Boot 4 는 ObjectMapper 를 빈으로 노출하지 않아
     * 주입받을 수 없기도 하고, 웹 계층의 직렬화 설정이 바뀌어도 캐시에 저장된 포맷이
     * 함께 흔들리지 않는 편이 낫기도 하다. 담는 값이 Long/String/int 뿐인 record 라
     * 별도 모듈 없이 기본 설정으로 충분하다.
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public Optional<CursorPageResponse<ExpiringSoonResponse>> find(
            LocalDate targetDate, Long categoryId, String pageToken, int pageSize) {
        String key = keyOf(targetDate, categoryId, pageToken, pageSize);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                return Optional.empty();
            }
            return Optional.of(OBJECT_MAPPER.readValue(cached, new TypeReference<>() { }));
        } catch (DataAccessException | com.fasterxml.jackson.core.JacksonException e) {
            // 캐시를 못 읽은 것은 미스와 같다. 호출부가 DB 에서 다시 구한다
            log.warn("event=CAMPAIGN_TARGET_CACHE_READ_FAILED key={}", key, e);
            return Optional.empty();
        }
    }

    public void put(LocalDate targetDate, Long categoryId, String pageToken, int pageSize,
            CursorPageResponse<ExpiringSoonResponse> response) {
        if (response.items().isEmpty()) {
            return;
        }
        String key = keyOf(targetDate, categoryId, pageToken, pageSize);
        try {
            redisTemplate.opsForValue().set(key, OBJECT_MAPPER.writeValueAsString(response), ttlUntilMidnight());
        } catch (DataAccessException | com.fasterxml.jackson.core.JacksonException e) {
            // 캐시에 못 넣어도 응답은 이미 만들어져 있다. 다음 요청이 다시 시도한다
            log.warn("event=CAMPAIGN_TARGET_CACHE_WRITE_FAILED key={}", key, e);
        }
    }

    /*
     * 페이지 단위로 키를 나눈다. 요청 파라미터가 그대로 결과를 정하므로 넷을 모두 키에 넣는다.
     * pageToken 은 불투명 문자열이라 그대로 쓰고, 첫 페이지(null)는 "first" 로 구분한다.
     */
    private String keyOf(LocalDate targetDate, Long categoryId, String pageToken, int pageSize) {
        return KEY_PREFIX + targetDate
                + ":" + (categoryId != null ? categoryId : "all")
                + ":" + (pageToken != null ? pageToken : "first")
                + ":" + pageSize;
    }

    // 다음 자정까지. 배치가 그 시각에 대상을 새로 확정한다
    private Duration ttlUntilMidnight() {
        LocalDateTime now = LocalDateTime.now(clock);
        return Duration.between(now, LocalDate.now(clock).plusDays(1).atStartOfDay());
    }
}
