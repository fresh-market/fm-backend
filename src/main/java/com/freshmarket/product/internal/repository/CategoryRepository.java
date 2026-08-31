package com.freshmarket.product.internal.repository;

import com.freshmarket.product.internal.entity.Category;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Category 엔티티에 대한 조회/저장을 담당한다
public interface CategoryRepository extends JpaRepository<Category, Long> {

    // 같은 부모 아래에 같은 이름의 카테고리가 있는지 확인한다 (하위 카테고리 이름 중복 검사)
    boolean existsByParentIdAndName(Long parentId, String name);

    // 최상위 카테고리끼리 같은 이름이 있는지 확인한다 (parentId가 null인 것끼리 비교)
    boolean existsByParentIdIsNullAndName(String name);

    // 이 카테고리를 부모로 하는 하위 카테고리가 하나라도 있는지 확인한다 (삭제 시 사용)
    boolean existsByParentId(Long parentId);

    /*
     * 삭제 대상 행에 비관적 쓰기 락(SELECT ... FOR UPDATE)을 건다.
     * 이 트랜잭션이 끝날 때까지, 이 카테고리를 부모로 참조하는 INSERT(하위 카테고리, 상품 등)는
     * FK 검사 과정에서 이 행에 공유 락을 잡으려다 대기하게 되어 삭제와 원자적으로 처리된다.
     * 같은 카테고리를 동시에 여러 요청이 삭제하려는 경우도, 뒤에 락을 잡는 쪽은 대기했다가
     * 앞선 삭제가 끝난 뒤 빈 결과(Optional.empty)를 받아 CATEGORY_NOT_FOUND로 정리된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Category c where c.id = :id")
    Optional<Category> findByIdForUpdate(@Param("id") Long id);
}
