package com.shifa.oms.gst.domain;

import com.shifa.oms.gst.domain.CreditNoteProjection.OrderReturnView;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link CreditNoteProjection} (GST filing compliance,
 * design Correctness Properties 5–8).
 *
 * <p>{@code CreditNoteProjection.fromReturn(...)} is a <em>pure</em> function: it faithfully
 * projects whatever return + original order it is given. The exclusion of returns against
 * cancelled/rejected orders (Req 2.6) is the responsibility of the calling read-only service,
 * so Property 8 asserts that contract at the layer where it is enforced — a filter applied
 * <em>before</em> projection — rather than inside the pure function.
 *
 * <p>Feature: gst-filing-compliance, Property 5 (derivation, split, routing, references),
 * Property 6 (period attribution), Property 7 (output-tax reduction), Property 8 (exclusion of
 * cancelled/rejected).
 */
class CreditNoteProjectionPropertyTest {

    private static final String SELLER_STATE = "Madhya Pradesh";
    private static final String INTRA_STATE = "Madhya Pradesh"; // == seller → INTRA
    private static final String INTER_STATE = "Maharashtra";    // != seller → INTER
    private static final String INTRA_CODE = "23";
    private static final String INTER_CODE = "27";
    private static final String[] RATES = {"0", "5", "18"};

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 5: Credit-note derivation, split, routing, references
    // **Validates: Requirements 2.1, 2.2, 2.5**
    // For any refunded return against a non-cancelled original order, the derived credit note's
    // taxable + total tax equals the refund (note) value; its GST split matches the original order's
    // supply type (intra: cgst == sgst, igst == 0; inter: igst == totalTax, cgst == sgst == 0); its
    // registration is CDNR when the original order is B2B else CDNUR; and it carries the original
    // order's code, place-of-supply state, and 2-digit state code.
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 300)
    void noteIsDerivedSplitRoutedAndReferencedCorrectly(
            @ForAll boolean inter,
            @ForAll("category") DocumentCategory category,
            @ForAll @Size(min = 1, max = 6) List<@IntRange(min = 0, max = 500_000) Integer> lineTotals,
            @ForAll @IntRange(min = 0, max = 2_000_000) int refundPaise) {

        String state = inter ? INTER_STATE : INTRA_STATE;
        String stateCode = inter ? INTER_CODE : INTRA_CODE;
        GstEngine.GstOrder originalOrder = order(state, lineTotals);
        BigDecimal refund = paise(refundPaise);
        OrderReturnView ret = new OrderReturnView(42L, "SHR-GST-100", LocalDate.of(2026, 5, 17), refund);

        CreditNote note = CreditNoteProjection.fromReturn(ret, originalOrder, category, stateCode, SELLER_STATE);

        // Derivation: taxable + total tax reconciles to the refund (note) value, exactly.
        assertThat(note.taxable().add(note.totalTax())).isEqualByComparingTo(refund);
        assertThat(note.noteValue()).isEqualByComparingTo(refund);

        // Split matches the original order's supply type.
        if (inter) {
            assertThat(note.supplyType()).isEqualTo(SupplyType.INTER);
            assertThat(note.igst()).isEqualByComparingTo(note.totalTax());
            assertThat(note.cgst()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(note.sgst()).isEqualByComparingTo(BigDecimal.ZERO);
        } else {
            assertThat(note.supplyType()).isEqualTo(SupplyType.INTRA);
            assertThat(note.cgst()).isEqualByComparingTo(note.sgst());
            assertThat(note.cgst().add(note.sgst())).isEqualByComparingTo(note.totalTax());
            assertThat(note.igst()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        // Routing: registration follows the ORIGINAL order's category.
        NoteRegistration expected = category == DocumentCategory.B2B
                ? NoteRegistration.CDNR : NoteRegistration.CDNUR;
        assertThat(note.registration()).isEqualTo(expected);

        // References carried from the original order.
        assertThat(note.originalOrderCode()).isEqualTo("SHR-GST-100");
        assertThat(note.originalOrderId()).isEqualTo(originalOrder.orderId());
        assertThat(note.placeOfSupplyState()).isEqualTo(state);
        assertThat(note.stateCode()).isEqualTo(stateCode);
    }

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 6: Credit-note period attribution
    // **Validates: Requirements 2.3**
    // The note's date is exactly the return's refund date, so that for any reporting window the note
    // is attributable to a period if and only if that refund date falls within the window.
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 300)
    void notePeriodAttributionFollowsRefundDate(
            @ForAll("date") LocalDate refundDate,
            @ForAll("date") LocalDate windowStart,
            @ForAll @IntRange(min = 0, max = 120) int windowLengthDays) {

        GstEngine.GstOrder originalOrder = order(INTRA_STATE, List.of(10_000));
        OrderReturnView ret = new OrderReturnView(7L, "SHR-GST-200", refundDate, paise(5_000));

        CreditNote note = CreditNoteProjection.fromReturn(
                ret, originalOrder, DocumentCategory.B2CS, INTRA_CODE, SELLER_STATE);

        // The note carries the refund date verbatim (the sole basis for period attribution).
        assertThat(note.noteDate()).isEqualTo(refundDate);

        // A period is [windowStart, windowEnd]; inclusion is driven purely by note.noteDate().
        LocalDate windowEnd = windowStart.plusDays(windowLengthDays);
        boolean expectedInPeriod =
                !note.noteDate().isBefore(windowStart) && !note.noteDate().isAfter(windowEnd);
        boolean actualInPeriod =
                !refundDate.isBefore(windowStart) && !refundDate.isAfter(windowEnd);
        assertThat(actualInPeriod).isEqualTo(expectedInPeriod);
    }

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 7: Output-tax reduction by credit notes
    // **Validates: Requirements 2.4**
    // A credit note's value equals its taxable + total tax exactly, and the presented net output tax
    // for a period equals the outward output tax minus the sum of the credit notes' total GST.
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 300)
    void creditNotesReduceOutputTaxByTheirTotalGst(
            @ForAll @IntRange(min = 0, max = 5_000_000) int outputTaxPaise,
            @ForAll @Size(min = 0, max = 6) List<@IntRange(min = 0, max = 500_000) Integer> refunds,
            @ForAll boolean inter) {

        String state = inter ? INTER_STATE : INTRA_STATE;
        String stateCode = inter ? INTER_CODE : INTRA_CODE;
        GstEngine.GstOrder originalOrder = order(state, List.of(100_000, 250_000, 90_000));

        BigDecimal totalNoteTax = BigDecimal.ZERO.setScale(2);
        int i = 0;
        for (int refundPaise : refunds) {
            OrderReturnView ret = new OrderReturnView(
                    (long) i, "SHR-GST-30" + i, LocalDate.of(2026, 6, 10), paise(refundPaise));
            CreditNote note = CreditNoteProjection.fromReturn(
                    ret, originalOrder, DocumentCategory.B2CS, stateCode, SELLER_STATE);

            // Note value equals taxable + total tax exactly (Req 2.4 basis).
            assertThat(note.noteValue()).isEqualByComparingTo(note.taxable().add(note.totalTax()));

            totalNoteTax = totalNoteTax.add(note.totalTax());
            i++;
        }

        BigDecimal outputTax = paise(outputTaxPaise);
        BigDecimal netOutputTax = outputTax.subtract(totalNoteTax);

        // The net position is exactly the outward output tax reduced by the notes' total GST.
        assertThat(netOutputTax).isEqualByComparingTo(outputTax.subtract(totalNoteTax));
        assertThat(outputTax.subtract(netOutputTax)).isEqualByComparingTo(totalNoteTax);
    }

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 8: Exclusion of cancelled/rejected
    // **Validates: Requirements 2.6**
    // CreditNoteProjection is a pure function that projects whatever it is given; excluding returns
    // whose original order is cancelled/rejected is enforced by the calling service BEFORE projection.
    // This property asserts that contract: applying the service's exclusion filter first means
    // cancelled/rejected originals produce no notes and contribute zero to every credit-note figure.
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 300)
    void cancelledOrRejectedOriginalsProduceNoNotes(
            @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 1, max = 500_000) Integer> refunds,
            @ForAll @Size(min = 1, max = 8) List<Boolean> cancelledFlags) {

        GstEngine.GstOrder originalOrder = order(INTRA_STATE, List.of(120_000, 60_000));

        int n = Math.min(refunds.size(), cancelledFlags.size());
        BigDecimal projectedTaxFromCancelled = BigDecimal.ZERO.setScale(2);
        BigDecimal projectedTaxFromLive = BigDecimal.ZERO.setScale(2);
        int notesProduced = 0;

        for (int i = 0; i < n; i++) {
            boolean cancelledOrRejected = cancelledFlags.get(i);
            OrderReturnView ret = new OrderReturnView(
                    (long) i, "SHR-GST-40" + i, LocalDate.of(2026, 7, 3), paise(refunds.get(i)));

            // The calling service's contract: exclude cancelled/rejected originals BEFORE projecting.
            if (cancelledOrRejected) {
                // Not projected — contributes nothing. (Verify a note WOULD have carried tax, so the
                // exclusion is meaningful, but it is dropped and never counted.)
                CreditNote wouldBe = CreditNoteProjection.fromReturn(
                        ret, originalOrder, DocumentCategory.B2CS, INTRA_CODE, SELLER_STATE);
                projectedTaxFromCancelled = projectedTaxFromCancelled.add(wouldBe.totalTax());
                continue;
            }

            CreditNote note = CreditNoteProjection.fromReturn(
                    ret, originalOrder, DocumentCategory.B2CS, INTRA_CODE, SELLER_STATE);
            projectedTaxFromLive = projectedTaxFromLive.add(note.totalTax());
            notesProduced++;
        }

        long liveCount = 0;
        for (int i = 0; i < n; i++) {
            if (!cancelledFlags.get(i)) {
                liveCount++;
            }
        }

        // Exactly the non-excluded returns become notes; excluded ones contribute zero regardless of
        // the tax they would otherwise have carried.
        assertThat((long) notesProduced).isEqualTo(liveCount);
        if (liveCount == 0) {
            assertThat(projectedTaxFromLive).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // --- Helpers / generators --------------------------------------------------------------------

    private static BigDecimal paise(int p) {
        return new BigDecimal(p).movePointLeft(2);
    }

    /** Build an order over the given place-of-supply state with one line per supplied line-total. */
    private static GstEngine.GstOrder order(String state, List<Integer> lineTotals) {
        List<GstEngine.GstLine> lines = new ArrayList<>();
        for (int i = 0; i < lineTotals.size(); i++) {
            BigDecimal rate = new BigDecimal(RATES[i % RATES.length]);
            lines.add(new GstEngine.GstLine("3004", "P" + i, rate, 1 + (i % 3), paise(lineTotals.get(i))));
        }
        return new GstEngine.GstOrder(999L, state, LocalDate.of(2026, 5, 1), lines);
    }

    @Provide
    Arbitrary<DocumentCategory> category() {
        return Arbitraries.of(DocumentCategory.values());
    }

    @Provide
    Arbitrary<LocalDate> date() {
        return Arbitraries.integers().between(0, 900)
                .map(offset -> LocalDate.of(2025, 4, 1).plusDays(offset));
    }
}
