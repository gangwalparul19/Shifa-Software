package com.shifa.oms.product;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.product.dto.ImportResultResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Bulk product CSV import endpoint ({@code POST /api/admin/products/import},
 * "operations depth" Feature 2).
 *
 * <p>Restricted to the {@code ADMIN} role via method security. Consumes a
 * multipart {@code file} (a CSV) and validates + reports every row. When
 * {@code dryRun} is true (the default) nothing is persisted (a preview); when
 * false the valid rows are applied in a single transaction and invalid rows are
 * skipped/reported.
 */
@RestController
@RequestMapping("/api/admin/products")
@PreAuthorize("hasRole('ADMIN')")
public class ProductImportController {

    private final ProductImportService productImportService;

    public ProductImportController(ProductImportService productImportService) {
        this.productImportService = productImportService;
    }

    /**
     * Imports (or previews) a product CSV.
     *
     * @param file   the multipart CSV file (required)
     * @param dryRun when true (default), validate + report only; when false, apply valid rows
     * @return the aggregate import result with per-row outcomes
     */
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResultResponse importProducts(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("A CSV file is required.");
        }
        try {
            return productImportService.importCsv(file.getBytes(), dryRun);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the uploaded CSV file.", e);
        }
    }
}
