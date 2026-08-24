package com.freshmarket.product.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.common.response.PageResponse;
import com.freshmarket.product.domain.dto.AdminProductCreateRequest;
import com.freshmarket.product.domain.dto.AdminProductListItem;
import com.freshmarket.product.domain.dto.AdminProductListRow;
import com.freshmarket.product.domain.dto.AdminProductOptionCreateRequest;
import com.freshmarket.product.domain.dto.AdminProductResponse;
import com.freshmarket.product.domain.dto.AdminProductSearchCondition;
import com.freshmarket.product.domain.entity.Category;
import com.freshmarket.product.domain.entity.Product;
import com.freshmarket.product.domain.entity.ProductOption;
import com.freshmarket.product.domain.entity.SaleStatus;
import com.freshmarket.product.domain.entity.StorageType;
import com.freshmarket.product.domain.exception.ProductErrorCode;
import com.freshmarket.product.domain.exception.ProductException;
import com.freshmarket.product.domain.repository.CategoryRepository;
import com.freshmarket.product.domain.repository.ProductOptionRepository;
import com.freshmarket.product.domain.repository.ProductQueryRepository;
import com.freshmarket.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

// AdminProductService의 등록, 목록/단건 조회와 각 실패 케이스를 검증한다
@ExtendWith(MockitoExtension.class)
class AdminProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductOptionRepository productOptionRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ProductQueryRepository productQueryRepository;

    @InjectMocks
    private AdminProductService adminProductService;

    @Test
    void 상품과_옵션을_함께_등록한다() {
        // given
        when(categoryRepository.existsById(4L)).thenReturn(true);
        stubSaveAssignsId();
        AdminProductCreateRequest request = new AdminProductCreateRequest(
                "제주 감귤 1kg", "req-1", 4L, 2L, "COLD", 3, "달콤한 제주 감귤입니다.",
                List.of(new AdminProductOptionCreateRequest("1kg", 12900)));

        // when
        AdminProductResponse result = adminProductService.register(request);

        // then
        assertThat(result.name()).isEqualTo("제주 감귤 1kg");
        assertThat(result.requestId()).isEqualTo("req-1");
        assertThat(result.categoryId()).isEqualTo(4L);
        assertThat(result.supplierId()).isEqualTo(2L);
        assertThat(result.storageType()).isEqualTo("COLD");
        assertThat(result.saleAvailableDaysFromExpiry()).isEqualTo(3);
        assertThat(result.description()).isEqualTo("달콤한 제주 감귤입니다.");
        assertThat(result.options()).hasSize(1);
        assertThat(result.options().get(0).name()).isEqualTo("1kg");
        assertThat(result.options().get(0).price()).isEqualTo(12900);
    }

    @Test
    void 판매_가능_최소_잔여일수를_생략하면_0으로_등록된다() {
        // given
        when(categoryRepository.existsById(4L)).thenReturn(true);
        stubSaveAssignsId();
        AdminProductCreateRequest request = new AdminProductCreateRequest(
                "제주 감귤 1kg", "req-1", 4L, 2L, "COLD", null, null,
                List.of(new AdminProductOptionCreateRequest("1kg", 12900)));

        // when
        AdminProductResponse result = adminProductService.register(request);

        // then
        assertThat(result.saleAvailableDaysFromExpiry()).isEqualTo(0);
    }

    @Test
    void 같은_요청_식별자로_재시도하면_기존_상품을_그대로_반환한다() {
        // given — 이전 요청으로 이미 등록된 상품이 있는 상황(사전 조회에서 바로 잡힘)
        Product existing = Product.register("req-1", "P-2026-ABC123", "제주 감귤 1kg", 4L, 2L,
                StorageType.COLD, 3, "달콤한 제주 감귤입니다.");
        ReflectionTestUtils.setField(existing, "id", 1L);
        when(productRepository.findByRequestId("req-1")).thenReturn(Optional.of(existing));
        when(productOptionRepository.findAllByProductId(1L))
                .thenReturn(List.of(ProductOption.register(1L, "1kg", 12900)));
        AdminProductCreateRequest request = new AdminProductCreateRequest(
                "제주 감귤 1kg", "req-1", 4L, 2L, "COLD", 3, "달콤한 제주 감귤입니다.",
                List.of(new AdminProductOptionCreateRequest("1kg", 12900)));

        // when
        AdminProductResponse result = adminProductService.register(request);

        // then
        assertThat(result.productId()).isEqualTo(1L);
        assertThat(result.options()).hasSize(1);
        verify(productRepository, never()).save(any());
        verify(categoryRepository, never()).existsById(any());
    }

    @Test
    void 저장_중_요청_식별자가_동시에_중복되면_기존_상품을_반환한다() {
        // given — 사전 조회 시점엔 없었지만, save() 직전에 동시 재시도가 먼저 커밋을 마친 경합 상황
        when(categoryRepository.existsById(4L)).thenReturn(true);
        Product existing = Product.register("req-1", "P-2026-ABC123", "제주 감귤 1kg", 4L, 2L,
                StorageType.COLD, 3, "달콤한 제주 감귤입니다.");
        ReflectionTestUtils.setField(existing, "id", 1L);
        when(productRepository.findByRequestId("req-1"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(productOptionRepository.findAllByProductId(1L))
                .thenReturn(List.of(ProductOption.register(1L, "1kg", 12900)));
        when(productRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry 'req-1' for key 'product.uk_product_request_id'"));
        AdminProductCreateRequest request = new AdminProductCreateRequest(
                "제주 감귤 1kg", "req-1", 4L, 2L, "COLD", 3, "달콤한 제주 감귤입니다.",
                List.of(new AdminProductOptionCreateRequest("1kg", 12900)));

        // when
        AdminProductResponse result = adminProductService.register(request);

        // then
        assertThat(result.productId()).isEqualTo(1L);
        verify(productOptionRepository, never()).save(any());
    }

    @Test
    void 락_대기_타임아웃_후_재조회에서_찾으면_기존_상품을_반환한다() {
        // given — save()가 유니크 위반이 아니라 락 대기 타임아웃으로 실패했지만, 그 사이 첫 요청이 커밋을 마친 상황
        when(categoryRepository.existsById(4L)).thenReturn(true);
        Product existing = Product.register("req-1", "P-2026-ABC123", "제주 감귤 1kg", 4L, 2L,
                StorageType.COLD, 3, "달콤한 제주 감귤입니다.");
        ReflectionTestUtils.setField(existing, "id", 1L);
        when(productRepository.findByRequestId("req-1"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(productOptionRepository.findAllByProductId(1L))
                .thenReturn(List.of(ProductOption.register(1L, "1kg", 12900)));
        when(productRepository.save(any())).thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));
        AdminProductCreateRequest request = new AdminProductCreateRequest(
                "제주 감귤 1kg", "req-1", 4L, 2L, "COLD", 3, "달콤한 제주 감귤입니다.",
                List.of(new AdminProductOptionCreateRequest("1kg", 12900)));

        // when
        AdminProductResponse result = adminProductService.register(request);

        // then
        assertThat(result.productId()).isEqualTo(1L);
        verify(productOptionRepository, never()).save(any());
    }

    @Test
    void 락_대기_타임아웃_후_재조회에서도_못_찾으면_처리중_오류를_던진다() {
        // given — 첫 요청이 타임아웃 안에도 여전히 처리 중인 상황(비정상적으로 느린 경우)
        when(categoryRepository.existsById(4L)).thenReturn(true);
        when(productRepository.findByRequestId("req-1")).thenReturn(Optional.empty());
        when(productRepository.save(any())).thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));
        AdminProductCreateRequest request = new AdminProductCreateRequest(
                "제주 감귤 1kg", "req-1", 4L, 2L, "COLD", 3, null,
                List.of(new AdminProductOptionCreateRequest("1kg", 12900)));

        // when, then
        assertThatThrownBy(() -> adminProductService.register(request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.REGISTRATION_IN_PROGRESS);
    }

    @Test
    void 요청_식별자_위반_직후_재조회가_비어있으면_예외를_던진다() {
        // given — 유니크 위반이 났다는 건 그 순간 동시 재시도가 커밋을 마쳤다는 뜻이라 재조회는 항상 있어야 한다.
        // 이 방어 분기(있을 수 없는 상황)의 커버리지를 위한 케이스
        when(categoryRepository.existsById(4L)).thenReturn(true);
        when(productRepository.findByRequestId("req-1")).thenReturn(Optional.empty());
        when(productRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry 'req-1' for key 'product.uk_product_request_id'"));
        AdminProductCreateRequest request = new AdminProductCreateRequest(
                "제주 감귤 1kg", "req-1", 4L, 2L, "COLD", 3, null,
                List.of(new AdminProductOptionCreateRequest("1kg", 12900)));

        // when, then
        assertThatThrownBy(() -> adminProductService.register(request))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 존재하지_않는_카테고리로_등록하면_실패한다() {
        // given
        when(categoryRepository.existsById(999L)).thenReturn(false);
        AdminProductCreateRequest request = new AdminProductCreateRequest(
                "제주 감귤 1kg", "req-1", 999L, 2L, "COLD", 3, null,
                List.of(new AdminProductOptionCreateRequest("1kg", 12900)));

        // when, then
        assertThatThrownBy(() -> adminProductService.register(request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.CATEGORY_NOT_FOUND);
        verify(productRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_공급처로_등록하면_실패한다() {
        // given
        when(categoryRepository.existsById(4L)).thenReturn(true);
        when(productRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`freshmarket`.`product`, CONSTRAINT `fk_product_supplier` "
                        + "FOREIGN KEY (`supplier_id`) REFERENCES `supplier` (`supplier_id`))"));
        AdminProductCreateRequest request = new AdminProductCreateRequest(
                "제주 감귤 1kg", "req-1", 4L, 999L, "COLD", 3, null,
                List.of(new AdminProductOptionCreateRequest("1kg", 12900)));

        // when, then
        assertThatThrownBy(() -> adminProductService.register(request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.SUPPLIER_NOT_FOUND);
        verify(productOptionRepository, never()).save(any());
    }

    @Test
    void 등록_중_카테고리가_동시에_삭제되면_존재하지_않는_카테고리로_응답한다() {
        // given — 사전 확인(existsById) 통과 직후, save() 시점엔 카테고리가 이미 삭제된 경합 상황
        when(categoryRepository.existsById(4L)).thenReturn(true);
        when(productRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`freshmarket`.`product`, CONSTRAINT `fk_product_category` "
                        + "FOREIGN KEY (`category_id`) REFERENCES `category` (`category_id`))"));
        AdminProductCreateRequest request = new AdminProductCreateRequest(
                "제주 감귤 1kg", "req-1", 4L, 2L, "COLD", 3, null,
                List.of(new AdminProductOptionCreateRequest("1kg", 12900)));

        // when, then
        assertThatThrownBy(() -> adminProductService.register(request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    void 같은_상품_안에_옵션_이름이_겹치면_등록에_실패한다() {
        // given — 옵션 목록 안에서 이름이 중복된 상황(예: "1kg"을 두 번 보냄)
        when(categoryRepository.existsById(4L)).thenReturn(true);
        stubSaveAssignsId();
        when(productOptionRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry '1-1kg' for key 'product_option.uk_option_product_name'"));
        AdminProductCreateRequest request = new AdminProductCreateRequest(
                "제주 감귤 1kg", "req-1", 4L, 2L, "COLD", 3, null,
                List.of(new AdminProductOptionCreateRequest("1kg", 12900),
                        new AdminProductOptionCreateRequest("1kg", 15900)));

        // when, then
        assertThatThrownBy(() -> adminProductService.register(request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.OPTION_DUPLICATE_NAME);
    }

    @Test
    void 알_수_없는_제약_위반은_그대로_전파된다() {
        // given — product_code 유니크 제약처럼 별도로 변환하지 않는 위반
        when(categoryRepository.existsById(4L)).thenReturn(true);
        DataIntegrityViolationException unknownViolation = new DataIntegrityViolationException(
                "Duplicate entry for key 'uk_product_code'");
        when(productRepository.save(any())).thenThrow(unknownViolation);
        AdminProductCreateRequest request = new AdminProductCreateRequest(
                "제주 감귤 1kg", "req-1", 4L, 2L, "COLD", 3, null,
                List.of(new AdminProductOptionCreateRequest("1kg", 12900)));

        // when, then
        assertThatThrownBy(() -> adminProductService.register(request))
                .isSameAs(unknownViolation);
    }

    @Test
    void 옵션_저장_중_알_수_없는_제약_위반은_그대로_전파된다() {
        // given — 옵션명 중복이 아닌 다른 위반(예: chk_option_price처럼 별도로 변환하지 않는 제약)
        when(categoryRepository.existsById(4L)).thenReturn(true);
        stubSaveAssignsId();
        DataIntegrityViolationException unknownViolation = new DataIntegrityViolationException(
                "Check constraint 'chk_option_price' is violated");
        when(productOptionRepository.save(any())).thenThrow(unknownViolation);
        AdminProductCreateRequest request = new AdminProductCreateRequest(
                "제주 감귤 1kg", "req-1", 4L, 2L, "COLD", 3, null,
                List.of(new AdminProductOptionCreateRequest("1kg", 12900)));

        // when, then
        assertThatThrownBy(() -> adminProductService.register(request))
                .isSameAs(unknownViolation);
    }

    @Test
    void 조건에_맞는_상품_목록을_페이지로_조회한다() {
        // given
        AdminProductSearchCondition condition = new AdminProductSearchCondition(
                null, null, null, false, 0, 20);
        Pageable pageable = PageRequest.of(0, 20);
        AdminProductListRow row = productListRowFixture(1L, "제주 감귤 1kg", 4L, false);
        when(productQueryRepository.searchForAdmin(condition, pageable))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 1));
        when(categoryRepository.findAllById(List.of(4L))).thenReturn(List.of(categoryFixture(4L, "과일")));

        // when
        PageResponse<AdminProductListItem> result = adminProductService.findAll(condition);

        // then
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).name()).isEqualTo("제주 감귤 1kg");
        assertThat(result.items().get(0).category().name()).isEqualTo("과일");
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void 삭제된_상품도_deleted가_true로_내려간다() {
        // given
        AdminProductSearchCondition condition = new AdminProductSearchCondition(
                null, null, null, true, 0, 20);
        Pageable pageable = PageRequest.of(0, 20);
        AdminProductListRow deleted = productListRowFixture(1L, "제주 감귤 1kg", 4L, true);
        when(productQueryRepository.searchForAdmin(condition, pageable))
                .thenReturn(new PageImpl<>(List.of(deleted), pageable, 1));
        when(categoryRepository.findAllById(List.of(4L))).thenReturn(List.of(categoryFixture(4L, "과일")));

        // when
        PageResponse<AdminProductListItem> result = adminProductService.findAll(condition);

        // then
        assertThat(result.items().get(0).deleted()).isTrue();
    }

    @Test
    void 목록_조회_결과가_없으면_빈_목록을_준다() {
        // given
        AdminProductSearchCondition condition = new AdminProductSearchCondition(
                null, null, null, false, 0, 20);
        Pageable pageable = PageRequest.of(0, 20);
        when(productQueryRepository.searchForAdmin(condition, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        // when
        PageResponse<AdminProductListItem> result = adminProductService.findAll(condition);

        // then
        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(0);
    }

    @Test
    void 상품_단건을_조회한다() {
        // given
        Product product = productFixture(1L, "제주 감귤 1kg", 4L);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productOptionRepository.findAllByProductId(1L))
                .thenReturn(List.of(ProductOption.register(1L, "1kg", 12900)));

        // when
        AdminProductResponse result = adminProductService.findById(1L);

        // then
        assertThat(result.productId()).isEqualTo(1L);
        assertThat(result.options()).hasSize(1);
    }

    @Test
    void 삭제된_상품도_단건_조회는_그대로_보여준다() {
        // given — 회원용 상세와 달리 관리자는 삭제 상품도 조회할 수 있어야 한다
        Product deleted = productFixture(1L, "제주 감귤 1kg", 4L);
        ReflectionTestUtils.setField(deleted, "deletedAt", LocalDateTime.now());
        when(productRepository.findById(1L)).thenReturn(Optional.of(deleted));
        when(productOptionRepository.findAllByProductId(1L)).thenReturn(List.of());

        // when
        AdminProductResponse result = adminProductService.findById(1L);

        // then
        assertThat(result.productId()).isEqualTo(1L);
    }

    @Test
    void 존재하지_않는_상품을_단건_조회하면_실패한다() {
        // given
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> adminProductService.findById(999L))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.PRODUCT_NOT_FOUND);
    }

    private Product productFixture(Long id, String name, Long categoryId) {
        Product product = Product.register("req-" + id, "P-2026-ABC123", name, categoryId, 2L,
                StorageType.COLD, 3, "달콤한 제주 감귤입니다.");
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private AdminProductListRow productListRowFixture(Long id, String name, Long categoryId, boolean deleted) {
        return new AdminProductListRow(id, "P-2026-ABC123", name, categoryId, SaleStatus.ON_SALE, deleted);
    }

    private Category categoryFixture(Long id, String name) {
        Category category = Category.register(name);
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }

    // 실제 저장이 없는 단위 테스트에서 JPA가 채워줄 생성 ID를 대신 채워준다.
    // 옵션 등록(saveOption)이 product.getId()를 그대로 넘겨받아 써야 해서 필요하다.
    private void stubSaveAssignsId() {
        when(productRepository.save(any())).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            ReflectionTestUtils.setField(product, "id", 1L);
            return product;
        });
    }
}
