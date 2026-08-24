package com.freshmarket.cart.domain.repository;

import com.freshmarket.cart.domain.entity.CartItem;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findAllByCartIdOrderByCreatedAtDesc(Long cartId);

    Optional<CartItem> findByCartIdAndProductOptionId(Long cartId, Long productOptionId);

    Optional<CartItem> findByIdAndCartId(Long cartItemId, Long cartId);

    long countByCartId(Long cartId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CartItem i where i.cartId = :cartId and i.id in :itemIds order by i.id")
    List<CartItem> findAllByCartIdAndIdInForUpdate(
            @Param("cartId") Long cartId,
            @Param("itemIds") List<Long> itemIds);
}
