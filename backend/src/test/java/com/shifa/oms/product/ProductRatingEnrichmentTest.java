package com.shifa.oms.product;

import com.shifa.oms.product.ProductRatingLookup.ProductRating;
import com.shifa.oms.product.dto.ProductResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ProductService} surfaces the APPROVED-review rating
 * aggregate (average + count) on catalog and detail responses via the injected
 * {@link ProductRatingLookup} (Phase C). The aggregate reflects approved reviews
 * only — that guarantee lives in the review module; here we assert the values
 * flow through onto {@link ProductResponse}.
 */
@ExtendWith(MockitoExtension.class)
class ProductRatingEnrichmentTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ProductRatingLookup ratingLookup;

    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(productRepository, categoryRepository, ratingLookup);
    }

    private Product publishedProduct(long id) {
        Product p = new Product("SKU-" + id, "Product " + id, "d",
                new BigDecimal("999.00"), new BigDecimal("499.00"), ProductVisibility.PUBLISHED);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    @Test
    void detailCarriesAverageAndCount() {
        when(productRepository.findByIdAndVisibility(7L, ProductVisibility.PUBLISHED))
                .thenReturn(Optional.of(publishedProduct(7L)));
        when(ratingLookup.ratingFor(7L)).thenReturn(new ProductRating(4.5, 10L));

        ProductResponse response = service.detail(7L);

        assertThat(response.averageRating()).isEqualTo(4.5);
        assertThat(response.reviewCount()).isEqualTo(10L);
    }

    @Test
    void detailWithNoReviewsHasNullAverageAndZeroCount() {
        when(productRepository.findByIdAndVisibility(7L, ProductVisibility.PUBLISHED))
                .thenReturn(Optional.of(publishedProduct(7L)));
        when(ratingLookup.ratingFor(7L)).thenReturn(ProductRating.NONE);

        ProductResponse response = service.detail(7L);

        assertThat(response.averageRating()).isNull();
        assertThat(response.reviewCount()).isZero();
    }

    @Test
    void catalogEnrichesEachProductFromBatchLookup() {
        when(productRepository.findByVisibilityOrderByNameAsc(ProductVisibility.PUBLISHED))
                .thenReturn(List.of(publishedProduct(1L), publishedProduct(2L)));
        when(ratingLookup.ratingsFor(any()))
                .thenReturn(Map.of(1L, new ProductRating(3.0, 2L)));

        List<ProductResponse> catalog = service.catalog();

        ProductResponse first = catalog.stream().filter(r -> r.id() == 1L).findFirst().orElseThrow();
        ProductResponse second = catalog.stream().filter(r -> r.id() == 2L).findFirst().orElseThrow();
        assertThat(first.averageRating()).isEqualTo(3.0);
        assertThat(first.reviewCount()).isEqualTo(2L);
        // A product absent from the batch result has no rating (null/0).
        assertThat(second.averageRating()).isNull();
        assertThat(second.reviewCount()).isZero();
    }
}
