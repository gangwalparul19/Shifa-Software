package com.shifa.oms.product.dto;

/**
 * The per-row outcome of a bulk product CSV import ("operations depth"
 * Feature 2).
 *
 * @param rowNumber the 1-based data row number (excludes the header row)
 * @param sku       the SKU parsed from the row (may be null/blank on a malformed row)
 * @param action    what happened to the row
 * @param message   a short human-readable explanation (e.g. why it errored / was skipped)
 */
public record ImportRowResult(
        int rowNumber,
        String sku,
        Action action,
        String message
) {

    /** The outcome classification for a single import row. */
    public enum Action {
        /** A new product was (or would be) created. */
        CREATE,
        /** An existing product (matched by SKU) was (or would be) updated. */
        UPDATE,
        /** The row was valid but intentionally not applied (e.g. no-op). */
        SKIP,
        /** The row failed validation and was not applied. */
        ERROR
    }

    public static ImportRowResult create(int rowNumber, String sku, String message) {
        return new ImportRowResult(rowNumber, sku, Action.CREATE, message);
    }

    public static ImportRowResult update(int rowNumber, String sku, String message) {
        return new ImportRowResult(rowNumber, sku, Action.UPDATE, message);
    }

    public static ImportRowResult skip(int rowNumber, String sku, String message) {
        return new ImportRowResult(rowNumber, sku, Action.SKIP, message);
    }

    public static ImportRowResult error(int rowNumber, String sku, String message) {
        return new ImportRowResult(rowNumber, sku, Action.ERROR, message);
    }
}
