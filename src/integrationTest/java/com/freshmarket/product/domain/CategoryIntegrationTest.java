package com.freshmarket.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.freshmarket.config.JpaAuditingConfig;
import com.freshmarket.product.domain.entity.Category;
import com.freshmarket.product.domain.repository.CategoryRepository;
import com.freshmarket.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
/*
 * @DataJpaTest 는 애플리케이션 전체를 스캔하지 않아 JpaAuditingConfig 가 로드되지 않는다.
 * 그러면 @CreatedDate/@LastModifiedDate 가 채워지지 않아 created_at 이 null 로 insert 되어
 * DB의 NOT NULL 제약을 위반한다 (BE-3-03).
 */
@Import(JpaAuditingConfig.class)
// CategoryRepository가 실제 MySQL 스키마의 유니크 제약과 FK 제약을 그대로 지키는지 검증한다
class CategoryIntegrationTest extends IntegrationTestSupport {


    @Autowired
    private CategoryRepository categoryRepository;

    // 수산물/육류/채소/과일/유제품은 V2__seed_category.sql 이 최상위에 이미 심어둔 확정 카테고리다.
    // 여기서 그 이름을 재사용하면 유니크 제약과 충돌하므로, 실제 카테고리로 오해할 수 없도록
    // "테스트카테고리N" 처럼 테스트 전용임이 이름에서 드러나는 값을 쓴다.

    @Test
    void 카테고리를_저장하고_조회한다() {
        Category saved = categoryRepository.save(Category.register("테스트카테고리1"));

        assertThat(categoryRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void 최상위_카테고리끼리_이름이_같으면_중복으로_잡힌다() {
        categoryRepository.save(Category.register("테스트카테고리2"));

        boolean exists = categoryRepository.existsByParentIdIsNullAndName("테스트카테고리2");

        assertThat(exists).isTrue();
    }

    @Test
    void 최상위_카테고리끼리_이름이_다르면_중복이_아니다() {
        categoryRepository.save(Category.register("테스트카테고리2"));

        boolean exists = categoryRepository.existsByParentIdIsNullAndName("테스트카테고리3");

        assertThat(exists).isFalse();
    }

    @Test
    void 같은_상위_카테고리_아래에서_이름_중복을_확인한다() {
        Category parent = categoryRepository.save(Category.register("테스트카테고리1"));
        categoryRepository.save(Category.register("손질생선", parent.getId()));

        boolean exists = categoryRepository.existsByParentIdAndName(parent.getId(), "손질생선");

        assertThat(exists).isTrue();
    }

    @Test
    void 이름_유니크_제약을_DB가_실제로_강제한다() {
        categoryRepository.saveAndFlush(Category.register("테스트카테고리4"));

        assertThatThrownBy(() -> categoryRepository.saveAndFlush(Category.register("테스트카테고리4")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 존재하지_않는_상위_카테고리를_참조하면_DB가_거부한다() {
        Category invalid = Category.register("불량", 999999L);

        assertThatThrownBy(() -> categoryRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByIdForUpdate로_잠금을_걸고_카테고리를_조회한다() {
        Category saved = categoryRepository.save(Category.register("테스트카테고리1"));

        assertThat(categoryRepository.findByIdForUpdate(saved.getId())).isPresent();
    }

    @Test
    void 하위_카테고리가_있는_상위_카테고리를_삭제하면_DB가_거부한다() {
        Category parent = categoryRepository.save(Category.register("테스트카테고리1"));
        categoryRepository.saveAndFlush(Category.register("손질생선", parent.getId()));

        assertThatThrownBy(() -> {
            categoryRepository.delete(parent);
            categoryRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
