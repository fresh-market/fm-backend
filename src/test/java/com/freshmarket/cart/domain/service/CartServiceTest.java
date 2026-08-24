package com.freshmarket.cart.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.cart.domain.dto.CartItemCreateRequest;
import com.freshmarket.cart.domain.dto.CartItemUpdateRequest;
import com.freshmarket.cart.domain.dto.CartResponse;
import com.freshmarket.cart.CartCheckoutInfo;
import com.freshmarket.cart.CartCheckoutItem;
import com.freshmarket.cart.domain.entity.Cart;
import com.freshmarket.cart.domain.entity.CartItem;
import com.freshmarket.cart.domain.exception.CartErrorCode;
import com.freshmarket.cart.domain.exception.CartException;
import com.freshmarket.cart.domain.repository.CartItemRepository;
import com.freshmarket.cart.domain.repository.CartRepository;
import com.freshmarket.member.MemberRegisteredEvent;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    private static final ProductOptionInfo PURCHASABLE_OPTION =
            new ProductOptionInfo(11L, "감귤", "1kg", 12900, true);

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private ProductApi productApi;

    private CartService sut;

    @BeforeEach
    void setUp() {
        sut = new CartService(cartRepository, cartItemRepository, productApi);
    }

    @Test
    void 신규_회원_이벤트를_받으면_빈_카트를_생성한다() {
        sut.createCartForNewMember(new MemberRegisteredEvent(1L));

        verify(cartRepository).save(any(Cart.class));
    }

    @Test
    void 내_카트와_항목_수량_합계를_조회한다() {
        Cart cart = cart(1L, 10L);
        CartItem first = item(10L, 11L, 2, 100L);
        CartItem second = item(10L, 12L, 3, 101L);
        when(cartRepository.findByMemberId(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findAllByCartIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(first, second));
        when(productApi.findOptionInfos(List.of(11L, 12L))).thenReturn(List.of(
                PURCHASABLE_OPTION,
                new ProductOptionInfo(12L, "사과", "2kg", 15000, true)));

        CartResponse result = sut.getCart(1L);

        assertThat(result.cartId()).isEqualTo(10L);
        assertThat(result.totalQty()).isEqualTo(5);
        assertThat(result.items()).extracting("productName").containsExactly("감귤", "사과");
        verify(productApi).findOptionInfos(List.of(11L, 12L));
        verify(productApi, never()).findOptionInfo(anyLong());
    }

    @Test
    void 같은_옵션을_다시_담으면_기존_수량에_더한다() {
        Cart cart = cart(1L, 10L);
        CartItem existing = item(10L, 11L, 2, 100L);
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart));
        when(productApi.findOptionInfo(11L)).thenReturn(Optional.of(PURCHASABLE_OPTION));
        when(cartItemRepository.findByCartIdAndProductOptionId(10L, 11L)).thenReturn(Optional.of(existing));

        var result = sut.addItem(1L, new CartItemCreateRequest(11L, 3));

        assertThat(existing.getQty()).isEqualTo(5);
        assertThat(result.qty()).isEqualTo(5);
    }

    @Test
    void 새_옵션을_카트에_담는다() {
        Cart cart = cart(1L, 10L);
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart));
        when(productApi.findOptionInfo(11L)).thenReturn(Optional.of(PURCHASABLE_OPTION));
        when(cartItemRepository.findByCartIdAndProductOptionId(10L, 11L)).thenReturn(Optional.empty());
        when(cartItemRepository.countByCartId(10L)).thenReturn(0L);
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = sut.addItem(1L, new CartItemCreateRequest(11L, 2));

        assertThat(result.productOptionId()).isEqualTo(11L);
        assertThat(result.qty()).isEqualTo(2);
    }

    @Test
    void 새_옵션은_장바구니에_99개까지만_담을_수_있다() {
        Cart cart = cart(1L, 10L);
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart));
        when(productApi.findOptionInfo(11L)).thenReturn(Optional.of(PURCHASABLE_OPTION));
        when(cartItemRepository.findByCartIdAndProductOptionId(10L, 11L)).thenReturn(Optional.empty());
        when(cartItemRepository.countByCartId(10L)).thenReturn(99L);

        assertThatThrownBy(() -> sut.addItem(1L, new CartItemCreateRequest(11L, 1)))
                .isInstanceOf(CartException.class)
                .extracting(e -> ((CartException) e).getErrorCode())
                .isEqualTo(CartErrorCode.CART_ITEM_LIMIT_EXCEEDED);

        verify(cartItemRepository, never()).save(any(CartItem.class));
    }

    @Test
    void 구매할_수_없는_옵션은_담을_수_없다() {
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart(1L, 10L)));
        when(productApi.findOptionInfo(11L)).thenReturn(Optional.of(
                new ProductOptionInfo(11L, "감귤", "1kg", 12900, false)));

        assertThatThrownBy(() -> sut.addItem(1L, new CartItemCreateRequest(11L, 1)))
                .isInstanceOf(CartException.class);
    }

    @Test
    void 없는_상품_옵션은_담을_수_없다() {
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart(1L, 10L)));
        when(productApi.findOptionInfo(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.addItem(1L, new CartItemCreateRequest(999L, 1)))
                .isInstanceOf(CartException.class);
    }

    @Test
    void 내_카트의_항목만_수량을_바꾼다() {
        Cart cart = cart(1L, 10L);
        CartItem existing = item(10L, 11L, 2, 100L);
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByIdAndCartId(100L, 10L)).thenReturn(Optional.of(existing));
        when(productApi.findOptionInfo(11L)).thenReturn(Optional.of(PURCHASABLE_OPTION));

        var result = sut.updateItem(1L, 100L, new CartItemUpdateRequest(4));

        assertThat(existing.getQty()).isEqualTo(4);
        assertThat(result.qty()).isEqualTo(4);
    }

    @Test
    void 내_카트의_항목만_삭제한다() {
        Cart cart = cart(1L, 10L);
        CartItem existing = item(10L, 11L, 2, 100L);
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByIdAndCartId(100L, 10L)).thenReturn(Optional.of(existing));

        sut.deleteItem(1L, 100L);

        verify(cartItemRepository).delete(existing);
    }

    @Test
    void 이미_삭제된_항목을_다시_삭제해도_성공한다() {
        Cart cart = cart(1L, 10L);
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByIdAndCartId(100L, 10L)).thenReturn(Optional.empty());

        sut.deleteItem(1L, 100L);

        verify(cartItemRepository, never()).delete(any(CartItem.class));
    }

    @Test
    void 주문할_항목을_잠금_조회하고_현재_상품정보를_반환한다() {
        Cart cart = cart(1L, 10L);
        CartItem first = item(10L, 11L, 2, 100L);
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findAllByCartIdAndIdInForUpdate(10L, List.of(100L)))
                .thenReturn(List.of(first));
        when(productApi.findOptionInfos(List.of(11L))).thenReturn(List.of(PURCHASABLE_OPTION));

        CartCheckoutInfo result = sut.getCheckoutItems(1L, List.of(100L));

        assertThat(result.cartId()).isEqualTo(10L);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.cartItemId()).isEqualTo(100L);
            assertThat(item.unitPrice()).isEqualTo(12900);
            assertThat(item.qty()).isEqualTo(2);
        });
    }

    @Test
    void 주문_항목_중_내_카트에_없는_ID가_있으면_실패한다() {
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart(1L, 10L)));
        when(cartItemRepository.findAllByCartIdAndIdInForUpdate(10L, List.of(100L, 999L)))
                .thenReturn(List.of(item(10L, 11L, 1, 100L)));

        assertThatThrownBy(() -> sut.getCheckoutItems(1L, List.of(100L, 999L)))
                .isInstanceOf(CartException.class)
                .extracting(e -> ((CartException) e).getErrorCode())
                .isEqualTo(CartErrorCode.CART_ITEM_NOT_FOUND);
    }

    @Test
    void 주문_시점에_구매할_수_없는_상품이면_실패한다() {
        CartItem item = item(10L, 11L, 1, 100L);
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart(1L, 10L)));
        when(cartItemRepository.findAllByCartIdAndIdInForUpdate(10L, List.of(100L)))
                .thenReturn(List.of(item));
        when(productApi.findOptionInfos(List.of(11L))).thenReturn(List.of(
                new ProductOptionInfo(11L, "감귤", "1kg", 12900, false)));

        assertThatThrownBy(() -> sut.getCheckoutItems(1L, List.of(100L)))
                .isInstanceOf(CartException.class)
                .extracting(e -> ((CartException) e).getErrorCode())
                .isEqualTo(CartErrorCode.PRODUCT_OPTION_NOT_PURCHASABLE);
    }

    @Test
    void 결제된_주문의_선택_항목만_삭제한다() {
        CartItem item = item(10L, 11L, 1, 100L);
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart(1L, 10L)));
        when(cartItemRepository.findAllByCartIdAndIdInForUpdate(10L, List.of(100L)))
                .thenReturn(List.of(item));

        CartCheckoutItem checkedOut = new CartCheckoutItem(100L, 11L, "감귤", "1kg", 12900, 1);
        sut.removeCheckedOutItems(1L, List.of(checkedOut));

        verify(cartItemRepository).deleteAll(List.of(item));
    }

    @Test
    void 주문_뒤에_추가된_수량은_삭제하지_않고_남긴다() {
        CartItem item = item(10L, 11L, 5, 100L);
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart(1L, 10L)));
        when(cartItemRepository.findAllByCartIdAndIdInForUpdate(10L, List.of(100L)))
                .thenReturn(List.of(item));
        CartCheckoutItem checkedOut = new CartCheckoutItem(100L, 11L, "감귤", "1kg", 12900, 2);

        sut.removeCheckedOutItems(1L, List.of(checkedOut));

        assertThat(item.getQty()).isEqualTo(3);
        verify(cartItemRepository).deleteAll(List.of());
    }

    @Test
    void 주문_항목_삭제를_재시도해도_성공한다() {
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart(1L, 10L)));
        when(cartItemRepository.findAllByCartIdAndIdInForUpdate(10L, List.of(100L)))
                .thenReturn(List.of());
        CartCheckoutItem checkedOut = new CartCheckoutItem(100L, 11L, "감귤", "1kg", 12900, 1);

        sut.removeCheckedOutItems(1L, List.of(checkedOut));

        verify(cartItemRepository).deleteAll(List.of());
    }

    @Test
    void 주문할_항목을_선택하지_않으면_실패한다() {
        assertThatThrownBy(() -> sut.getCheckoutItems(1L, List.of()))
                .isInstanceOf(CartException.class)
                .extracting(e -> ((CartException) e).getErrorCode())
                .isEqualTo(CartErrorCode.CART_ITEMS_REQUIRED);

        verify(cartRepository, never()).findByMemberIdForUpdate(anyLong());
    }

    @Test
    void 카트가_없으면_조회할_수_없다() {
        when(cartRepository.findByMemberId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.getCart(1L))
                .isInstanceOf(CartException.class);
    }

    @Test
    void 카트가_없으면_상품을_담을_수_없다() {
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.addItem(1L, new CartItemCreateRequest(11L, 1)))
                .isInstanceOf(CartException.class);
    }

    @Test
    void 카트에_없는_항목은_수정할_수_없다() {
        when(cartRepository.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(cart(1L, 10L)));
        when(cartItemRepository.findByIdAndCartId(100L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.updateItem(1L, 100L, new CartItemUpdateRequest(1)))
                .isInstanceOf(CartException.class);
    }

    private static Cart cart(Long memberId, Long id) {
        Cart cart = Cart.create(memberId);
        setId(cart, id);
        return cart;
    }

    private static CartItem item(Long cartId, Long optionId, int qty, Long id) {
        CartItem item = CartItem.add(cartId, optionId, qty);
        setId(item, id);
        return item;
    }

    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
