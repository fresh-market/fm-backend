package com.freshmarket.product.internal.service;

import com.freshmarket.product.internal.dto.CategoryResponse;
import com.freshmarket.product.internal.entity.Category;
import com.freshmarket.product.internal.exception.ProductErrorCode;
import com.freshmarket.product.internal.exception.ProductException;
import com.freshmarket.product.internal.repository.CategoryRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 관리자 화면에서 카테고리를 등록/조회/수정/삭제하는 기능을 담당한다
@Service
@Transactional(readOnly = true)
public class AdminCategoryService {

    private final CategoryRepository categoryRepository;

    public AdminCategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    // 카테고리 전체 목록을 반환한다
    public List<CategoryResponse> findAll() {
        return categoryRepository.findAll().stream()
                .map(CategoryResponse::from)
                .toList();
    }

    // 카테고리 하나를 ID로 조회한다. 없으면 CATEGORY_NOT_FOUND
    public CategoryResponse findById(Long categoryId) {
        return CategoryResponse.from(getCategoryOrThrow(categoryId));
    }

    // 카테고리를 새로 등록한다. 부모가 존재해야 하고, 같은 부모 아래 이름이 중복되면 안 된다
    @Transactional
    public CategoryResponse register(String name, Long parentId) {
        String trimmedName = name.trim();
        validateParentExists(parentId);
        if (existsDuplicateName(parentId, trimmedName)) {
            throw new ProductException(ProductErrorCode.CATEGORY_DUPLICATE_NAME);
        }
        Category category = Category.register(trimmedName, parentId);
        try {
            categoryRepository.save(category);
        } catch (DataIntegrityViolationException e) {
            // 사전 검사(존재 확인/중복 검사)와 저장 사이의 동시 요청 경합을 DB 제약으로 다시 잡는다.
            // 그사이 부모가 삭제됐다면 fk_category_parent 위반이라 존재 확인 실패로,
            // 아니면 이름 유니크 제약 위반이라 중복으로 본다
            ProductErrorCode errorCode = isParentConstraintViolation(e)
                    ? ProductErrorCode.CATEGORY_NOT_FOUND
                    : ProductErrorCode.CATEGORY_DUPLICATE_NAME;
            throw new ProductException(errorCode, e);
        }
        return CategoryResponse.from(category);
    }

    // DB 예외의 근본 원인 메시지에 부모 참조 제약 이름이 들어있는지로, 부모 소멸과 이름 중복을 구분한다
    private boolean isParentConstraintViolation(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains("fk_category_parent");
    }

    // 카테고리 이름을 바꾼다. 자기 자신과 같은 이름으로 바꾸는 것은 중복으로 보지 않는다
    @Transactional
    public CategoryResponse rename(Long categoryId, String newName) {
        String trimmedName = newName.trim();
        Category category = getCategoryOrThrow(categoryId);
        boolean nameChanged = !category.getName().equals(trimmedName);
        if (nameChanged && existsDuplicateName(category.getParentId(), trimmedName)) {
            throw new ProductException(ProductErrorCode.CATEGORY_DUPLICATE_NAME);
        }
        try {
            category.rename(trimmedName);
            categoryRepository.flush();   // UPDATE를 지금 실행시켜서 제약 위반을 이 안에서 잡음
        } catch (DataIntegrityViolationException e) {
            throw new ProductException(ProductErrorCode.CATEGORY_DUPLICATE_NAME, e);
        }
        return CategoryResponse.from(category);
    }

    /*
     * 카테고리를 삭제한다. 하위 카테고리가 있거나 소속 상품이 있으면 거부한다.
     * 대상 행에 비관적 쓰기 락을 걸어 두 동시성 문제를 함께 막는다.
     *   1) 같은 카테고리를 여러 요청이 동시에 삭제 — 뒤에 락을 잡는 쪽은 대기했다가
     *      앞선 삭제가 끝난 뒤 빈 결과를 받아 CATEGORY_NOT_FOUND로 정리된다
     *   2) 삭제 확인(하위 카테고리 등)과 실제 삭제 사이에 다른 트랜잭션이 이 카테고리를
     *      참조하는 행(하위 카테고리, 상품)을 끼워 넣는 경우 — 락이 걸린 동안 그 INSERT가
     *      FK 검사에서 막혀 대기하므로 확인과 삭제가 원자적으로 처리된다
     */
    @Transactional
    public void delete(Long categoryId) {
        Category category = categoryRepository.findByIdForUpdate(categoryId)
                .orElseThrow(() -> new ProductException(ProductErrorCode.CATEGORY_NOT_FOUND));
        if (categoryRepository.existsByParentId(categoryId)) {
            throw new ProductException(ProductErrorCode.CATEGORY_HAS_CHILDREN);
        }
        try {
            categoryRepository.delete(category);
            categoryRepository.flush();   // DELETE를 지금 실행시켜서 FK 위반을 이 안에서 잡음
        } catch (DataIntegrityViolationException e) {
            // 하위 카테고리는 위에서 걸러졌으므로, 여기 남는 FK 위반은 소속 상품 때문이다
            throw new ProductException(ProductErrorCode.CATEGORY_HAS_PRODUCTS, e);
        }
    }

    // 같은 부모 아래(또는 최상위끼리) 이름이 이미 있는지 확인한다
    private boolean existsDuplicateName(Long parentId, String name) {
        return parentId == null
                ? categoryRepository.existsByParentIdIsNullAndName(name)
                : categoryRepository.existsByParentIdAndName(parentId, name);
    }

    // 상위 카테고리로 지정한 ID가 실제로 존재하는지 확인한다. parentId가 null이면 최상위라 검사하지 않는다
    private void validateParentExists(Long parentId) {
        if (parentId != null && !categoryRepository.existsById(parentId)) {
            throw new ProductException(ProductErrorCode.CATEGORY_NOT_FOUND);
        }
    }

    // ID로 카테고리를 찾고, 없으면 CATEGORY_NOT_FOUND를 던진다
    private Category getCategoryOrThrow(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ProductException(ProductErrorCode.CATEGORY_NOT_FOUND));
    }
}
