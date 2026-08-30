package com.freshmarket.stock.domain.repository;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.stock.domain.dto.ExpiringSoonResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Repository;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 회원용 떨이 쿠폰 대상 조회 결과를 캐시한다.
 *
 * <p>이 목록은 자정 배치가 확정한 뒤 하루 종일 바뀌지 않는데, 캐시가 없으면 요청마다
 * campaign_target_lot 조회 → stock_lot 조회 → ProductApi(product 도메인) 호출을 다시 탄다.
 * 선착순 쿠폰 오픈 직후 이 조회에 트래픽이 몰리는 것을 감안해 결과를 통째로 담아둔다.
 *
 * <p><b>Redis 가 아니라 로컬(JVM) 캐시를 쓴다.</b> 인스턴스 사이에 공유할 필요가 없는 값이고
 * (모든 인스턴스가 같은 확정본을 읽으므로 각자 채워도 내용이 같다), 네트워크 왕복 없이 읽는
 * 것이 목적이기 때문이다. 앱 인스턴스가 최대 2대라 최악의 경우에도 DB 조회가 2배에 그친다.
 *
 * <p><b>TTL 은 정확성이 아니라 메모리 회수를 위한 것이다.</b> 캐시 키에 기준일이 들어가 있어
 * 자정이 지나면 키 자체가 달라진다 — 지난 날짜의 값을 잘못 내보낼 경로가 없다. 그래서 만료는
 * 오래된 날짜 항목을 치우는 역할만 한다.
 *
 * <p><b>빈 결과는 캐시하지 않는다.</b> 자정 직후 배치가 아직 커밋하기 전에 들어온 요청이
 * 빈 목록을 굳혀버리는 것을 막기 위해서다. 빈 응답은 값이 싸므로 매번 DB 를 보는 편이 낫다.
 *
 * <p>없으면 없는 대로 DB 에서 구하면 되므로 조회는 Optional 을 준다. 호출부가 캐시 유무로
 * 분기할 일이 없다는 뜻이다.
 */
@Repository
public class CampaignTargetLotCacheRepository {

    /*
     * 기준일 × 카테고리 × 페이지 조합만큼 항목이 생긴다. 대상이 소진율 하위 10% 라 페이지 수가
     * 많지 않고, 카테고리도 다섯 종이라 상한을 넉넉히 잡아도 메모리 부담이 없다.
     */
    private static final int MAX_ENTRIES = 500;
    private static final Duration EXPIRE_AFTER_WRITE = Duration.ofHours(2);

    private final Cache<String, CursorPageResponse<ExpiringSoonResponse>> cache = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(EXPIRE_AFTER_WRITE)
            .build();

    public Optional<CursorPageResponse<ExpiringSoonResponse>> find(
            LocalDate targetDate, Long categoryId, String pageToken, int pageSize) {
        return Optional.ofNullable(cache.getIfPresent(keyOf(targetDate, categoryId, pageToken, pageSize)));
    }

    public void put(LocalDate targetDate, Long categoryId, String pageToken, int pageSize,
            CursorPageResponse<ExpiringSoonResponse> response) {
        if (response.items().isEmpty()) {
            return;
        }
        cache.put(keyOf(targetDate, categoryId, pageToken, pageSize), response);
    }

    /*
     * 담아둔 것을 전부 버린다.
     *
     * 배치를 같은 날 다시 돌리면 확정본이 바뀌는데, 캐시는 그것을 알 방법이 없어 만료될 때까지
     * 옛 목록을 준다. 그때 비우기 위한 것이다. 다만 로컬 캐시라 이 인스턴스만 비워지므로,
     * 여러 대가 떠 있으면 나머지는 만료를 기다려야 한다 — 그 시간을 EXPIRE_AFTER_WRITE 가 묶는다.
     */
    public void clear() {
        cache.invalidateAll();
    }

    /*
     * 페이지 단위로 키를 나눈다. 요청 파라미터가 그대로 결과를 정하므로 넷을 모두 키에 넣는다.
     * 기준일이 앞에 있어 자정이 지나면 키가 통째로 달라진다.
     * pageToken 은 불투명 문자열이라 그대로 쓰고, 첫 페이지(null)는 "first" 로 구분한다.
     */
    private String keyOf(LocalDate targetDate, Long categoryId, String pageToken, int pageSize) {
        return targetDate
                + ":" + (categoryId != null ? categoryId : "all")
                + ":" + (pageToken != null ? pageToken : "first")
                + ":" + pageSize;
    }
}
