package com.freshmarket.stock.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.freshmarket.stock.domain.entity.StockLot;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * mergeSortedByExpiry()만 검증한다. DB를 타는 나머지 메서드(findByProductOptionId 등)는
 * QueryDSL 동적 쿼리라 이 프로젝트 방침(리포지토리 통합 테스트 스킵)대로 여기서 다루지 않는다 —
 * 병합 로직은 DB 없이 순수 로직으로 검증 가능해서 그렇게 분리해 둔 것이다(클래스 메서드 주석 참고).
 */
class StockLotQueryRepositoryTest {

    private final StockLotQueryRepository repository = new StockLotQueryRepository(mock(JPAQueryFactory.class));

    @Test
    void 여러_옵션의_정렬된_결과를_소비기한_순으로_병합한다() {
        // given — 옵션1: 8/20, 8/25 / 옵션2: 8/18, 8/22
        List<StockLot> option1 = List.of(lotFixture(1L, "2026-08-20"), lotFixture(2L, "2026-08-25"));
        List<StockLot> option2 = List.of(lotFixture(3L, "2026-08-18"), lotFixture(4L, "2026-08-22"));

        // when
        List<StockLot> merged = repository.mergeSortedByExpiry(List.of(option1, option2), 4);

        // then — 8/18, 8/20, 8/22, 8/25 순
        assertThat(merged).extracting(StockLot::getId).containsExactly(3L, 1L, 4L, 2L);
    }

    @Test
    void 소비기한이_같으면_id로_동점_처리한다() {
        // given — 두 옵션 모두 같은 날짜, id가 작은 쪽이 먼저 나와야 한다
        List<StockLot> option1 = List.of(lotFixture(5L, "2026-08-20"));
        List<StockLot> option2 = List.of(lotFixture(2L, "2026-08-20"));

        // when
        List<StockLot> merged = repository.mergeSortedByExpiry(List.of(option1, option2), 2);

        // then
        assertThat(merged).extracting(StockLot::getId).containsExactly(2L, 5L);
    }

    @Test
    void limit을_넘는_뒷부분은_잘라낸다() {
        // given
        List<StockLot> option1 = List.of(lotFixture(1L, "2026-08-18"), lotFixture(2L, "2026-08-25"));
        List<StockLot> option2 = List.of(lotFixture(3L, "2026-08-20"), lotFixture(4L, "2026-08-30"));

        // when — 상위 2건만
        List<StockLot> merged = repository.mergeSortedByExpiry(List.of(option1, option2), 2);

        // then
        assertThat(merged).extracting(StockLot::getId).containsExactly(1L, 3L);
    }

    @Test
    void 로트가_없는_옵션이_섞여도_정상_병합한다() {
        // given — 옵션 하나는 이번 페이지에 로트가 없는 경우(빈 리스트)
        List<StockLot> option1 = List.of(lotFixture(1L, "2026-08-20"));
        List<StockLot> empty = List.of();

        // when
        List<StockLot> merged = repository.mergeSortedByExpiry(List.of(option1, empty), 5);

        // then
        assertThat(merged).extracting(StockLot::getId).containsExactly(1L);
    }

    @Test
    void 전부_비어있으면_빈_리스트를_반환한다() {
        List<StockLot> merged = repository.mergeSortedByExpiry(List.of(List.of(), List.of()), 5);

        assertThat(merged).isEmpty();
    }

    private StockLot lotFixture(Long id, String expiryDate) {
        StockLot lot = StockLot.register("req-" + id, 1L, LocalDate.parse("2026-08-01"),
                LocalDate.parse(expiryDate), 10);
        ReflectionTestUtils.setField(lot, "id", id);
        return lot;
    }
}
