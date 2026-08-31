package com.freshmarket.stock.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.freshmarket.stock.StockOrderItemsRequest;
import com.freshmarket.stock.StockReservationRequest;
import com.freshmarket.stock.internal.service.StockReservationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// StockApiImpl이 요청 null 여부를 검증하고 StockReservationService에 위임하는지만 검증한다(DPB-4-05,
// 규칙 판단 자체는 StockReservationServiceTest가 검증한다)
@ExtendWith(MockitoExtension.class)
class StockApiImplTest {

    @Mock
    private StockReservationService stockReservationService;

    @InjectMocks
    private StockApiImpl stockApiImpl;

    @Test
    void 요청이_null이면_예약을_거부한다() {
        // when, then
        assertThatThrownBy(() -> stockApiImpl.reserve(null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(stockReservationService, never()).reserve(any());
    }

    @Test
    void 유효한_요청이면_예약을_서비스에_위임한다() {
        // given
        StockReservationRequest request = new StockReservationRequest(9001L, List.of());

        // when
        stockApiImpl.reserve(request);

        // then
        verify(stockReservationService).reserve(request);
    }

    @Test
    void 요청이_null이면_확정을_거부한다() {
        // when, then
        assertThatThrownBy(() -> stockApiImpl.confirm(null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(stockReservationService, never()).confirm(any());
    }

    @Test
    void 유효한_요청이면_확정을_서비스에_위임한다() {
        // given
        StockOrderItemsRequest request = new StockOrderItemsRequest(9001L, List.of(501L));

        // when
        stockApiImpl.confirm(request);

        // then
        verify(stockReservationService).confirm(request);
    }

    @Test
    void 요청이_null이면_해제를_거부한다() {
        // when, then
        assertThatThrownBy(() -> stockApiImpl.release(null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(stockReservationService, never()).release(any());
    }

    @Test
    void 유효한_요청이면_해제를_서비스에_위임한다() {
        // given
        StockOrderItemsRequest request = new StockOrderItemsRequest(9001L, List.of(501L));

        // when
        stockApiImpl.release(request);

        // then
        verify(stockReservationService).release(request);
    }
}
