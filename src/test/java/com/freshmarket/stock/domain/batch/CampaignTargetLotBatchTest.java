package com.freshmarket.stock.domain.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.stock.domain.dto.CampaignTargetLotCandidate;
import com.freshmarket.stock.domain.entity.CampaignTargetLot;
import com.freshmarket.stock.domain.repository.CampaignTargetLotRepository;
import com.freshmarket.stock.domain.repository.StockLotQueryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * 캠페인 대상 선정 로직(재고 하한, 확보재고 제외, 소진율 하위 20%, 상위 3건)을 검증한다.
 * 실제 QueryDSL 조회는 StockLotQueryRepositoryIntegrationTest 가 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class CampaignTargetLotBatchTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

    @Mock
    private StockLotQueryRepository stockLotQueryRepository;

    @Mock
    private CampaignTargetLotRepository campaignTargetLotRepository;

    private CampaignTargetLotBatch batch;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        batch = new CampaignTargetLotBatch(stockLotQueryRepository, campaignTargetLotRepository, clock);
    }

    // 초기수량, 잔여수량으로 후보 하나를 만든다. 로트ID는 초기수량으로 대신 구분한다(테스트 편의)
    private CampaignTargetLotCandidate candidate(long lotId, int initialQty, int availableQty) {
        return new CampaignTargetLotCandidate(lotId, 100L, TODAY.plusDays(5), initialQty, availableQty);
    }

    // 재실행 시 당일 기존 대상을 지우고 다시 확정하는 것은 관찰 가능한 결과(저장된 행)로
    // CampaignTargetLotBatchIntegrationTest 가 검증한다. 여기서 deleteByTargetDate 호출
    // 여부만 verify 하는 것은 구현 세부 결합이라 UT-1-02/UT-2-02 에 따라 지운다.

    @Test
    void 잔여재고가_30_미만이면_대상에서_제외된다() {
        // given — 소진율은 낮지만(0.9) 잔여재고가 29라 제외되어야 한다
        when(stockLotQueryRepository.findCandidatesExpiringBy(any()))
                .thenReturn(List.of(candidate(1L, 100, 29)));

        batch.run();

        verify(campaignTargetLotRepository, times(0)).save(any());
    }

    @Test
    void 확보재고가_0이면_대상에서_제외된다() {
        when(stockLotQueryRepository.findCandidatesExpiringBy(any()))
                .thenReturn(List.of(candidate(1L, 0, 0)));

        batch.run();

        verify(campaignTargetLotRepository, times(0)).save(any());
    }

    @Test
    void 소진율_오름차순으로_순위를_매긴다() {
        // given — 소진율: lot1=0.9(90개 팔림), lot2=0.1(10개 팔림), lot3=0.5(50개 팔림)
        //         전부 잔여재고 30 이상, 하위 20%(3건 중 1건)만 봐도 결국 셋 다 소규모라
        //         전부 살아남도록 후보 수를 5개로 늘려 "하위 20%" 필터가 의미 있게 만든다
        List<CampaignTargetLotCandidate> candidates = List.of(
                candidate(1L, 100, 10),   // 소진율 0.9
                candidate(2L, 100, 90),   // 소진율 0.1  ← 가장 낮음
                candidate(3L, 100, 50),   // 소진율 0.5
                candidate(4L, 100, 95),   // 소진율 0.05 ← 두 번째로 낮음
                candidate(5L, 100, 40));  // 소진율 0.6
        when(stockLotQueryRepository.findCandidatesExpiringBy(any())).thenReturn(candidates);

        batch.run();

        // then — 5건 중 하위 20%(ceil(5*0.2)=1건) 만 대상. 즉 가장 낮은 소진율 하나만 저장된다
        ArgumentCaptor<CampaignTargetLot> captor = ArgumentCaptor.forClass(CampaignTargetLot.class);
        verify(campaignTargetLotRepository, times(1)).save(captor.capture());
        assertThatSaved(captor.getValue(), 4L, 1);
    }

    @Test
    void 하위_20퍼센트_안에서도_최대_3건까지만_대상이_된다() {
        // given — 10건, 하위 20% = ceil(10*0.2) = 2건. TARGET_COUNT(3)보다 적으니 2건만 저장
        List<CampaignTargetLotCandidate> candidates = List.of(
                candidate(1L, 100, 95),   // 0.05 ← 가장 낮음
                candidate(2L, 100, 90),   // 0.1  ← 두 번째
                candidate(3L, 100, 80),
                candidate(4L, 100, 70),
                candidate(5L, 100, 60),
                candidate(6L, 100, 50),
                candidate(7L, 100, 40),
                candidate(8L, 100, 35),
                candidate(9L, 100, 32),
                candidate(10L, 100, 30));
        when(stockLotQueryRepository.findCandidatesExpiringBy(any())).thenReturn(candidates);

        batch.run();

        verify(campaignTargetLotRepository, times(2)).save(any());
    }

    private void assertThatSaved(CampaignTargetLot saved, Long expectedLotId, int expectedRank) {
        org.assertj.core.api.Assertions.assertThat(saved.getStockLotId()).isEqualTo(expectedLotId);
        org.assertj.core.api.Assertions.assertThat(saved.getTargetRank()).isEqualTo(expectedRank);
    }
}
