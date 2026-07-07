package com.shifa.oms.account;

import com.shifa.oms.account.dto.WishlistItemResponse;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.product.ProductVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WishlistService} with mocked repositories (no DB).
 * Covers idempotent add (no duplicate when already present), idempotent remove
 * (scoped delete regardless of presence), user-scoping, and product-summary
 * projection.
 */
@ExtendWith(MockitoExtension.class)
class WishlistServiceTest {

    private static final Long ALICE = 1L;

    @Mock
    private WishlistItemRepository wishlistRepository;
    @Mock
    private ProductRepository productRepository;

    private WishlistService service;

    @BeforeEach
    void setUp() {
        service = new WishlistService(wishlistRepository, productRepository);
    }

    private Product product(long id) {
        Product p = new Product("SKU-" + id, "Product " + id, "d",
                new BigDecimal("999.00"), new BigDecimal("499.00"), ProductVisibility.PUBLISHED);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    @Test
    void addSavesWhenNotAlreadyPresent() {
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L)));
        when(wishlistRepository.existsByCustomerIdAndProductId(ALICE, 7L)).thenReturn(false);

        service.add(ALICE, 7L);

        verify(wishlistRepository).save(any(WishlistItem.class));
    }

    @Test
    void addIsIdempotentWhenAlreadyPresent() {
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L)));
        when(wishlistRepository.existsByCustomerIdAndProductId(ALICE, 7L)).thenReturn(true);

        service.add(ALICE, 7L);

        verify(wishlistRepository, never()).save(any());
    }

    @Test
    void addUnknownProductIsRejected() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add(ALICE, 99L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not exist");
        verify(wishlistRepository, never()).save(any());
    }

    @Test
    void removeDelegatesScopedDelete() {
        service.remove(ALICE, 7L);

        // Idempotent + scoped: delete is keyed by both customer id and product id.
        verify(wishlistRepository).deleteByCustomerIdAndProductId(ALICE, 7L);
    }

    @Test
    void listReturnsProductSummariesForTheCustomerOnly() {
        WishlistItem item = new WishlistItem(ALICE, 7L);
        when(wishlistRepository.findByCustomerIdOrderByIdDesc(ALICE)).thenReturn(List.of(item));
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L)));

        List<WishlistItemResponse> list = service.list(ALICE);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).productId()).isEqualTo(7L);
        assertThat(list.get(0).salePrice()).isEqualByComparingTo("499.00");
    }
}
