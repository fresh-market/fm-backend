package com.freshmarket.stock.domain.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.stock.domain.dto.CampaignTargetLotCandidate;
import com.freshmarket.stock.domain.dto.LotDisposedQty;
import com.freshmarket.stock.domain.entity.CampaignTargetLot;
import com.freshmarket.stock.domain.repository.CampaignTargetLotRepository;
import com.freshmarket.stock.domain.repository.StockLotQueryRepository;
import com.freshmarket.stock.domain.repository.StockMovementRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * 캠페인 대상 선정 로직(재고 하한, 확보재고 제외, 소진율 하위 10% 전체)을 검증한다.
 * 실제 QueryDSL 조회는 StockLotQueryRepositoryIntegrationTest 가 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class CampaignTargetLotBatchTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

    @Mock
    private StockLotQueryRepository stockLotQueryRepository;

    @Mock
    private CampaignTargetLotRepository campaignTargetLotRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    private CampaignTargetLotBatch batch;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        batch = new CampaignTargetLotBatch(
                stockLotQueryRepository, campaignTargetLotRepository, stockMovementRepository, clock);
    }

    // 폐기 이력이 없는 기본 상황. 폐기 교정을 따로 보는 테스트만 이 스텁을 덮어쓴다
    private void 폐기_이력_없음() {
        when(stockMovementRepository.findDisposedQtyByStockLotIds(any())).thenReturn(List.of());
    }

    // 초기수량, 잔여수량으로 후보 하나를 만든다. 로트ID는 초기수량으로 대신 구분한다(테스트 편의)
    private CampaignTargetLotCandidate candidate(long lotId, int initialQty, int availableQty) {
        return new CampaignTargetLotCandidate(lotId, 100L, TODAY.plusDays(5), initialQty, availableQty);
    }

    /*
     * 소진율이 서로 다른 후보 count 건을 만든다. i 번 로트가 i 개 팔린 형태라
     * lotId 가 작을수록 소진율이 낮다(=대상 우선순위가 높다).
     * 건수가 많아 명시적 나열이 어려울 때만 쓰는 픽스처 팩터리다 (UT-3-04).
     */
    private List<CampaignTargetLotCandidate> candidates(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> candidate(i, 1000, 1000 - i))
                .toList();
    }

    // 재실행 시 당일 기존 대상을 지우고 다시 확정하는 것은 관찰 가능한 결과(저장된 행)로
    // CampaignTargetLotBatchIntegrationTest 가 검증한다. 여기서 deleteByTargetDate 호출
    // 여부만 verify 하는 것은 구현 세부 결합이라 UT-1-02/UT-2-02 에 따라 지운다.

    // 잔여재고 하한과 확보재고 조건은 SQL 의 where 로 내려가 이 클래스의 책임이 아니게 됐다.
    // 실제 필터링은 CampaignTargetLotBatchIntegrationTest 가 진짜 DB 로 검증한다.

    @Test
    void 소진율이_가장_낮은_로트가_1순위가_된다() {
        // given — 소진율: lot1=0.9(90개 팔림), lot2=0.1, lot3=0.5, lot4=0.05, lot5=0.6
        List<CampaignTargetLotCandidate> candidates = List.of(
                candidate(1L, 100, 10),   // 소진율 0.9
                candidate(2L, 100, 90),   // 소진율 0.1  ← 두 번째로 낮음
                candidate(3L, 100, 50),   // 소진율 0.5
                candidate(4L, 100, 95),   // 소진율 0.05 ← 가장 낮음
                candidate(5L, 100, 40));  // 소진율 0.6
        폐기_이력_없음();
        when(stockLotQueryRepository.findCandidatesExpiringBetween(any(), any(), anyInt())).thenReturn(candidates);

        batch.run();

        // then — 5건 중 하위 10%(ceil(5/10)=1건) 만 대상. 가장 낮은 소진율 하나만 저장된다
        ArgumentCaptor<CampaignTargetLot> captor = ArgumentCaptor.forClass(CampaignTargetLot.class);
        verify(campaignTargetLotRepository, times(1)).save(captor.capture());
        assertThatSaved(captor.getValue(), 4L, 1);
    }

    @Test
    void 대상_건수에_상한이_없어_후보가_늘면_대상도_함께_는다() {
        // given — 50건. 하위 10% = ceil(50/10) = 5건.
        //         예전처럼 3건 상한이 남아 있었다면 3건에서 잘렸을 규모다
        폐기_이력_없음();
        when(stockLotQueryRepository.findCandidatesExpiringBetween(any(), any(), anyInt())).thenReturn(candidates(50));

        batch.run();

        verify(campaignTargetLotRepository, times(5)).save(any());
    }

    @Test
    void 후보가_적으면_대상도_함께_줄어든다() {
        // given — 10건. 하위 10% = ceil(10/10) = 1건.
        //         비율 기준이라 후보 풀이 작으면 대상이 1건까지 줄어든다
        폐기_이력_없음();
        when(stockLotQueryRepository.findCandidatesExpiringBetween(any(), any(), anyInt())).thenReturn(candidates(10));

        batch.run();

        verify(campaignTargetLotRepository, times(1)).save(any());
    }

    @Test
    void 폐기된_수량은_팔린_것으로_세지_않는다() {
        /*
         * lot1: 입고 100, 폐기 30, 잔여 70 → 한 개도 안 팔렸다
         *   교정 전: (100-70)/100 = 0.30  ← 30% 팔린 것처럼 보인다
         *   교정 후: (70-70)/70   = 0.00  ← 실제로 0% 다
         * lot2: 입고 100, 폐기 없음, 잔여 90 → 10 개 팔림, 소진율 0.10
         * 교정이 없으면 lot2(0.10) 가 lot1(0.30) 보다 낮아 1순위가 되지만,
         * 교정하면 lot1(0.00) 이 1순위여야 한다.
         */
        when(stockLotQueryRepository.findCandidatesExpiringBetween(any(), any(), anyInt()))
                .thenReturn(List.of(candidate(1L, 100, 70), candidate(2L, 100, 90)));
        when(stockMovementRepository.findDisposedQtyByStockLotIds(any()))
                .thenReturn(List.of(new LotDisposedQty(1L, 30L)));

        batch.run();

        // then — 2건 중 하위 10%(ceil(2/10)=1건). 폐기를 걷어낸 lot1 이 1순위로 저장된다
        ArgumentCaptor<CampaignTargetLot> captor = ArgumentCaptor.forClass(CampaignTargetLot.class);
        verify(campaignTargetLotRepository, times(1)).save(captor.capture());
        assertThatSaved(captor.getValue(), 1L, 1);
    }

    private void assertThatSaved(CampaignTargetLot saved, Long expectedLotId, int expectedRank) {
        org.assertj.core.api.Assertions.assertThat(saved.getStockLotId()).isEqualTo(expectedLotId);
        org.assertj.core.api.Assertions.assertThat(saved.getTargetRank()).isEqualTo(expectedRank);
    }
}
