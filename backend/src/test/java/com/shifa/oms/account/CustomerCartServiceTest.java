package com.shifa.oms.account;

import com.shifa.oms.account.CustomerCartService.CartLine;
import com.shifa.oms.account.dto.CartItemResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerCartService} with mocked repositories (no DB).
 * Covers atomic/idempotent replace (delete-then-insert), invalid-quantity skip,
 * unknown-product skip, and product-summary + quantity projection on list.
 *
 * <p>Only the repository interfaces are mocked (concrete classes cannot be mocked
 * on this runtime); the service is a real instance over those mocks.
 */
@ExtendWith(MockitoExtension.class)
class CustomerCartServiceTest {

    private static final Long ALICE = 1L;

    @Mock
    private CustomerCartItemRepository cartRepository;
    @Mock
    private ProductRepository productRepository;

    private CustomerCartService service;

    @BeforeEach
    void setUp() {
        service = new CustomerCartService(cartRepository, productRepository);
    }

    private Product product(long id) {
        Product p = new Product("SKU-" + id, "Product " + id, "d",
                new BigDecimal("999.00"), new BigDecimal("499.00"), ProductVisibility.PUBLISHED);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    /** Wires the repository so saved rows are reflected back by the ordered finder. */
    private List<CustomerCartItem> captureSavedRows() {
        List<CustomerCartItem> saved = new ArrayList<>();
        lenient().when(cartRepository.save(any(CustomerCartItem.class))).thenAnswer(inv -> {
            CustomerCartItem item = inv.getArgument(0);
            saved.add(item);
            return item;
        });
        lenient().when(cartRepository.findByCustomerIdOrderByIdAsc(ALICE)).thenReturn(saved);
        return saved;
    }

    @Test
    void replaceDeletesExistingThenInsertsValidLines() {
        captureSavedRows();
        when(productRepository.existsById(7L)).thenReturn(true);
        when(productRepository.existsById(8L)).thenReturn(true);
        lenient().when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L)));
        lenient().when(productRepository.findById(8L)).thenReturn(Optional.of(product(8L)));

        service.replace(ALICE, List.of(new CartLine(7L, 2), new CartLine(8L, 3)));

        // Atomic replace: existing rows are cleared first, then each valid line saved.
        verify(cartRepository).deleteByCustomerId(ALICE);
        verify(cartRepository, times(2)).save(any(CustomerCartItem.class));
    }

    @Test
    void replaceIsIdempotentAcrossRepeatedCalls() {
        when(productRepository.existsById(7L)).thenReturn(true);
        lenient().when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L)));

        // Independent saved-state per call so each replace starts from the delete.
        List<CustomerCartItem> firstSaved = captureSavedRows();
        List<CartItemResponse> first = service.replace(ALICE, List.of(new CartLine(7L, 5)));

        assertThat(first).hasSize(1);
        assertThat(first.get(0).productId()).isEqualTo(7L);
        assertThat(first.get(0).quantity()).isEqualTo(5);

        // Posting the same cart again yields the same saved result (idempotent).
        firstSaved.clear();
        List<CartItemResponse> second = service.replace(ALICE, List.of(new CartLine(7L, 5)));
        assertThat(second).hasSize(1);
        assertThat(second.get(0).productId()).isEqualTo(7L);
        assertThat(second.get(0).quantity()).isEqualTo(5);
    }

    @Test
    void replaceSkipsInvalidQuantities() {
        captureSavedRows();
        // Only the in-range line (7L) should be saved; qty 0 and qty 1000 are skipped.
        lenient().when(productRepository.existsById(any())).thenReturn(true);
        lenient().when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L)));

        service.replace(ALICE, List.of(
                new CartLine(5L, 0),
                new CartLine(7L, 4),
                new CartLine(9L, 1000)));

        verify(cartRepository).deleteByCustomerId(ALICE);
        verify(cartRepository, times(1)).save(any(CustomerCartItem.class));
    }

    @Test
    void replaceSkipsUnknownProducts() {
        captureSavedRows();
        when(productRepository.existsById(7L)).thenReturn(true);
        when(productRepository.existsById(99L)).thenReturn(false);
        lenient().when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L)));

        List<CartItemResponse> result = service.replace(ALICE,
                List.of(new CartLine(99L, 2), new CartLine(7L, 1)));

        // The unknown product (99L) is skipped; only the existing product is saved.
        verify(cartRepository, times(1)).save(any(CustomerCartItem.class));
        assertThat(result).extracting(CartItemResponse::productId).containsExactly(7L);
    }

    @Test
    void replaceCollapsesDuplicateProductIdsLastWins() {
        captureSavedRows();
        when(productRepository.existsById(7L)).thenReturn(true);
        lenient().when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L)));

        List<CartItemResponse> result = service.replace(ALICE,
                List.of(new CartLine(7L, 2), new CartLine(7L, 9)));

        // A single row is saved for the duplicated product, with the last quantity.
        verify(cartRepository, times(1)).save(any(CustomerCartItem.class));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).quantity()).isEqualTo(9);
    }

    @Test
    void emptyReplaceClearsTheCart() {
        captureSavedRows();

        List<CartItemResponse> result = service.replace(ALICE, List.of());

        verify(cartRepository).deleteByCustomerId(ALICE);
        verify(cartRepository, never()).save(any());
        assertThat(result).isEmpty();
    }

    @Test
    void clearCartEmptiesTheCart() {
        service.clearCart(ALICE);

        // Clearing simply deletes the customer's rows; nothing is re-inserted.
        verify(cartRepository).deleteByCustomerId(ALICE);
        verify(cartRepository, never()).save(any());
    }

    @Test
    void listProjectsProductSummariesWithQuantity() {
        when(cartRepository.findByCustomerIdOrderByIdAsc(ALICE))
                .thenReturn(List.of(new CustomerCartItem(ALICE, 7L, 3)));
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L)));

        List<CartItemResponse> list = service.list(ALICE);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).productId()).isEqualTo(7L);
        assertThat(list.get(0).sku()).isEqualTo("SKU-7");
        assertThat(list.get(0).salePrice()).isEqualByComparingTo("499.00");
        assertThat(list.get(0).mrp()).isEqualByComparingTo("999.00");
        assertThat(list.get(0).quantity()).isEqualTo(3);
    }

    @Test
    void listSkipsProductsThatNoLongerExist() {
        when(cartRepository.findByCustomerIdOrderByIdAsc(ALICE)).thenReturn(List.of(
                new CustomerCartItem(ALICE, 7L, 1),
                new CustomerCartItem(ALICE, 42L, 2)));
        when(productRepository.findById(7L)).thenReturn(Optional.of(product(7L)));
        when(productRepository.findById(42L)).thenReturn(Optional.empty());

        List<CartItemResponse> list = service.list(ALICE);

        assertThat(list).extracting(CartItemResponse::productId).containsExactly(7L);
    }
}
