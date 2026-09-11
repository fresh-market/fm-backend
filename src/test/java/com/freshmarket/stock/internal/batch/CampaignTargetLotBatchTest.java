package com.freshmarket.stock.internal.batch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.stock.internal.exception.StockErrorCode;
import com.freshmarket.stock.internal.exception.StockException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * 자정 스케줄이 확정 결과를 어떻게 받아넘기는지 본다. 선정 로직 자체는
 * CampaignTargetLotRebuildServiceTest 가 본다 — 여기는 스케줄 쪽 판단만 다룬다.
 *
 * 이 판단이 감시 지표로 이어진다. SchedulerLoggingAspect 는 예외가 올라오면 SCHEDULER_FAILED 를
 * 남기고 batch.job.last.success.timestamp 를 갱신하지 않는데, 그 값이 낡는 것으로 알람이 울린다.
 * 무엇을 올려 보내고 무엇을 삼키는지가 곧 "언제 알람이 울리는가" 라서 여기서 잠가 둔다.
 */
@ExtendWith(MockitoExtension.class)
class CampaignTargetLotBatchTest {

    @Mock
    private CampaignTargetLotRebuildService campaignTargetLotRebuildService;

    @InjectMocks
    private CampaignTargetLotBatch batch;

    @Test
    void 확정을_서비스에_맡긴다() {
        when(campaignTargetLotRebuildService.rebuild()).thenReturn(3);

        batch.run();

        verify(campaignTargetLotRebuildService).rebuild();
    }

    /*
     * 다른 쪽이 확정 중이라 양보한 것은 실패가 아니다.
     *
     * 올려 보내면 멀쩡한 날에 SCHEDULER_FAILED 가 남고 마지막 성공 시각이 낡아 알람이 울린다.
     * 이 스케줄의 목적은 "오늘 대상이 확정되어 있게 하는 것" 이고 남이 하고 있으면 달성된다.
     */
    @Test
    void 다른_쪽이_확정_중이면_양보하고_정상_종료한다() {
        doThrow(new StockException(StockErrorCode.CAMPAIGN_REBUILD_IN_PROGRESS))
                .when(campaignTargetLotRebuildService).rebuild();

        assertThatCode(() -> batch.run()).doesNotThrowAnyException();
    }

    /*
     * 삼키는 것은 그 한 가지뿐이다. 다른 실패까지 삼키면 배치가 조용히 안 도는 상태가 되는데,
     * 배치는 틀리게 도는 것보다 조용히 안 도는 것이 위험하다(SchedulerLoggingAspect 주석).
     */
    @Test
    void 다른_stock_실패는_그대로_올려_보낸다() {
        doThrow(new StockException(StockErrorCode.LOT_NOT_FOUND))
                .when(campaignTargetLotRebuildService).rebuild();

        assertThatThrownBy(() -> batch.run())
                .isInstanceOf(StockException.class)
                .hasMessageContaining(StockErrorCode.LOT_NOT_FOUND.getMessage());
    }

    // stock 예외가 아닌 것도 마찬가지다. 잠금 경합만 골라 삼키는 것이지 예외를 덮는 것이 아니다
    @Test
    void 그_밖의_예외도_그대로_올려_보낸다() {
        doThrow(new IllegalStateException("DB 가 죽었다"))
                .when(campaignTargetLotRebuildService).rebuild();

        assertThatThrownBy(() -> batch.run()).isInstanceOf(IllegalStateException.class);
    }
}
