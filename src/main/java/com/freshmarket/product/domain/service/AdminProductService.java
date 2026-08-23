package com.freshmarket.product.domain.service;

import com.freshmarket.common.response.PageResponse;
import com.freshmarket.product.domain.dto.AdminProductCreateRequest;
import com.freshmarket.product.domain.dto.AdminProductListItem;
import com.freshmarket.product.domain.dto.AdminProductOptionCreateRequest;
import com.freshmarket.product.domain.dto.AdminProductOptionResponse;
import com.freshmarket.product.domain.dto.AdminProductResponse;
import com.freshmarket.product.domain.dto.AdminProductSearchCondition;
import com.freshmarket.product.domain.dto.CategorySummary;
import com.freshmarket.product.domain.entity.Category;
import com.freshmarket.product.domain.entity.Product;
import com.freshmarket.product.domain.entity.ProductOption;
import com.freshmarket.product.domain.entity.StorageType;
import com.freshmarket.product.domain.exception.ProductErrorCode;
import com.freshmarket.product.domain.exception.ProductException;
import com.freshmarket.product.domain.repository.CategoryRepository;
import com.freshmarket.product.domain.repository.ProductOptionRepository;
import com.freshmarket.product.domain.repository.ProductQueryRepository;
import com.freshmarket.product.domain.repository.ProductRepository;
import java.security.SecureRandom;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 관리자 화면에서 상품과 옵션을 등록·조회하는 기능을 담당한다
@Service
@Transactional(readOnly = true)
public class AdminProductService {

    private static final String CODE_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CODE_RANDOM_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final CategoryRepository categoryRepository;
    private final ProductQueryRepository productQueryRepository;

    public AdminProductService(ProductRepository productRepository,
            ProductOptionRepository productOptionRepository,
            CategoryRepository categoryRepository,
            ProductQueryRepository productQueryRepository) {
        this.productRepository = productRepository;
        this.productOptionRepository = productOptionRepository;
        this.categoryRepository = categoryRepository;
        this.productQueryRepository = productQueryRepository;
    }

    /*
     * 조건에 맞는 상품 목록을 페이징 조회한다. 판매안함/품절/삭제까지 전부 포함한다
     * (includeDeleted로 삭제 상품 포함 여부만 가른다). 재고 합계는 이 이슈 범위 밖이라 넣지 않는다.
     * 카테고리 이름은 페이지 안 상품들의 categoryId를 모아 한 번에 배치 조회한다(N+1 방지).
     */
    public PageResponse<AdminProductListItem> findAll(AdminProductSearchCondition condition) {
        Pageable pageable = PageRequest.of(condition.page(), condition.size());
        Page<Product> page = productQueryRepository.searchForAdmin(condition, pageable);
        Map<Long, String> categoryNames = categoryNamesOf(page.getContent());
        return PageResponse.from(page.map(product -> toListItem(product, categoryNames)));
    }

    // 상품 단건을 조회한다. 회원용 상세와 달리 삭제된 상품도 그대로 보여준다 — id 자체가 없을 때만 404
    public AdminProductResponse findById(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
        return responseOf(product);
    }

    private Map<Long, String> categoryNamesOf(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<Long> categoryIds = products.stream().map(Product::getCategoryId).distinct().toList();
        return categoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
    }

    private static AdminProductListItem toListItem(Product product, Map<Long, String> categoryNames) {
        CategorySummary category = new CategorySummary(
                product.getCategoryId(), categoryNames.get(product.getCategoryId()));
        return new AdminProductListItem(
                product.getId(), product.getProductCode(), product.getName(), category,
                product.getSaleStatus(), product.isDeleted());
    }

    /*
     * 상품과 옵션들을 함께 등록한다. 카테고리는 사전 확인하고, 공급처는 DB 제약으로 확인한다.
     * 같은 requestId로 재시도가 오면(API-5-07, AIP-155) 새로 등록하지 않고 최초 결과를 그대로 돌려준다.
     * 먼저 조회해 일반적인(순차적인) 재시도를 검증 전에 걸러내고, save() 시점의 uk_product_request_id
     * 위반은 두 요청이 거의 동시에 들어온 경합 상황을 잡는 안전망이다.
     */
    @Transactional
    public AdminProductResponse register(AdminProductCreateRequest request) {
        Optional<Product> existingProduct = productRepository.findByRequestId(request.requestId());
        if (existingProduct.isPresent()) {
            return responseOf(existingProduct.get());
        }

        validateCategoryExists(request.categoryId());
        StorageType storageType = StorageType.valueOf(request.storageType());
        int saleAvailableDaysFromExpiry = resolveSaleAvailableDaysFromExpiry(request.saleAvailableDaysFromExpiry());

        Product product = Product.register(request.requestId(), generateProductCode(), request.name(),
                request.categoryId(), request.supplierId(), storageType, saleAvailableDaysFromExpiry,
                request.description());
        try {
            productRepository.save(product);
        } catch (DataIntegrityViolationException e) {
            if (isConstraintViolation(e, "uk_product_request_id")) {
                return responseOf(findByRequestIdOrThrow(request.requestId()));
            }
            throw resolveSaveException(e);
        } catch (PessimisticLockingFailureException e) {
            return responseOfInProgressRetry(request.requestId(), e);
        }

        List<AdminProductOptionResponse> optionResponses = registerOptions(product.getId(), request.options());
        return AdminProductResponse.of(product, optionResponses);
    }

