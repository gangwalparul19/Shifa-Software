package com.shifa.oms.ledger.domain;

import java.util.Optional;

/**
 * The standard set of voucher types supported by the General Ledger (Req 7.1, 7.2, 7.3).
 *
 * <p>Every posted voucher records its type as exactly one of these values. A voucher posted with a
 * type that is not one of the supported types is rejected — the strict {@link #fromName(String)}
 * parse below is the single point that accepts a supported name and rejects everything else. The
 * voucher service translates a rejected parse into a {@code common.ValidationException}; the pure
 * domain itself stays exception-free by returning an {@link Optional}.
 */
public enum VoucherType {
    JOURNAL,
    PAYMENT,
    RECEIPT,
    CONTRA,
    SALES,
    PURCHASE,
    DEBIT_NOTE,
    CREDIT_NOTE;

    /**
     * Strictly parse a voucher-type name into its {@link VoucherType}.
     *
     * <p>The input is matched <em>exactly</em> against the canonical enum names — no trimming or
     * other normalisation is applied. Because a canonical {@link #name()} contains no whitespace,
     * the {@code name()} round-trip still holds, while any {@code null}, blank, whitespace-only,
     * whitespace-padded (for example {@code " CONTRA"} or {@code "receipt "}), or otherwise
     * unsupported name yields an empty result rather than throwing. This keeps the parse pure and
     * property-testable and ensures unsupported names are rejected (Req 7.1, 7.2, 7.3).
     *
     * @param name the candidate voucher-type name
     * @return the matching {@link VoucherType}, or {@link Optional#empty()} when {@code name} is
     *         {@code null}, blank, whitespace-padded, or not an exact match for one of the
     *         supported types
     */
    public static Optional<VoucherType> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (VoucherType type : values()) {
            if (type.name().equals(name)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
