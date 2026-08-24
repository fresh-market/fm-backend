package com.freshmarket.cart.domain.service;

import com.freshmarket.cart.CartCheckoutInfo;
import com.freshmarket.cart.CartCheckoutItem;
import com.freshmarket.cart.domain.dto.CartItemCreateRequest;
import com.freshmarket.cart.domain.dto.CartItemResponse;
import com.freshmarket.cart.domain.dto.CartItemUpdateRequest;
import com.freshmarket.cart.domain.dto.CartResponse;
import com.freshmarket.cart.domain.entity.Cart;
import com.freshmarket.cart.domain.entity.CartItem;
import com.freshmarket.cart.domain.exception.CartErrorCode;
import com.freshmarket.cart.domain.exception.CartException;
import com.freshmarket.cart.domain.repository.CartItemRepository;
import com.freshmarket.cart.domain.repository.CartRepository;
import com.freshmarket.member.MemberRegisteredEvent;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartService {

    private static final int MAX_CART_ITEMS = 99;

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductApi productApi;

    // 회원 생성 트랜잭션 안에서 동기로 실행된다. 실패는 예외로 전파되어 회원 생성도 함께 롤백된다.
    @EventListener
    @Transactional
    public void createCartForNewMember(MemberRegisteredEvent event) {
        cartRepository.save(Cart.create(event.memberId()));
    }

    public CartResponse getCart(Long memberId) {
        Cart cart = findCart(memberId);
        List<CartItem> cartItems = cartItemRepository.findAllByCartIdOrderByCreatedAtDesc(cart.getId());
        Map<Long, ProductOptionInfo> optionsById = productApi.findOptionInfos(cartItems.stream()
                        .map(CartItem::getProductOptionId)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(ProductOptionInfo::productOptionId, Function.identity()));
        List<CartItemResponse> items = cartItems.stream()
                .map(item -> CartItemResponse.from(item, findOptionInfo(optionsById, item.getProductOptionId())))
                .toList();
        return CartResponse.from(cart, items);
    }

    // 주문 대상만 잠가 다시 조회하고, 현재 상품 정보로 주문 스냅샷 원본을 만든다.
    @Transactional
    public CartCheckoutInfo getCheckoutItems(Long memberId, List<Long> cartItemIds) {
        List<Long> itemIds = validateAndNormalizeItemIds(cartItemIds);
        Cart cart = findCartForUpdate(memberId);
        List<CartItem> items = findCheckoutItems(cart.getId(), itemIds);

        Map<Long, ProductOptionInfo> optionsById = productApi.findOptionInfos(items.stream()
                        .map(CartItem::getProductOptionId)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(ProductOptionInfo::productOptionId, Function.identity()));

        List<CartCheckoutItem> checkoutItems = items.stream()
                .map(item -> toCheckoutItem(item, optionsById))
                .toList();
        return new CartCheckoutInfo(cart.getId(), checkoutItems);
    }

    // 주문 뒤 추가된 수량은 남기고, 주문 당시 수량만 장바구니에서 제거한다.
    @Transactional
    public void removeCheckedOutItems(Long memberId, List<CartCheckoutItem> checkedOutItems) {
        if (checkedOutItems == null || checkedOutItems.isEmpty()
                || checkedOutItems.stream().anyMatch(item ->
                        item == null || item.cartItemId() == null || item.qty() <= 0)) {
            throw new CartException(CartErrorCode.CART_ITEMS_REQUIRED);
        }
        Map<Long, Integer> orderedQtyByItemId = checkedOutItems.stream()
                .collect(Collectors.toMap(
                        CartCheckoutItem::cartItemId,
                        CartCheckoutItem::qty,
                        Math::max));
        List<Long> itemIds = orderedQtyByItemId.keySet().stream().sorted().toList();
        Cart cart = findCartForUpdate(memberId);
        // 이미 정리된 항목은 재시도에서 빠질 수 있으므로 삭제는 멱등하게 처리한다.
        List<CartItem> items = cartItemRepository.findAllByCartIdAndIdInForUpdate(cart.getId(), itemIds);
        List<CartItem> itemsToDelete = items.stream()
                .filter(item -> removeOrderedQty(item, orderedQtyByItemId.get(item.getId())))
                .toList();
        cartItemRepository.deleteAll(itemsToDelete);
    }

    @Transactional
    public CartItemResponse addItem(Long memberId, CartItemCreateRequest request) {
        Cart cart = findCartForUpdate(memberId);
        ProductOptionInfo option = findOptionInfo(request.productOptionId());
        if (!option.purchasable()) {
            throw new CartException(CartErrorCode.PRODUCT_OPTION_NOT_PURCHASABLE);
        }

        CartItem item = cartItemRepository.findByCartIdAndProductOptionId(cart.getId(), request.productOptionId())
                .map(existing -> {
                    existing.increaseQty(request.qty());
                    return existing;
                })
                .orElseGet(() -> addNewItem(cart.getId(), request));
        return CartItemResponse.from(item, option);
    }

    @Transactional
    public CartItemResponse updateItem(Long memberId, Long cartItemId, CartItemUpdateRequest request) {
        Cart cart = findCartForUpdate(memberId);
        CartItem item = findItem(cart.getId(), cartItemId);
        item.changeQty(request.qty());
        return toResponse(item);
    }

    @Transactional
    public void deleteItem(Long memberId, Long cartItemId) {
        Cart cart = findCartForUpdate(memberId);
        // DELETE 재시도는 이미 지워진 항목이어도 성공(204)으로 끝난다.
        cartItemRepository.findByIdAndCartId(cartItemId, cart.getId())
                .ifPresent(cartItemRepository::delete);
    }

    private Cart findCart(Long memberId) {
        return cartRepository.findByMemberId(memberId)
                .orElseThrow(() -> new CartException(CartErrorCode.CART_NOT_FOUND));
    }

    private Cart findCartForUpdate(Long memberId) {
        return cartRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> new CartException(CartErrorCode.CART_NOT_FOUND));
    }

    private CartItem findItem(Long cartId, Long cartItemId) {
        return cartItemRepository.findByIdAndCartId(cartItemId, cartId)
                .orElseThrow(() -> new CartException(CartErrorCode.CART_ITEM_NOT_FOUND));
    }

    private CartItem addNewItem(Long cartId, CartItemCreateRequest request) {
        if (cartItemRepository.countByCartId(cartId) >= MAX_CART_ITEMS) {
            throw new CartException(CartErrorCode.CART_ITEM_LIMIT_EXCEEDED);
        }
        return cartItemRepository.save(CartItem.add(cartId, request.productOptionId(), request.qty()));
    }

    private List<Long> validateAndNormalizeItemIds(List<Long> cartItemIds) {
        if (cartItemIds == null || cartItemIds.isEmpty() || cartItemIds.stream().anyMatch(id -> id == null)) {
            throw new CartException(CartErrorCode.CART_ITEMS_REQUIRED);
        }
        return cartItemIds.stream().distinct().toList();
    }

    private List<CartItem> findCheckoutItems(Long cartId, List<Long> itemIds) {
        List<CartItem> items = cartItemRepository.findAllByCartIdAndIdInForUpdate(cartId, itemIds);
        if (items.size() != itemIds.size()) {
            throw new CartException(CartErrorCode.CART_ITEM_NOT_FOUND);
        }
        return items;
    }

    private CartCheckoutItem toCheckoutItem(
            CartItem item,
            Map<Long, ProductOptionInfo> optionsById
    ) {
        ProductOptionInfo option = findOptionInfo(optionsById, item.getProductOptionId());
        if (!option.purchasable()) {
            throw new CartException(CartErrorCode.PRODUCT_OPTION_NOT_PURCHASABLE);
        }
        return new CartCheckoutItem(item.getId(), item.getProductOptionId(), option.productName(),
                option.optionName(), option.price(), item.getQty());
    }

    // true면 남은 수량이 없어 삭제 대상이고, false면 주문 뒤 추가된 수량을 엔티티에 남긴다.
    private boolean removeOrderedQty(CartItem item, int orderedQty) {
        if (item.getQty() <= orderedQty) {
            return true;
        }
        item.changeQty(item.getQty() - orderedQty);
        return false;
    }

    private CartItemResponse toResponse(CartItem item) {
        return CartItemResponse.from(item, findOptionInfo(item.getProductOptionId()));
    }

    private ProductOptionInfo findOptionInfo(Long productOptionId) {
        return productApi.findOptionInfo(productOptionId)
                .orElseThrow(() -> new CartException(CartErrorCode.PRODUCT_OPTION_NOT_PURCHASABLE));
    }

    private ProductOptionInfo findOptionInfo(Map<Long, ProductOptionInfo> optionsById, Long productOptionId) {
        ProductOptionInfo optionInfo = optionsById.get(productOptionId);
        if (optionInfo == null) {
            throw new CartException(CartErrorCode.PRODUCT_OPTION_NOT_PURCHASABLE);
        }
        return optionInfo;
    }
}