    // 이미 등록된 상품의 옵션을 다시 조회해 응답을 재구성한다. 재시도 응답 재사용에 쓰인다
    private AdminProductResponse responseOf(Product product) {
        List<AdminProductOptionResponse> optionResponses = productOptionRepository.findAllByProductId(product.getId())
                .stream()
                .map(AdminProductOptionResponse::from)
                .toList();
        return AdminProductResponse.of(product, optionResponses);
    }

    // save() 시점에 uk_product_request_id 위반이 났다면, 동시 재시도가 먼저 커밋을 마친 것이라 반드시 존재한다
    private Product findByRequestIdOrThrow(String requestId) {
        return productRepository.findByRequestId(requestId)
                .orElseThrow(() -> new IllegalStateException(
                        "request_id 유니크 위반 직후 재조회에 실패했다: " + requestId));
    }

    /*
     * save()가 유니크 위반이 아니라 락 대기 타임아웃(PessimisticLockingFailureException)으로 실패한 경우다.
     * 동시 재시도가 아직 커밋 전이라 유니크 위반조차 나지 않고 대기하다 시간 초과된 상황이므로, 그 사이
     * 커밋됐을 수도 있어 한 번 더 조회하고, 그래도 없으면 아직 처리 중이라는 뜻이라 클라이언트에게
     * 재시도를 안내한다(자체 재시도 루프는 넣지 않는다 — resolveSaveException의 이유와 동일).
     */
    private AdminProductResponse responseOfInProgressRetry(String requestId, PessimisticLockingFailureException cause) {
        return productRepository.findByRequestId(requestId)
                .map(this::responseOf)
                .orElseThrow(() -> new ProductException(ProductErrorCode.REGISTRATION_IN_PROGRESS, cause));
    }

    // 카테고리가 실재하는지 미리 확인한다. 카테고리 등록 때 상위 카테고리 존재를 확인했던 것과 같은 패턴이다
    private void validateCategoryExists(Long categoryId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new ProductException(ProductErrorCode.CATEGORY_NOT_FOUND);
        }
    }

    // 생략된 값은 0으로 취급한다 (product.md: "0 이상. 기본 0")
    private int resolveSaleAvailableDaysFromExpiry(Integer saleAvailableDaysFromExpiry) {
        return saleAvailableDaysFromExpiry != null ? saleAvailableDaysFromExpiry : 0;
    }

    // 옵션들을 하나씩 저장한다. saveAll()로 묶지 않고 개별 저장하는 이유는 register()와 동일(JPA-3, cascade 없이 각 저장을 추적 가능하게)
    private List<AdminProductOptionResponse> registerOptions(Long productId,
            List<AdminProductOptionCreateRequest> optionRequests) {
        return optionRequests.stream()
                .map(optionRequest -> saveOption(productId, optionRequest))
                .map(AdminProductOptionResponse::from)
                .toList();
    }

    // 옵션 하나를 생성해 저장한다. 같은 상품 안에서 옵션명이 겹치면 OPTION_DUPLICATE_NAME으로 바꾼다
    private ProductOption saveOption(Long productId, AdminProductOptionCreateRequest optionRequest) {
        ProductOption option = ProductOption.register(productId, optionRequest.name(), optionRequest.price());
        try {
            productOptionRepository.save(option);
        } catch (DataIntegrityViolationException e) {
            if (isConstraintViolation(e, "uk_option_product_name")) {
                throw new ProductException(ProductErrorCode.OPTION_DUPLICATE_NAME, e);
            }
            throw e;
        }
        return option;
    }

    /*
     * Supplier 리포지토리가 아직 없어 supplierId를 미리 확인할 방법이 없다. 대신 저장 시점에
     * fk_product_supplier 위반을 잡아 SUPPLIER_NOT_FOUND로 바꾼다.
     * categoryId는 위에서 이미 확인했으므로 fk_product_category 위반은 그 사이 카테고리가
     * 삭제된 경합 상황에서만 발생한다.
     * product_code(uk_product_code) 위반은 영숫자 6자리 무작위 값이라 사실상 일어나지 않는다.
     * 재시도는 넣지 않았다 — 같은 트랜잭션에서 flush 실패 후 계속 쓰면 영속성 컨텍스트가
     * 불안정해질 수 있어, 일어나지도 않을 경합을 막으려 위험을 감수할 이유가 없다.
     */
    private ProductException resolveSaveException(DataIntegrityViolationException e) {
        if (isConstraintViolation(e, "fk_product_supplier")) {
            return new ProductException(ProductErrorCode.SUPPLIER_NOT_FOUND, e);
        }
        if (isConstraintViolation(e, "fk_product_category")) {
            return new ProductException(ProductErrorCode.CATEGORY_NOT_FOUND, e);
        }
        throw e;
    }

    // DB 예외의 근본 원인 메시지에 제약 이름이 들어있는지로 어떤 제약을 위반했는지 구분한다
    private boolean isConstraintViolation(DataIntegrityViolationException e, String constraintName) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(constraintName);
    }

    // P-{연도}-{영숫자 6자리 랜덤}. product_id에 의존하지 않아 INSERT 전에 완성할 수 있다
    private String generateProductCode() {
        StringBuilder suffix = new StringBuilder(CODE_RANDOM_LENGTH);
        for (int i = 0; i < CODE_RANDOM_LENGTH; i++) {
            suffix.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return "P-" + Year.now().getValue() + "-" + suffix;
    }
}
