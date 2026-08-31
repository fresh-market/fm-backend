package com.freshmarket.stock.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.stock.internal.dto.ExpiringSoonResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/*
 * 로컬 캐시의 키 분리와 저장 규칙을 검증한다.
 * 캐시 자체는 Caffeine 이 보장하므로, 여기서는 이 클래스가 정한 규칙만 본다 —
 * 어떤 요청을 서로 다른 항목으로 볼 것인가, 그리고 무엇을 담지 않을 것인가.
 */
class CampaignTargetLotCacheRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 28);

    private CampaignTargetLotCacheRepository repository;

    @BeforeEach
    void setUp() {
        repository = new CampaignTargetLotCacheRepository();
    }

    private CursorPageResponse<ExpiringSoonResponse> response(String productName) {
        return CursorPageResponse.of(
                List.of(new ExpiringSoonResponse(12L, productName, 31L, "1kg", 12900)), null);
    }

    @Test
    void 담아두면_같은_조건으로_다시_꺼낼_수_있다() {
        repository.put(TODAY, null, null, 20, response("감귤"));

        Optional<CursorPageResponse<ExpiringSoonResponse>> found = repository.find(TODAY, null, null, 20);

        assertThat(found).isPresent();
        assertThat(found.get().items().get(0).productName()).isEqualTo("감귤");
    }

    @Test
    void 담아둔_적이_없으면_비어_있다() {
        assertThat(repository.find(TODAY, null, null, 20)).isEmpty();
    }

    /*
     * 기준일이 키 앞에 있어 자정이 지나면 키가 통째로 달라진다.
     * 지난 날짜의 확정본을 오늘 것으로 잘못 내보낼 경로가 없다는 뜻이다.
     */
    @Test
    void 기준일이_다르면_다른_항목이다() {
        repository.put(TODAY, null, null, 20, response("어제 감귤"));

        assertThat(repository.find(TODAY.plusDays(1), null, null, 20)).isEmpty();
    }

    @Test
    void 카테고리가_다르면_다른_항목이다() {
        repository.put(TODAY, 4L, null, 20, response("과일"));

        assertThat(repository.find(TODAY, 5L, null, 20)).isEmpty();
        assertThat(repository.find(TODAY, null, null, 20)).isEmpty();
    }

    @Test
    void 페이지_토큰이_다르면_다른_항목이다() {
        repository.put(TODAY, null, null, 20, response("첫 페이지"));

        assertThat(repository.find(TODAY, null, "cursor-token", 20)).isEmpty();
    }

    @Test
    void 페이지_크기가_다르면_다른_항목이다() {
        repository.put(TODAY, null, null, 20, response("20건"));

        assertThat(repository.find(TODAY, null, null, 10)).isEmpty();
    }

    /*
     * 자정 직후 배치가 아직 커밋하기 전에 들어온 요청이 빈 목록을 굳혀버리면,
     * 그날 대상이 확정된 뒤에도 계속 빈 응답을 주게 된다.
     */
    @Test
    void 빈_결과는_담지_않는다() {
        repository.put(TODAY, null, null, 20, CursorPageResponse.of(List.of(), null));

        assertThat(repository.find(TODAY, null, null, 20)).isEmpty();
    }
}
