package com.freshmarket.stock.internal.repository;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.stock.internal.dto.ExpiringSoonResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.Supplier;
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
 * <p><b>TTL 은 정확성이 아니라 메모리 회수를 위한 것이다.</b> 캐시 키에 기준일과 확정본
 * 버전이 들어가 있어, 자정이 지나거나 배치를 다시 돌리면 키 자체가 달라진다 — 지난 값을
 * 잘못 내보낼 경로가 없다. 그래서 만료는 아무도 안 찾게 된 옛 항목을 치우는 역할만 한다.
 *
 * <p><b>무효화 대신 키 분리를 쓰는 이유가 있다.</b> 로컬 캐시라 한 인스턴스에서 비워도
 * 나머지는 그대로다. 관리자 재실행은 API 인스턴스 중 한 대로만 들어오므로 비우는 방식으로는
 * 나머지를 손댈 수 없다. 키에 버전을 넣으면 모든 인스턴스가 각자 새 키를 만들어 채운다.
 *
 * <p><b>빈 결과는 캐시하지 않는다.</b> 자정 직후 배치가 아직 커밋하기 전에 들어온 요청이
 * 빈 목록을 굳혀버리는 것을 막기 위해서다. 빈 응답은 값이 싸므로 매번 DB 를 보는 편이 낫다.
 *
 * <p><b>조회와 적재를 한 메서드로 묶었다.</b> 나눠 두면 같은 키가 동시에 미스했을 때 요청 수만큼
 * 원본 조회가 도는데, 그게 하필 캐시가 가장 필요한 순간(쿠폰 오픈 직후)에 일어난다.
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

    /*
     * 캐시에 있으면 그것을, 없으면 loader 로 구해 담고 돌려준다.
     *
     * 조회와 적재를 따로 부르지 않고 하나로 묶은 이유가 있다. 나눠 부르면 같은 키가 동시에
     * 미스했을 때 들어온 요청 수만큼 loader 가 돈다 — 캐시가 막으려던 바로 그 순간
     * (쿠폰 오픈 직후 캐시가 비어 있을 때) 원본 조회가 폭주한다.
     *
     * Caffeine 의 get(key, loader) 는 같은 키에 대해 loader 를 한 번만 실행하고 나머지는
     * 그 결과를 기다린다. 요청이 몇 개든 DB 로는 하나만 내려간다.
     *
     * 빈 결과는 담아두지 않는다. 자정 직후 배치가 아직 커밋하기 전에 들어온 요청이 빈 목록을
     * 굳혀버리면 그날 대상이 확정된 뒤에도 계속 빈 응답을 주기 때문이다. 담은 뒤 곧바로
     * 지우는 방식을 쓴다 — loader 가 null 을 주면 기록하지 않는 성질을 쓸 수도 있지만,
     * 그러면 반환값이 null 인지 확인하는 분기가 생겨 흐름이 한 겹 늘어난다.
     *
     * 지우기 전 아주 짧은 순간에 다른 요청이 그 빈 결과를 받을 수 있다. 어차피 그 시점에
     * 직접 조회해도 같은 빈 결과라 문제가 되지 않는다.
     */
    public CursorPageResponse<ExpiringSoonResponse> getOrLoad(
            LocalDate targetDate, Long version, Long categoryId, String pageToken, int pageSize,
            Supplier<CursorPageResponse<ExpiringSoonResponse>> loader) {

        String key = keyOf(targetDate, version, categoryId, pageToken, pageSize);
        CursorPageResponse<ExpiringSoonResponse> response = cache.get(key, ignored -> loader.get());

        if (response.items().isEmpty()) {
            cache.invalidate(key);
        }
        return response;
    }

    /*
     * 담아둔 것을 전부 버린다. 테스트가 회차 사이를 격리하는 데 쓴다.
     *
     * 운영에서 이것을 부를 일은 없다. 확정본이 바뀌는 경우는 키에 버전이 들어가 있어
     * 저절로 갈리기 때문이다.
     */
    public void clear() {
        cache.invalidateAll();
    }

    /*
     * 페이지 단위로 키를 나눈다. 요청 파라미터가 그대로 결과를 정하므로 넷을 모두 키에 넣는다.
     * 기준일이 앞에 있어 자정이 지나면 키가 통째로 달라진다.
     * pageToken 은 불투명 문자열이라 그대로 쓰고, 첫 페이지(null)는 "first" 로 구분한다.
     */
    private String keyOf(LocalDate targetDate, Long version, Long categoryId, String pageToken, int pageSize) {
        return targetDate
                + ":v" + version
                + ":" + (categoryId != null ? categoryId : "all")
                + ":" + (pageToken != null ? pageToken : "first")
                + ":" + pageSize;
    }
}
