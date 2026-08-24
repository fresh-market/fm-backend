package com.freshmarket.stock.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.product.OptionAvailabilityChangedEvent;
import com.freshmarket.product.ProductApi;
import com.freshmarket.stock.domain.dto.AdminLotCreateRequest;
import com.freshmarket.stock.domain.dto.AdminLotExpireResponse;
import com.freshmarket.stock.domain.dto.AdminLotListResponse;
import com.freshmarket.stock.domain.dto.AdminLotResponse;
import com.freshmarket.stock.domain.entity.LotStatus;
import com.freshmarket.stock.domain.entity.StockLot;
import com.freshmarket.stock.domain.exception.StockErrorCode;
import com.freshmarket.stock.domain.exception.StockException;
import com.freshmarket.stock.domain.repository.StockLotRepository;
import com.freshmarket.stock.domain.repository.StockMovementRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

// AdminLotService의 등록 성공과 실패 케이스를 검증한다
@ExtendWith(MockitoExtension.class)
class AdminLotServiceTest {

    @Mock
    private StockLotRepository stockLotRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @Mock
    private ProductApi productApi;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AdminLotService adminLotService;

    @Test
    void 입고일을_지정하면_그대로_등록한다() {
        // given
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        stubSaveAssignsId();
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when
        AdminLotResponse result = adminLotService.register(12L, 31L, request);

        // then
        assertThat(result.stockLotId()).isEqualTo(77L);
        assertThat(result.productOptionId()).isEqualTo(31L);
        assertThat(result.receivedDate()).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(result.expiryDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(result.initialQty()).isEqualTo(200);
        assertThat(result.availableQty()).isEqualTo(200);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        verify(stockMovementRepository).save(any());
        verify(eventPublisher).publishEvent(new OptionAvailabilityChangedEvent(31L, false));
    }

    @Test
    void 입고일을_생략하면_오늘_날짜로_등록한다() {
        // given
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        stubSaveAssignsId();
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", null, LocalDate.now().plusDays(10), 100);

        // when
        AdminLotResponse result = adminLotService.register(12L, 31L, request);

        // then
        assertThat(result.receivedDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void 같은_요청_식별자로_재시도하면_기존_로트를_그대로_반환한다() {
        // given — 이전 요청으로 이미 등록된 로트가 있는 상황(사전 조회에서 바로 잡힘)
        StockLot existing = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 31), 200);
        ReflectionTestUtils.setField(existing, "id", 77L);
        when(stockLotRepository.findByRequestIdAndProductOptionId("req-1", 31L)).thenReturn(Optional.of(existing));
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when
        AdminLotResponse result = adminLotService.register(12L, 31L, request);

        // then
        assertThat(result.stockLotId()).isEqualTo(77L);
        verify(stockLotRepository, never()).save(any());
        verify(productApi, never()).existsOption(any(), any());
    }

    @Test
    void 다른_옵션에_재사용된_요청_식별자는_재시도로_보지_않고_새로_등록한다() {
        // given — requestId "req-1"은 옵션 31에 이미 쓰였지만, 이번 요청은 옵션 45다.
        // (productId, optionId) 조합까지 봐야 엉뚱한 옵션의 로트를 재시도 응답으로 잘못 돌려주지 않는다.
        when(stockLotRepository.findByRequestIdAndProductOptionId("req-1", 45L)).thenReturn(Optional.empty());
        when(productApi.existsOption(12L, 45L)).thenReturn(true);
        stubSaveAssignsId();
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when
        AdminLotResponse result = adminLotService.register(12L, 45L, request);

        // then
        assertThat(result.productOptionId()).isEqualTo(45L);
        verify(stockLotRepository).save(any());
    }

    @Test
    void 저장_중_요청_식별자가_동시에_중복되면_기존_로트를_반환한다() {
        // given — 사전 조회 시점엔 없었지만, save() 직전에 동시 재시도가 먼저 커밋을 마친 경합 상황
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        StockLot existing = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 31), 200);
        ReflectionTestUtils.setField(existing, "id", 77L);
        when(stockLotRepository.findByRequestIdAndProductOptionId("req-1", 31L))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(stockLotRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry 'req-1' for key 'stock_lot.uk_lot_request_id'"));
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when
        AdminLotResponse result = adminLotService.register(12L, 31L, request);

        // then
        assertThat(result.stockLotId()).isEqualTo(77L);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void 다른_옵션에_이미_사용된_요청_식별자면_충돌_오류를_던진다() {
        // given — save() 시점에 uk_lot_request_id 위반이 났는데, 같은 (requestId, optionId) 조합으로
        // 재조회해도 없다면 그 requestId는 다른 옵션 소속이라는 뜻이다(클라이언트의 잘못된 재사용)
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        when(stockLotRepository.findByRequestIdAndProductOptionId("req-1", 31L)).thenReturn(Optional.empty());
        when(stockLotRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry 'req-1' for key 'stock_lot.uk_lot_request_id'"));
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when, then
        assertThatThrownBy(() -> adminLotService.register(12L, 31L, request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.REQUEST_ID_ALREADY_USED);
    }

    @Test
    void 락_대기_타임아웃_후_재조회에서_찾으면_기존_로트를_반환한다() {
        // given — save()가 유니크 위반이 아니라 락 대기 타임아웃으로 실패했지만, 그 사이 첫 요청이 커밋을 마친 상황
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        StockLot existing = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 31), 200);
        ReflectionTestUtils.setField(existing, "id", 77L);
        when(stockLotRepository.findByRequestIdAndProductOptionId("req-1", 31L))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(stockLotRepository.save(any())).thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when
        AdminLotResponse result = adminLotService.register(12L, 31L, request);

        // then
        assertThat(result.stockLotId()).isEqualTo(77L);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void 락_대기_타임아웃_후_재조회에서도_못_찾으면_처리중_오류를_던진다() {
        // given — 첫 요청이 타임아웃 안에도 여전히 처리 중인 상황(비정상적으로 느린 경우)
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        when(stockLotRepository.findByRequestIdAndProductOptionId("req-1", 31L)).thenReturn(Optional.empty());
        when(stockLotRepository.save(any())).thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when, then
        assertThatThrownBy(() -> adminLotService.register(12L, 31L, request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.REGISTRATION_IN_PROGRESS);
    }

    @Test
    void 존재하지_않는_옵션으로_등록하면_실패한다() {
        // given
        when(productApi.existsOption(12L, 999L)).thenReturn(false);
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", null, LocalDate.now().plusDays(10), 100);

        // when, then
        assertThatThrownBy(() -> adminLotService.register(12L, 999L, request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.OPTION_NOT_FOUND);
        verify(stockLotRepository, never()).save(any());
    }

    @Test
    void 등록_중_옵션이_동시에_삭제되면_존재하지_않는_옵션으로_응답한다() {
        // given — 사전 확인(existsOption) 통과 직후, save() 시점엔 옵션이 이미 삭제된 경합 상황
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        when(stockLotRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`freshmarket`.`stock_lot`, CONSTRAINT `fk_lot_option` "
                        + "FOREIGN KEY (`product_option_id`) REFERENCES `product_option` (`product_option_id`))"));
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when, then
        assertThatThrownBy(() -> adminLotService.register(12L, 31L, request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.OPTION_NOT_FOUND);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void 알_수_없는_제약_위반은_감싸서_던진다() {
        // given — fk_lot_option이 아닌 다른 위반(예: chk_lot_qty처럼 별도로 변환하지 않는 제약)
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        DataIntegrityViolationException unknownViolation = new DataIntegrityViolationException(
                "Check constraint 'chk_lot_qty' is violated");
        when(stockLotRepository.save(any())).thenThrow(unknownViolation);
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when, then
        assertThatThrownBy(() -> adminLotService.register(12L, 31L, request))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(unknownViolation);
    }

    @Test
    void 소비기한이_입고일보다_이르면_실패한다() {
        // given
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 10), 100);

        // when, then
        assertThatThrownBy(() -> adminLotService.register(12L, 31L, request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.EXPIRY_BEFORE_RECEIVED);
        verify(stockLotRepository, never()).save(any());
    }

    @Test
    void 만료_대상이_없으면_빈_응답을_돌려준다() {
        // given
        when(stockLotRepository.findByStatusAndExpiryDateBefore(LotStatus.AVAILABLE, LocalDate.now()))
                .thenReturn(List.of());

        // when
        AdminLotExpireResponse result = adminLotService.expireLots();

        // then
        assertThat(result.lots()).isEmpty();
        verify(stockMovementRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 소비기한이_지난_로트를_만료_처리하고_이력을_남긴다() {
        // given
        StockLot lot = lotFixture(77L, 31L, LocalDate.now().minusDays(1), 40);
        when(stockLotRepository.findByStatusAndExpiryDateBefore(LotStatus.AVAILABLE, LocalDate.now()))
                .thenReturn(List.of(lot));
        when(stockLotRepository.existsByProductOptionIdAndStatus(31L, LotStatus.AVAILABLE)).thenReturn(false);

        // when
        AdminLotExpireResponse result = adminLotService.expireLots();

        // then
        assertThat(result.lots()).hasSize(1);
        assertThat(result.lots().get(0).status()).isEqualTo("EXPIRED");
        assertThat(lot.getAvailableQty()).isEqualTo(0);
        verify(stockMovementRepository).save(any());
    }

    @Test
    void 만료_처리로_옵션에_가용_로트가_없으면_품절_이벤트를_발행한다() {
        // given
        StockLot lot = lotFixture(77L, 31L, LocalDate.now().minusDays(1), 40);
        when(stockLotRepository.findByStatusAndExpiryDateBefore(LotStatus.AVAILABLE, LocalDate.now()))
                .thenReturn(List.of(lot));
        when(stockLotRepository.existsByProductOptionIdAndStatus(31L, LotStatus.AVAILABLE)).thenReturn(false);

        // when
        adminLotService.expireLots();

        // then
        verify(eventPublisher).publishEvent(new OptionAvailabilityChangedEvent(31L, true));
    }

    @Test
    void 만료_처리_후에도_옵션에_다른_가용_로트가_남아있으면_이벤트를_발행하지_않는다() {
        // given
        StockLot lot = lotFixture(77L, 31L, LocalDate.now().minusDays(1), 40);
        when(stockLotRepository.findByStatusAndExpiryDateBefore(LotStatus.AVAILABLE, LocalDate.now()))
                .thenReturn(List.of(lot));
        when(stockLotRepository.existsByProductOptionIdAndStatus(31L, LotStatus.AVAILABLE)).thenReturn(true);

        // when
        adminLotService.expireLots();

        // then
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 같은_옵션의_로트_여러_개가_만료되면_품절_이벤트는_한_번만_발행한다() {
        // given — 옵션 31에 속한 로트 두 개가 같은 배치에서 함께 만료되는 상황
        StockLot lotA = lotFixture(77L, 31L, LocalDate.now().minusDays(2), 10);
        StockLot lotB = lotFixture(78L, 31L, LocalDate.now().minusDays(1), 20);
        when(stockLotRepository.findByStatusAndExpiryDateBefore(LotStatus.AVAILABLE, LocalDate.now()))
                .thenReturn(List.of(lotA, lotB));
        when(stockLotRepository.existsByProductOptionIdAndStatus(31L, LotStatus.AVAILABLE)).thenReturn(false);

        // when
        adminLotService.expireLots();

        // then
        verify(stockLotRepository, times(1)).existsByProductOptionIdAndStatus(31L, LotStatus.AVAILABLE);
        verify(eventPublisher, times(1)).publishEvent(new OptionAvailabilityChangedEvent(31L, true));
    }

    @Test
    void 이미_처리된_로트는_건너뛴다() {
        // given — 조회 조건이 AVAILABLE이지만, 조회와 처리 사이 상태가 바뀐 경우를 방어적으로 대비한다
        StockLot lot = nonAvailableLotFixture(77L, 31L, LocalDate.now().minusDays(1), LotStatus.DISPOSED);
        when(stockLotRepository.findByStatusAndExpiryDateBefore(LotStatus.AVAILABLE, LocalDate.now()))
                .thenReturn(List.of(lot));

        // when
        AdminLotExpireResponse result = adminLotService.expireLots();

        // then
        assertThat(result.lots()).isEmpty();
        verify(stockMovementRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 가용_수량이_0인_로트는_이력_없이_상태만_전환한다() {
        // given — 예약으로 이미 다 소진됐지만 SOLD_OUT 전환이 없어 status는 여전히 AVAILABLE인 로트.
        // StockMovement가 quantity>0을 강제하므로, 여기서 이력을 남기려 하면 배치 전체가 예외로 롤백된다
        StockLot lot = lotFixture(77L, 31L, LocalDate.now().minusDays(1), 40);
        ReflectionTestUtils.setField(lot, "availableQty", 0);
        when(stockLotRepository.findByStatusAndExpiryDateBefore(LotStatus.AVAILABLE, LocalDate.now()))
                .thenReturn(List.of(lot));
        when(stockLotRepository.existsByProductOptionIdAndStatus(31L, LotStatus.AVAILABLE)).thenReturn(false);

        // when
        AdminLotExpireResponse result = adminLotService.expireLots();

        // then
        assertThat(result.lots()).hasSize(1);
        assertThat(result.lots().get(0).status()).isEqualTo("EXPIRED");
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void 만료_대상_조회_중_락_경합이_나면_처리중_오류로_감싼다() {
        // given — findByStatusAndExpiryDateBefore 자체가 쓰기 락 조회라 락 대기 타임아웃/교착이 날 수 있다
        when(stockLotRepository.findByStatusAndExpiryDateBefore(LotStatus.AVAILABLE, LocalDate.now()))
                .thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));

        // when, then
        assertThatThrownBy(() -> adminLotService.expireLots())
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.EXPIRE_IN_PROGRESS);
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

    @Test
    void 상품의_로트_전체를_소비기한_오름차순으로_조회한다() {
        // given
        when(productApi.findOptionIds(12L)).thenReturn(List.of(31L, 45L));
        StockLot lot1 = lotOf(31L, LocalDate.of(2026, 8, 31), 77L);
        StockLot lot2 = lotOf(45L, LocalDate.of(2026, 9, 10), 78L);
        when(stockLotRepository.findByProductOptionIdInOrderByExpiryDateAsc(List.of(31L, 45L)))
                .thenReturn(List.of(lot1, lot2));

        // when
        AdminLotListResponse result = adminLotService.findAllByProduct(12L, false);

        // then
        assertThat(result.lots()).hasSize(2);
        assertThat(result.lots().get(0).stockLotId()).isEqualTo(77L);
        assertThat(result.lots().get(1).stockLotId()).isEqualTo(78L);
    }

    @Test
    void availableOnly가_true면_판매_가능_로트만_조회한다() {
        // given
        when(productApi.findOptionIds(12L)).thenReturn(List.of(31L));
        StockLot lot = lotOf(31L, LocalDate.of(2026, 8, 31), 77L);
        when(stockLotRepository.findByProductOptionIdInAndStatusOrderByExpiryDateAsc(
                List.of(31L), LotStatus.AVAILABLE)).thenReturn(List.of(lot));

        // when
        AdminLotListResponse result = adminLotService.findAllByProduct(12L, true);

        // then
        assertThat(result.lots()).hasSize(1);
        verify(stockLotRepository, never()).findByProductOptionIdInOrderByExpiryDateAsc(any());
    }

    @Test
    void 상품에_옵션이_하나도_없으면_상품_없음으로_실패한다() {
        // given — 상품 등록 시 옵션이 최소 1개 필수라, 옵션 ID 목록이 비어있다는 건 상품 자체가 없다는 뜻이다
        when(productApi.findOptionIds(999L)).thenReturn(List.of());

        // when, then
        assertThatThrownBy(() -> adminLotService.findAllByProduct(999L, false))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.OPTION_NOT_FOUND);
        verify(stockLotRepository, never()).findByProductOptionIdInOrderByExpiryDateAsc(any());
        verify(stockLotRepository, never()).findByProductOptionIdInAndStatusOrderByExpiryDateAsc(any(), any());
    }

    private StockLot lotOf(Long optionId, LocalDate expiryDate, Long id) {
        StockLot lot = StockLot.register("req-" + id, optionId, LocalDate.of(2026, 8, 1), expiryDate, 100);
        ReflectionTestUtils.setField(lot, "id", id);
        return lot;
    }

    // 실제 저장이 없는 단위 테스트에서 JPA가 채워줄 생성 ID를 대신 채워준다.
    // StockMovement.inbound()가 stockLot.getId()를 그대로 넘겨받아 써야 해서 필요하다.
    private void stubSaveAssignsId() {
        when(stockLotRepository.save(any())).thenAnswer(invocation -> {
            StockLot stockLot = invocation.getArgument(0);
            ReflectionTestUtils.setField(stockLot, "id", 77L);
            return stockLot;
        });
    }
}
