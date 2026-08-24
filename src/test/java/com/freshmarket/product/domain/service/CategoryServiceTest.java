package com.freshmarket.product.domain.service;

import static com.freshmarket.product.domain.entity.ProductFixture.카테고리;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.freshmarket.product.domain.dto.CategoryResponse;
import com.freshmarket.product.domain.entity.Category;
import com.freshmarket.product.domain.repository.CategoryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// CategoryService의 전체 조회를 검증한다
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryService categoryService;

    @Test
    void 카테고리_전체를_조회한다() {
        // given
        Category fruit = 카테고리(4L, "과일");
        Category seafood = 카테고리(1L, "수산물");
        when(categoryRepository.findAll()).thenReturn(List.of(seafood, fruit));

        // when
        List<CategoryResponse> result = categoryService.getCategories();

        // then
        assertThat(result).hasSize(2);
        assertThat(result).extracting(CategoryResponse::name)
                .containsExactly("수산물", "과일");
    }

    @Test
    void 카테고리가_없으면_빈_목록을_준다() {
        // given
        when(categoryRepository.findAll()).thenReturn(List.of());

        // when
        List<CategoryResponse> result = categoryService.getCategories();

        // then
        assertThat(result).isEmpty();
    }
}