package com.shifa.oms.account;

import com.shifa.oms.account.dto.WishlistItemResponse;
import com.shifa.oms.common.ResourceNotFoundException;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WishlistShareService} with mocked repositories (no DB).
 * A real {@link WishlistService} is composed over the mocked repositories rather
 * than mocked itself (concrete classes cannot be mocked on this runtime), so the
 * product-summary projection is exercised end to end. Covers idempotent
 * create-or-get (same token until revoked), idempotent revoke, the public
 * read-only view returning the owner's summaries, and unknown-token 404.
 */
@ExtendWith(MockitoExtension.class)
class WishlistShareServiceTest {

    private static final Long ALICE = 1L;

    @Mock
    private WishlistShareRepository shareRepository;
    @Mock
    private WishlistItemRepository wishlistRepository;
    @Mock
    private ProductRepository productRepository;

    private WishlistShareService service;

    @BeforeEach
    void setUp() {
        // Real WishlistService over mocked repos: reused for the summary mapping.
        WishlistService wishlistService = new WishlistService(wishlistRepository, productRepository);
        service = new WishlistShareService(shareRepository, wishlistService);
    }

    private Product product(long id) {
        Product p = new Product("SKU-" + id, "Product " + id, "d",
                new BigDecimal("999.00"), new BigDecimal("499.00"), ProductVisibility.PUBLISHED);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    @Test
    void createGeneratesAnUnguessableUrlSafeToken() {
        when(shareRepository.findByCustomerId(ALICE)).thenReturn(Optional.empty());
        when(shareRepository.save(any(WishlistShare.class))).thenAnswer(inv -> inv.getArgument(0));

        String token = service.createOrGetShareToken(ALICE);

        // base64url alphabet, no padding, long enough to be unguessable.
        assertThat(token).matches("[A-Za-z0-9_-]+");
        assertThat(token.length()).isGreaterThanOrEqualTo(32);
        verify(shareRepository).save(any(WishlistShare.class));
    }

    @Test
    void createIsIdempotentReturningTheSameTokenUntilRevoked() {
        when(shareRepository.findByCustomerId(ALICE)).thenReturn(Optional.empty());
        when(shareRepository.save(any(WishlistShare.class))).thenAnswer(inv -> inv.getArgument(0));

        String first = service.createOrGetShareToken(ALICE);

        // A share now exists for the customer: a second call returns the same token.
        when(shareRepository.findByCustomerId(ALICE))
                .thenReturn(Optional.of(new WishlistShare(ALICE, first)));

        String second = service.createOrGetShareToken(ALICE);

        assertThat(second).isEqualTo(first);
        // Token generated/persisted exactly once, not on the repeat call.
        verify(shareRepository, times(1)).save(any(WishlistShare.class));
    }

    @Test
    void revokeDeletesTheShareWhenPresent() {
        WishlistShare share = new WishlistShare(ALICE, "tok");
        when(shareRepository.findByCustomerId(ALICE)).thenReturn(Optional.of(share));

        service.revokeShare(ALICE);

        verify(shareRepository).delete(share);
    }

    @Test
    void revokeIsIdempotentWhenNothingShared() {
        when(shareRepository.findByCustomerId(ALICE)).thenReturn(Optional.empty());

        service.revokeShare(ALICE);

        verify(shareRepository, never()).delete(any());
    }

    @Test
    void getSharedWishlistReturnsTheOwnersProductSummaries() {
        when(shareRepository.findByToken("tok")).thenReturn(Optional.of(new WishlistShare(ALICE, "tok")));
        when(wishlistRepository.findByCustomerIdOrderByIdDesc(ALICE))
                .thenReturn(List.of(new WishlistItem(ALICE, 7L)));
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L)));

        List<WishlistItemResponse> list = service.getSharedWishlist("tok");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).productId()).isEqualTo(7L);
        assertThat(list.get(0).salePrice()).isEqualByComparingTo("499.00");
    }

    @Test
    void getSharedWishlistWithUnknownTokenIsRejected() {
        when(shareRepository.findByToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSharedWishlist("nope"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }
}
