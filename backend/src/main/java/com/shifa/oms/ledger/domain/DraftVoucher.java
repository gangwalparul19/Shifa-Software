package com.shifa.oms.ledger.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A voucher awaiting validation (General Ledger, Req 5.x).
 *
 * <p>A draft carries the three voucher-level fields required on posting — the {@link VoucherType
 * type}, the voucher {@code date}, and a {@code narration} (Req 5.7) — plus the ordered list of
 * {@link PostingLine posting lines} that make up the double entry (Reqs 5.1, 5.2, 5.4–5.6). A draft
 * is an inert value object: it performs no validation of its own and can hold any (even invalid)
 * combination of fields. All double-entry rules are enforced by
 * {@link DoubleEntry#validate(DraftVoucher)}, which returns a structured, exception-free result so
 * the rules can be property-tested without a database.
 *
 * <p>The {@code lines} list is defensively copied and made unmodifiable; a {@code null} list is
 * normalised to an empty list. Any of {@code type}, {@code date}, or {@code narration} may be
 * {@code null}/blank in a draft — those absences are reported by {@link DoubleEntry} (Req 5.7).
 *
 * @param type      the voucher type (required on posting; may be {@code null} in a draft)
 * @param date      the voucher date (required on posting; may be {@code null} in a draft)
 * @param narration the voucher narration (required on posting; may be {@code null}/blank in a draft)
 * @param lines     the ordered posting lines making up the voucher (never {@code null} after
 *                  construction; may be empty)
 */
public record DraftVoucher(VoucherType type, LocalDate date, String narration, List<PostingLine> lines) {

    public DraftVoucher {
        lines = (lines == null) ? List.of() : Collections.unmodifiableList(new ArrayList<>(lines));
    }
}
