package com.freshmarket.product.internal.service;

import com.freshmarket.product.internal.dto.CategoryResponse;
import com.freshmarket.product.internal.repository.CategoryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 회원에게 카테고리 목록을 노출한다. 관리자 관리(등록/수정/삭제)는 AdminCategoryService 가 맡는다
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;

    /*
     * 카테고리 전체를 조회한다.
     *
     * AdminCategoryService.findAll() 과 지금은 본문이 같지만 의도적으로 분리해 둔다 (MNT-3-01).
     * 회원은 최상위만, 관리자는 하위까지 보는 식으로 노출 범위가 갈릴 것이 예상되고,
     * 회원 조회가 관리자 서비스를 호출하는 것은 관심사가 뒤집힌다.
     * 지금은 최상위 5종만 시드되어 있어 계층 정렬 없이 그대로 내려간다.
     */
    public List<CategoryResponse> getCategories() {
        return categoryRepository.findAll().stream()
                .map(CategoryResponse::from)
                .toList();
    }
}