package com.freshmarket.product.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.product.internal.dto.CategoryResponse;
import com.freshmarket.product.internal.entity.Category;
import com.freshmarket.product.internal.exception.ProductErrorCode;
import com.freshmarket.product.internal.exception.ProductException;
import com.freshmarket.product.internal.repository.CategoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

// AdminCategoryService의 조회/등록/수정/삭제와 각 실패 케이스를 검증한다
@ExtendWith(MockitoExtension.class)
class AdminCategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private AdminCategoryService adminCategoryService;

    @Test
    void 카테고리_목록을_전부_조회한다() {
        // given
        when(categoryRepository.findAll()).thenReturn(List.of(Category.register("수산물")));

        // when
        List<CategoryResponse> result = adminCategoryService.findAll();

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("수산물");
    }

    @Test
    void 존재하는_카테고리를_단건_조회한다() {
        // given
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(Category.register("육류")));

        // when
        CategoryResponse result = adminCategoryService.findById(1L);

        // then
        assertThat(result.name()).isEqualTo("육류");
    }

    @Test
    void 존재하지_않는_카테고리를_조회하면_실패한다() {
        // given
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> adminCategoryService.findById(999L))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    void 최상위_카테고리를_등록한다() {
        // given
        when(categoryRepository.existsByParentIdIsNullAndName("수산물")).thenReturn(false);

        // when
        CategoryResponse result = adminCategoryService.register("수산물", null);

        // then
        assertThat(result.name()).isEqualTo("수산물");
        assertThat(result.parentId()).isNull();
        verify(categoryRepository).save(any(Category.class));
    }

    @Test
    void 상위_카테고리가_존재하면_하위_카테고리를_등록한다() {
        // given
        when(categoryRepository.existsById(1L)).thenReturn(true);
        when(categoryRepository.existsByParentIdAndName(1L, "손질생선")).thenReturn(false);

        // when
        CategoryResponse result = adminCategoryService.register("손질생선", 1L);

        // then
        assertThat(result.parentId()).isEqualTo(1L);
    }

    @Test
    void 존재하지_않는_상위_카테고리로_등록하면_실패한다() {
        // given
        when(categoryRepository.existsById(999L)).thenReturn(false);

        // when, then
        assertThatThrownBy(() -> adminCategoryService.register("손질생선", 999L))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.CATEGORY_NOT_FOUND);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void 최상위_카테고리끼리_이름이_겹치면_등록에_실패한다() {
        // given — null 처리 버그의 회귀 테스트
        when(categoryRepository.existsByParentIdIsNullAndName("수산물")).thenReturn(true);

        // when, then
        assertThatThrownBy(() -> adminCategoryService.register("수산물", null))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.CATEGORY_DUPLICATE_NAME);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void 동시_요청으로_DB에서_중복이_감지되면_친절한_예외로_바꾼다() {
        // given
        when(categoryRepository.existsByParentIdIsNullAndName("수산물")).thenReturn(false);
        when(categoryRepository.save(any(Category.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        // when, then
        assertThatThrownBy(() -> adminCategoryService.register("수산물", null))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.CATEGORY_DUPLICATE_NAME);
    }

    @Test
    void 등록_중_부모가_동시에_삭제되면_존재하지_않는_카테고리로_응답한다() {
        // given — validateParentExists 통과 직후, save() 시점엔 부모가 이미 삭제된 경합 상황
        when(categoryRepository.existsById(1L)).thenReturn(true);
        when(categoryRepository.existsByParentIdAndName(1L, "손질생선")).thenReturn(false);
        when(categoryRepository.save(any(Category.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "Cannot add or update a child row: a foreign key constraint fails "
                                + "(`freshmarket`.`category`, CONSTRAINT `fk_category_parent` "
                                + "FOREIGN KEY (`parent_id`) REFERENCES `category` (`category_id`))"));

        // when, then
        assertThatThrownBy(() -> adminCategoryService.register("손질생선", 1L))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    void 카테고리_이름을_바꾼다() {
        // given
        Category category = Category.register("과일");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(categoryRepository.existsByParentIdIsNullAndName("채소과일")).thenReturn(false);

        // when
        CategoryResponse result = adminCategoryService.rename(1L, "채소과일");

        // then
        assertThat(result.name()).isEqualTo("채소과일");
    }

    @Test
    void 이름을_그대로_두면_자기_자신과의_중복으로_오판하지_않는다() {
        // given — 자기 자신 때문에 이 조회는 항상 true를 반환하는 상황을 그대로 재현
        Category category = Category.register("과일");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        lenient().when(categoryRepository.existsByParentIdIsNullAndName("과일")).thenReturn(true);

        // when
        CategoryResponse result = adminCategoryService.rename(1L, "과일");

        // then
        assertThat(result.name()).isEqualTo("과일");
    }

    @Test
    void 존재하지_않는_카테고리는_이름을_바꿀_수_없다() {
        // given
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> adminCategoryService.rename(999L, "새이름"))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    void 다른_카테고리와_이름이_겹치면_변경에_실패한다() {
        // given
        Category category = Category.register("과일");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(categoryRepository.existsByParentIdIsNullAndName("채소")).thenReturn(true);

        // when, then
        assertThatThrownBy(() -> adminCategoryService.rename(1L, "채소"))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.CATEGORY_DUPLICATE_NAME);
    }

    @Test
    void 카테고리를_삭제한다() {
        // given
        Category category = Category.register("유제품");
        when(categoryRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(category));

        // when
        adminCategoryService.delete(1L);

        // then
        verify(categoryRepository).delete(category);
    }

    @Test
    void 존재하지_않는_카테고리는_삭제할_수_없다() {
        // given
        when(categoryRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> adminCategoryService.delete(999L))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.CATEGORY_NOT_FOUND);
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void 하위_카테고리가_있으면_삭제할_수_없다() {
        // given
        Category category = Category.register("수산물");
        when(categoryRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(category));
        when(categoryRepository.existsByParentId(1L)).thenReturn(true);

        // when, then
        assertThatThrownBy(() -> adminCategoryService.delete(1L))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.CATEGORY_HAS_CHILDREN);
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void 소속_상품이_있으면_삭제할_수_없다() {
        // given
        Category category = Category.register("수산물");
        when(categoryRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(category));
        when(categoryRepository.existsByParentId(1L)).thenReturn(false);
        doThrow(new DataIntegrityViolationException("fk violation"))
                .when(categoryRepository).flush();

        // when, then
        assertThatThrownBy(() -> adminCategoryService.delete(1L))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.CATEGORY_HAS_PRODUCTS);
    }
}