package com.freshmarket.stock.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.product.OptionAvailabilityChangedEvent;
import com.freshmarket.stock.domain.entity.LotStatus;
import com.freshmarket.stock.domain.entity.StockLot;
import com.freshmarket.stock.domain.exception.StockErrorCode;
import com.freshmarket.stock.domain.exception.StockException;
import com.freshmarket.stock.domain.repository.StockLotRepository;
import com.freshmarket.stock.domain.repository.StockMovementRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

// AdminLotExpireChunkService의 청크 단위 만료 처리를 검증한다
@ExtendWith(MockitoExtension.class)
class AdminLotExpireChunkServiceTest {

    private static final int CHUNK_SIZE = 1000;

    @Mock
    private StockLotRepository stockLotRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AdminLotExpireChunkService adminLotExpireChunkService;

    @Test
    void 만료_대상이_없으면_빈_목록을_돌려준다() {
        // given
        when(stockLotRepository.findByStatusAndExpiryDateBefore(
                eq(LotStatus.AVAILABLE), eq(LocalDate.now()), any(Pageable.class)))
                .thenReturn(List.of());

        // when
        List<StockLot> result = adminLotExpireChunkService.expireChunk(CHUNK_SIZE);

        // then
        assertThat(result).isEmpty();
        verify(stockMovementRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 소비기한이_지난_로트를_만료_처리하고_이력을_남긴다() {
        // given
        StockLot lot = lotFixture(77L, 31L, LocalDate.now().minusDays(1), 40);
        stubTargets(List.of(lot));
        when(stockLotRepository.findProductOptionIdsByProductOptionIdInAndStatus(List.of(31L), LotStatus.AVAILABLE))
                .thenReturn(Set.of());

        // when
        List<StockLot> result = adminLotExpireChunkService.expireChunk(CHUNK_SIZE);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(LotStatus.EXPIRED);
        assertThat(lot.getAvailableQty()).isEqualTo(0);
        verify(stockMovementRepository).save(any());
    }

    @Test
    void 만료_처리로_옵션에_가용_로트가_없으면_품절_이벤트를_발행한다() {
        // given
        StockLot lot = lotFixture(77L, 31L, LocalDate.now().minusDays(1), 40);
        stubTargets(List.of(lot));
        when(stockLotRepository.findProductOptionIdsByProductOptionIdInAndStatus(List.of(31L), LotStatus.AVAILABLE))
                .thenReturn(Set.of());

        // when
        adminLotExpireChunkService.expireChunk(CHUNK_SIZE);

        // then
        ArgumentCaptor<OptionAvailabilityChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(OptionAvailabilityChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().productOptionId()).isEqualTo(31L);
        assertThat(eventCaptor.getValue().soldOut()).isTrue();
        assertThat(eventCaptor.getValue().occurredAt()).isNotNull();
    }

    @Test
    void 만료_처리_후에도_옵션에_다른_가용_로트가_남아있으면_이벤트를_발행하지_않는다() {
        // given
        StockLot lot = lotFixture(77L, 31L, LocalDate.now().minusDays(1), 40);
        stubTargets(List.of(lot));
        when(stockLotRepository.findProductOptionIdsByProductOptionIdInAndStatus(List.of(31L), LotStatus.AVAILABLE))
                .thenReturn(Set.of(31L));

        // when
        adminLotExpireChunkService.expireChunk(CHUNK_SIZE);

        // then
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 같은_옵션의_로트_여러_개가_만료되면_품절_이벤트는_한_번만_발행한다() {
        // given — 옵션 31에 속한 로트 두 개가 같은 청크에서 함께 만료되는 상황
        StockLot lotA = lotFixture(77L, 31L, LocalDate.now().minusDays(2), 10);
        StockLot lotB = lotFixture(78L, 31L, LocalDate.now().minusDays(1), 20);
        stubTargets(List.of(lotA, lotB));
        when(stockLotRepository.findProductOptionIdsByProductOptionIdInAndStatus(List.of(31L), LotStatus.AVAILABLE))
                .thenReturn(Set.of());

        // when
        adminLotExpireChunkService.expireChunk(CHUNK_SIZE);

        // then
        verify(stockLotRepository, times(1))
                .findProductOptionIdsByProductOptionIdInAndStatus(List.of(31L), LotStatus.AVAILABLE);
        ArgumentCaptor<OptionAvailabilityChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(OptionAvailabilityChangedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().productOptionId()).isEqualTo(31L);
        assertThat(eventCaptor.getValue().soldOut()).isTrue();
    }

    @Test
    void 이미_처리된_로트는_건너뛴다() {
        // given — 조회 조건이 AVAILABLE이지만, 조회와 처리 사이 상태가 바뀐 경우를 방어적으로 대비한다
        StockLot lot = nonAvailableLotFixture(77L, 31L, LocalDate.now().minusDays(1), LotStatus.DISPOSED);
        stubTargets(List.of(lot));

        // when
        List<StockLot> result = adminLotExpireChunkService.expireChunk(CHUNK_SIZE);

        // then
        assertThat(result).isEmpty();
        verify(stockMovementRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 가용_수량이_0인_로트는_이력_없이_상태만_전환한다() {
        // given — 예약으로 이미 다 소진됐지만 SOLD_OUT 전환이 없어 status는 여전히 AVAILABLE인 로트.
        // StockMovement가 quantity>0을 강제하므로, 여기서 이력을 남기려 하면 청크 전체가 예외로 롤백된다
        StockLot lot = lotFixture(77L, 31L, LocalDate.now().minusDays(1), 40);
        ReflectionTestUtils.setField(lot, "availableQty", 0);
        stubTargets(List.of(lot));
        when(stockLotRepository.findProductOptionIdsByProductOptionIdInAndStatus(List.of(31L), LotStatus.AVAILABLE))
                .thenReturn(Set.of());

        // when
        List<StockLot> result = adminLotExpireChunkService.expireChunk(CHUNK_SIZE);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(LotStatus.EXPIRED);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void 만료_대상_조회_중_락_경합이_나면_처리중_오류로_감싼다() {
        // given — findByStatusAndExpiryDateBefore 자체가 쓰기 락 조회라 락 대기 타임아웃/교착이 날 수 있다
        when(stockLotRepository.findByStatusAndExpiryDateBefore(
                eq(LotStatus.AVAILABLE), eq(LocalDate.now()), any(Pageable.class)))
                .thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));

        // when, then
        assertThatThrownBy(() -> adminLotExpireChunkService.expireChunk(CHUNK_SIZE))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.EXPIRE_IN_PROGRESS);
    }

    private void stubTargets(List<StockLot> targets) {
        when(stockLotRepository.findByStatusAndExpiryDateBefore(
                eq(LotStatus.AVAILABLE), eq(LocalDate.now()), any(Pageable.class)))
                .thenReturn(targets);
    }

    private StockLot lotFixture(Long id, Long productOptionId, LocalDate expiryDate, int availableQty) {
        StockLot lot = StockLot.register("req-" + id, productOptionId, expiryDate.minusDays(14), expiryDate,
                availableQty);
        ReflectionTestUtils.setField(lot, "id", id);
        return lot;
    }

    private StockLot nonAvailableLotFixture(Long id, Long productOptionId, LocalDate expiryDate, LotStatus status) {
        StockLot lot = lotFixture(id, productOptionId, expiryDate, 50);
        ReflectionTestUtils.setField(lot, "status", status);
        return lot;
    }
}
