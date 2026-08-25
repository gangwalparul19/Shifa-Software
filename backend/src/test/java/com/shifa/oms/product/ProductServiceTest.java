package com.shifa.oms.product;

import com.shifa.oms.common.DuplicateResourceException;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.product.dto.ProductRequest;
import com.shifa.oms.product.dto.ProductResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link ProductService} covering the product edge
 * cases (design task 8.4): duplicate SKU rejection (6.2), empty catalog (1.6),
 * no-match search (1.5), and unavailable product detail (1.7). Repositories are
 * mocked so these run without a database.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private ProductService productService;

    private ProductRequest request(String sku, ProductVisibility visibility) {
        return new ProductRequest(sku, "Ashwagandha", "desc",
                new BigDecimal("199.00"), new BigDecimal("149.00"), null, null, visibility,
                null, null, null, null, null, null, null, null);
    }

    // --- Duplicate SKU (Req 6.2) -------------------------------------------

    @Test
    void createRejectsDuplicateSku() {
        when(productRepository.existsBySku("SKU-1")).thenReturn(true);

        assertThatThrownBy(() -> productService.create(request("SKU-1", ProductVisibility.PUBLISHED)))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("SKU-1");
    }

    @Test
    void createSucceedsForUniqueSku() {
        when(productRepository.existsBySku("SKU-NEW")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductResponse response = productService.create(request("SKU-NEW", ProductVisibility.PUBLISHED));

        assertThat(response.sku()).isEqualTo("SKU-NEW");
        assertThat(response.visibility()).isEqualTo(ProductVisibility.PUBLISHED);
    }

    @Test
    void updateRejectsChangingToAnExistingSku() {
        Product existing = new Product("OLD", "Name", "d",
                BigDecimal.ZERO, BigDecimal.ZERO, ProductVisibility.HIDDEN);
        when(productRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(productRepository.existsBySku("TAKEN")).thenReturn(true);

        assertThatThrownBy(() -> productService.update(7L, request("TAKEN", ProductVisibility.PUBLISHED)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    // --- Empty catalog (Req 1.6) -------------------------------------------

    @Test
    void catalogIsEmptyWhenNoPublishedProducts() {
        when(productRepository.findByVisibilityOrderByNameAsc(ProductVisibility.PUBLISHED))
                .thenReturn(List.of());

        assertThat(productService.catalog()).isEmpty();
    }

    // --- Admin list returns hidden + published (Req 6.3, 6.4) --------------

    @Test
    void adminListReturnsBothHiddenAndPublishedProducts() {
        Product published = new Product("PUB-1", "Published", "d",
                new BigDecimal("199.00"), new BigDecimal("149.00"), ProductVisibility.PUBLISHED);
        Product hidden = new Product("HID-1", "Hidden", "d",
                new BigDecimal("299.00"), new BigDecimal("249.00"), ProductVisibility.HIDDEN);
        when(productRepository.findAllByOrderByNameAsc())
                .thenReturn(List.of(hidden, published));

        List<ProductResponse> result = productService.adminList();

        assertThat(result)
                .extracting(ProductResponse::visibility)
                .containsExactlyInAnyOrder(ProductVisibility.HIDDEN, ProductVisibility.PUBLISHED);
        assertThat(result).extracting(ProductResponse::sku)
                .containsExactlyInAnyOrder("HID-1", "PUB-1");
    }

    @Test
    void adminDetailReturnsHiddenProduct() {
        Product hidden = new Product("HID-2", "Hidden", "d",
                BigDecimal.ZERO, BigDecimal.ZERO, ProductVisibility.HIDDEN);
        when(productRepository.findById(42L)).thenReturn(Optional.of(hidden));

        ProductResponse result = productService.adminDetail(42L);

        assertThat(result.sku()).isEqualTo("HID-2");
        assertThat(result.visibility()).isEqualTo(ProductVisibility.HIDDEN);
    }

    @Test
    void adminDetailForMissingProductIsNotFound() {
        when(productRepository.findById(123L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.adminDetail(123L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- No-match search (Req 1.5) -----------------------------------------

    @Test
    void searchReturnsEmptyWhenNothingMatches() {
        when(productRepository.searchPublished(ProductVisibility.PUBLISHED, "zzz"))
                .thenReturn(List.of());

        assertThat(productService.search("zzz")).isEmpty();
    }

    // --- Unavailable product detail (Req 1.7) ------------------------------

    @Test
    void detailForHiddenOrMissingProductIsNotFound() {
        when(productRepository.findByIdAndVisibility(99L, ProductVisibility.PUBLISHED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.detail(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not available");
    }
}
