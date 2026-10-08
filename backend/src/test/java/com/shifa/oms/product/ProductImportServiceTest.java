package com.shifa.oms.product;

import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.product.dto.ImportResultResponse;
import com.shifa.oms.product.dto.ImportRowResult;
import com.shifa.oms.product.dto.ProductRequest;
import com.shifa.oms.product.dto.ProductResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link ProductImportService} ("operations depth"
 * Feature 2). The interface repositories are Mockito mocks; the concrete
 * {@link ProductService} and {@link AuditService} are hand-written fakes
 * (Mockito cannot mock the concrete service classes in this project's setup) so
 * we can assert exactly whether persistence was invoked.
 */
@ExtendWith(MockitoExtension.class)
class ProductImportServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    private RecordingProductService productService;
    private ProductImportService importService;

    @BeforeEach
    void setUp() {
        productService = new RecordingProductService();
        importService = new ProductImportService(productService, productRepository,
                categoryRepository, new NoopAuditService());
    }

    private byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // --- dry run: reports create/update/error without persisting ------------

    @Test
    void dryRunReportsWithoutPersisting() {
        lenient().when(productRepository.findBySku("NEW-1")).thenReturn(Optional.empty());
        lenient().when(productRepository.findBySku("EXIST-1"))
                .thenReturn(Optional.of(existingProduct("EXIST-1")));

        String content = """
                sku,name,mrp,salePrice,visibility
                NEW-1,New Product,199.00,149.00,PUBLISHED
                EXIST-1,Existing,299.00,249.00,PUBLISHED
                BAD-1,Bad Number,notanumber,10.00,PUBLISHED
                """;

        ImportResultResponse result = importService.importCsv(csv(content), true);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.totalRows()).isEqualTo(3);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.errors()).isEqualTo(1);
        // Nothing persisted in a dry run.
        assertThat(productService.creates).isZero();
        assertThat(productService.updates).isZero();
        assertThat(result.rows()).extracting(ImportRowResult::action)
                .containsExactly(ImportRowResult.Action.CREATE,
                        ImportRowResult.Action.UPDATE,
                        ImportRowResult.Action.ERROR);
    }

    // --- commit: creates + updates valid rows, skips/reports invalid ---------

    @Test
    void commitCreatesAndUpdatesValidRowsAndSkipsInvalid() {
        lenient().when(productRepository.findBySku("NEW-1")).thenReturn(Optional.empty());
        lenient().when(productRepository.findBySku("EXIST-1"))
                .thenReturn(Optional.of(existingProduct("EXIST-1")));

        String content = """
                sku,name,mrp,salePrice,visibility
                NEW-1,New Product,199.00,149.00,PUBLISHED
                EXIST-1,Existing,299.00,249.00,PUBLISHED
                BAD-1,Bad Number,notanumber,10.00,PUBLISHED
                """;

        ImportResultResponse result = importService.importCsv(csv(content), false);

        assertThat(result.dryRun()).isFalse();
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.errors()).isEqualTo(1);
        // Valid rows persisted; invalid row skipped.
        assertThat(productService.creates).isEqualTo(1);
        assertThat(productService.updates).isEqualTo(1);
    }

    // --- missing required column → whole import rejected --------------------

    @Test
    void missingRequiredColumnIsRejected() {
        String content = """
                sku,name,salePrice
                NEW-1,New Product,149.00
                """;

        assertThatThrownBy(() -> importService.importCsv(csv(content), true))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("required columns");
    }

    // --- missing required value → row ERROR ---------------------------------

    @Test
    void missingRequiredValueIsRowError() {
        String content = """
                sku,name,mrp,salePrice
                NEW-1,New Product,,149.00
                """;

        ImportResultResponse result = importService.importCsv(csv(content), true);

        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.rows().get(0).action()).isEqualTo(ImportRowResult.Action.ERROR);
        assertThat(result.rows().get(0).message()).contains("mrp");
    }

    // --- invalid number → row ERROR -----------------------------------------

    @Test
    void invalidNumberIsRowError() {
        lenient().when(productRepository.findBySku("NEW-1")).thenReturn(Optional.empty());
        String content = """
                sku,name,mrp,salePrice
                NEW-1,New Product,abc,149.00
                """;

        ImportResultResponse result = importService.importCsv(csv(content), true);

        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.rows().get(0).message()).contains("mrp");
    }

    // --- unknown category → blank category, not a hard error ----------------

    @Test
    void unknownCategoryIsNotAHardError() {
        lenient().when(productRepository.findBySku("NEW-1")).thenReturn(Optional.empty());
        when(categoryRepository.findBySlug("nonexistent")).thenReturn(Optional.empty());
        when(categoryRepository.findAll()).thenReturn(List.of());

        String content = """
                sku,name,mrp,salePrice,category
                NEW-1,New Product,199.00,149.00,nonexistent
                """;

        ImportResultResponse result = importService.importCsv(csv(content), true);

        assertThat(result.errors()).isZero();
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.rows().get(0).action()).isEqualTo(ImportRowResult.Action.CREATE);
        assertThat(result.rows().get(0).message()).contains("unknown category");
    }

    private Product existingProduct(String sku) {
        return new Product(sku, "Existing", "desc",
                new BigDecimal("299.00"), new BigDecimal("249.00"), ProductVisibility.PUBLISHED);
    }

    // --- Hand-written fakes for concrete service dependencies ---------------

    /** Records create/update invocations so the test can assert persistence. */
    private static final class RecordingProductService extends ProductService {
        int creates = 0;
        int updates = 0;

        RecordingProductService() {
            super(null, null, null, null);
        }

        @Override
        public ProductResponse create(ProductRequest request) {
            creates++;
            return null;
        }

        @Override
        public ProductResponse update(Long id, ProductRequest request) {
            updates++;
            return null;
        }
    }

    /** A no-op audit service so best-effort auditing never interferes with the test. */
    private static final class NoopAuditService extends AuditService {
        NoopAuditService() {
            super(null, null);
        }

        @Override
        public com.shifa.oms.audit.AuditEvent record(String action, String entityType,
                                                     String entityId, String summary) {
            return null;
        }
    }
}
