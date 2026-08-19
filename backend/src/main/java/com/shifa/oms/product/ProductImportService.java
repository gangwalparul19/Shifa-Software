package com.shifa.oms.product;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.product.dto.ImportResultResponse;
import com.shifa.oms.product.dto.ImportRowResult;
import com.shifa.oms.product.dto.ProductRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bulk product CSV import application service ("operations depth" Feature 2).
 *
 * <p>Parses and validates every row of an uploaded CSV, producing a per-row
 * outcome ({@link ImportRowResult}). The header row names the columns
 * (case-insensitive): {@code sku,name,mrp,salePrice,minimumRate,hsnCode,wtMl,
 * gstRate,stockQuantity,trackInventory,category,visibility,description}. {@code sku},
 * {@code name}, {@code mrp} and {@code salePrice} are required; the rest are
 * optional. Existing products are matched by SKU and updated; otherwise a new
 * product is created — reusing {@link ProductService#create}/{@link
 * ProductService#update} so pricing/validation rules are never duplicated.
 *
 * <p>When {@code dryRun} is true (the default) the file is validated and reported
 * WITHOUT persisting (a preview). When false the valid rows are applied within
 * this method's transaction and the invalid rows are skipped/reported; a
 * best-effort {@code PRODUCTS_IMPORTED} audit event is recorded on commit.
 */
@Service
public class ProductImportService {

    /** The canonical (lower-cased) header names understood by the importer. */
    private static final String COL_SKU = "sku";
    private static final String COL_NAME = "name";
    private static final String COL_MRP = "mrp";
    private static final String COL_SALE_PRICE = "saleprice";
    private static final String COL_MINIMUM = "minimumrate";
    private static final String COL_HSN = "hsncode";
    private static final String COL_GST = "gstrate";
    private static final String COL_WT = "wtml";
    private static final String COL_STOCK = "stockquantity";
    private static final String COL_TRACK = "trackinventory";
    private static final String COL_CATEGORY = "category";
    private static final String COL_VISIBILITY = "visibility";
    private static final String COL_DESCRIPTION = "description";

    private final ProductService productService;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final AuditService auditService;

    public ProductImportService(ProductService productService,
                                ProductRepository productRepository,
                                CategoryRepository categoryRepository,
                                AuditService auditService) {
        this.productService = productService;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.auditService = auditService;
    }

    /**
     * Imports (or previews) a product CSV.
     *
     * @param csvBytes the raw uploaded CSV bytes (UTF-8)
     * @param dryRun   when true, validate + report only (nothing persisted)
     * @return the aggregate result with per-row outcomes
     */
    @Transactional
    public ImportResultResponse importCsv(byte[] csvBytes, boolean dryRun) {
        String content = new String(csvBytes == null ? new byte[0] : csvBytes, StandardCharsets.UTF_8);
        // Strip a leading UTF-8 BOM if present so the first header is clean.
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        List<List<String>> rows = CsvSupport.parse(content);
        if (rows.isEmpty()) {
            throw new ValidationException("The uploaded CSV is empty.");
        }

        Map<String, Integer> columns = headerIndex(rows.get(0));
        if (!columns.containsKey(COL_SKU) || !columns.containsKey(COL_NAME)
                || !columns.containsKey(COL_MRP) || !columns.containsKey(COL_SALE_PRICE)) {
            throw new ValidationException(
                    "CSV header must include the required columns: sku, name, mrp, salePrice.");
        }

        List<ImportRowResult> results = new ArrayList<>();
        Set<String> seenSkus = new HashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            int rowNumber = i; // 1-based data row (header is row 0).
            results.add(processRow(rowNumber, rows.get(i), columns, seenSkus, dryRun));
        }

        ImportResultResponse response = ImportResultResponse.of(dryRun, results);
        if (!dryRun) {
            auditService.record(AuditActions.PRODUCTS_IMPORTED, AuditActions.ENTITY_PRODUCT, null,
                    "Product import: " + response.created() + " created, " + response.updated()
                            + " updated, " + response.skipped() + " skipped, "
                            + response.errors() + " errors (" + response.totalRows() + " rows).");
        }
        return response;
    }

    private ImportRowResult processRow(int rowNumber, List<String> row, Map<String, Integer> columns,
                                       Set<String> seenSkus, boolean dryRun) {
        String sku = trimToNull(value(row, columns, COL_SKU));
        try {
            String name = trimToNull(value(row, columns, COL_NAME));
            if (sku == null) {
                return ImportRowResult.error(rowNumber, null, "sku is required.");
            }
            if (name == null) {
                return ImportRowResult.error(rowNumber, sku, "name is required.");
            }
            String skuKey = sku.toLowerCase(Locale.ROOT);
            if (!seenSkus.add(skuKey)) {
                return ImportRowResult.error(rowNumber, sku, "Duplicate sku within the file.");
            }

            BigDecimal mrp = parseRequiredDecimal(value(row, columns, COL_MRP), "mrp");
            BigDecimal salePrice = parseRequiredDecimal(value(row, columns, COL_SALE_PRICE), "salePrice");
            BigDecimal gstRate = parseOptionalDecimal(value(row, columns, COL_GST), "gstRate");
            BigDecimal minimumRate = parseOptionalDecimal(value(row, columns, COL_MINIMUM), "minimumRate");
            Integer stockQuantity = parseOptionalInt(value(row, columns, COL_STOCK), "stockQuantity");
            Boolean trackInventory = parseOptionalBoolean(value(row, columns, COL_TRACK), "trackInventory");
            ProductVisibility visibility = parseVisibility(value(row, columns, COL_VISIBILITY));
            String hsnCode = trimToNull(value(row, columns, COL_HSN));
            String wtMl = trimToNull(value(row, columns, COL_WT));
            String description = trimToNull(value(row, columns, COL_DESCRIPTION));

            // Resolve category (by slug or name); unknown/blank → no category (not an error).
            String categoryRaw = trimToNull(value(row, columns, COL_CATEGORY));
            Long categoryId = null;
            String categoryNote = "";
            if (categoryRaw != null) {
                categoryId = resolveCategoryId(categoryRaw);
                if (categoryId == null) {
                    categoryNote = " (unknown category '" + categoryRaw + "' ignored)";
                }
            }

            Optional<Product> existing = productRepository.findBySku(sku);
            boolean isUpdate = existing.isPresent();

            ProductRequest request = isUpdate
                    ? buildUpdateRequest(existing.get(), columns, row, name, description, mrp, salePrice,
                            hsnCode, gstRate, visibility, categoryId, categoryRaw, stockQuantity, trackInventory,
                            minimumRate, wtMl)
                    : buildCreateRequest(sku, name, description, mrp, salePrice, hsnCode, gstRate,
                            visibility, categoryId, stockQuantity, trackInventory, minimumRate, wtMl);

            if (isUpdate) {
                if (!dryRun) {
                    productService.update(existing.get().getId(), request);
                }
                return ImportRowResult.update(rowNumber, sku, "Updated" + categoryNote);
            } else {
                if (!dryRun) {
                    productService.create(request);
                }
                return ImportRowResult.create(rowNumber, sku, "Created" + categoryNote);
            }
        } catch (RowException e) {
            return ImportRowResult.error(rowNumber, sku, e.getMessage());
        }
    }

    /** Builds a full create request from CSV values, applying safe defaults. */
    private ProductRequest buildCreateRequest(String sku, String name, String description,
                                              BigDecimal mrp, BigDecimal salePrice, String hsnCode,
                                              BigDecimal gstRate, ProductVisibility visibility,
                                              Long categoryId, Integer stockQuantity,
                                              Boolean trackInventory,
                                              BigDecimal minimumRate, String wtMl) {
        return new ProductRequest(
                sku, name, description, mrp, salePrice, hsnCode, gstRate,
                visibility != null ? visibility : ProductVisibility.PUBLISHED,
                categoryId, stockQuantity, trackInventory, null, null,
                minimumRate, wtMl);
    }

    /**
     * Builds an update request that preserves the existing product's fields for
     * any column NOT present in the CSV header, overriding only the provided
     * columns (so a partial CSV does not wipe unrelated fields).
     */
    private ProductRequest buildUpdateRequest(Product existing, Map<String, Integer> columns,
                                              List<String> row, String name, String description,
                                              BigDecimal mrp, BigDecimal salePrice, String hsnCode,
                                              BigDecimal gstRate, ProductVisibility visibility,
                                              Long categoryId, String categoryRaw,
                                              Integer stockQuantity, Boolean trackInventory,
                                              BigDecimal minimumRate, String wtMl) {
        Long resolvedCategory = existing.getCategory() != null ? existing.getCategory().getId() : null;
        if (columns.containsKey(COL_CATEGORY) && categoryRaw != null && categoryId != null) {
            resolvedCategory = categoryId;
        }
        return new ProductRequest(
                existing.getSku(),
                name,
                columns.containsKey(COL_DESCRIPTION) ? description : existing.getDescription(),
                mrp,
                salePrice,
                columns.containsKey(COL_HSN) ? hsnCode : existing.getHsnCode(),
                columns.containsKey(COL_GST) ? gstRate : existing.getGstRate(),
                visibility != null ? visibility : existing.getVisibility(),
                resolvedCategory,
                columns.containsKey(COL_STOCK) ? stockQuantity : existing.getStockQuantity(),
                columns.containsKey(COL_TRACK) ? trackInventory : existing.isTrackInventory(),
                existing.getLowStockThreshold(),
                existing.isFeatured(),
                columns.containsKey(COL_MINIMUM) ? minimumRate : existing.getMinimumRate(),
                columns.containsKey(COL_WT) ? wtMl : existing.getWtMl());
    }

    // --- Parsing helpers ----------------------------------------------------

    private Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = header.get(i) == null ? "" : header.get(i).trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty() && !map.containsKey(key)) {
                map.put(key, i);
            }
        }
        return map;
    }

    private String value(List<String> row, Map<String, Integer> columns, String column) {
        Integer idx = columns.get(column);
        if (idx == null || idx >= row.size()) {
            return null;
        }
        return row.get(idx);
    }

    private BigDecimal parseRequiredDecimal(String raw, String field) {
        String cleaned = cleanNumber(raw);
        if (cleaned == null) {
            throw new RowException(field + " is required.");
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new RowException(field + " is not a valid number: '" + raw.trim() + "'.");
        }
    }

    private BigDecimal parseOptionalDecimal(String raw, String field) {
        String cleaned = cleanNumber(raw);
        if (cleaned == null) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new RowException(field + " is not a valid number: '" + raw.trim() + "'.");
        }
    }

    private Integer parseOptionalInt(String raw, String field) {
        String cleaned = cleanNumber(raw);
        if (cleaned == null) {
            return null;
        }
        try {
            // Accept "10" or "10.0" leniently.
            return new BigDecimal(cleaned).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new RowException(field + " is not a valid whole number: '" + raw.trim() + "'.");
        }
    }

    private Boolean parseOptionalBoolean(String raw, String field) {
        String v = trimToNull(raw);
        if (v == null) {
            return null;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "true", "yes", "y", "1", "t" -> Boolean.TRUE;
            case "false", "no", "n", "0", "f" -> Boolean.FALSE;
            default -> throw new RowException(field + " is not a valid boolean: '" + v + "'.");
        };
    }

    private ProductVisibility parseVisibility(String raw) {
        String v = trimToNull(raw);
        if (v == null) {
            return ProductVisibility.PUBLISHED;
        }
        try {
            return ProductVisibility.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RowException("visibility must be PUBLISHED or HIDDEN: '" + v + "'.");
        }
    }

    /** Strips currency symbols, thousands separators and whitespace; blank → null. */
    private String cleanNumber(String raw) {
        String v = trimToNull(raw);
        if (v == null) {
            return null;
        }
        String cleaned = v.replaceAll("[,₹$\\s]", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    private Long resolveCategoryId(String categoryRaw) {
        Optional<Category> bySlug = categoryRepository.findBySlug(categoryRaw);
        if (bySlug.isPresent()) {
            return bySlug.get().getId();
        }
        for (Category category : categoryRepository.findAll()) {
            if (category.getName() != null && category.getName().equalsIgnoreCase(categoryRaw)) {
                return category.getId();
            }
            if (category.getSlug() != null && category.getSlug().equalsIgnoreCase(categoryRaw)) {
                return category.getId();
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Signals a per-row validation failure (converted to a row ERROR result). */
    private static final class RowException extends RuntimeException {
        RowException(String message) {
            super(message);
        }
    }
}
