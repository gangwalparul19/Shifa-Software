package com.shifa.oms.gst.dto;

import com.shifa.oms.gst.domain.B2bRow;
import com.shifa.oms.gst.domain.B2clRow;
import com.shifa.oms.gst.domain.B2csRow;
import com.shifa.oms.gst.domain.CdnrRow;
import com.shifa.oms.gst.domain.CdnurRow;
import com.shifa.oms.gst.domain.DocRow;
import com.shifa.oms.gst.domain.GstEngine.Gstr3bSummary;
import com.shifa.oms.gst.domain.Gstr1Return;
import com.shifa.oms.gst.domain.HsnRow;
import com.shifa.oms.gst.domain.UnresolvedStateFlag;

import java.util.List;
import java.util.Locale;

/**
 * The API payload for the portal-ready GSTR-1 return of a reporting period (GST filing compliance,
 * Req 5). It mirrors the pure {@link Gstr1Return} domain model one-to-one — the seller GSTIN and
 * period (Req 5.4), the seven portal sections (Req 5.1), the unresolved place-of-supply flags
 * (Req 6.3), and the {@link Gstr3bSummary reconciliation} of the same period's totals so the UI can
 * show that the sections reconcile to the CA GST dashboard (Req 5.5).
 *
 * <p>The section rows are the same immutable domain records the exporter reads, so the JSON response,
 * the CSV bundle, and the portal JSON all reconcile to identical figures. A convenience {@code period}
 * ({@code MMYYYY}) is added to match the export filename / portal {@code fp}.
 *
 * @param sellerGstin      the seller's GSTIN
 * @param month            the return-period month (1–12)
 * @param year             the return-period year
 * @param period           the return period as {@code MMYYYY} (portal {@code fp})
 * @param b2b              the B2B (registered-buyer) invoice-level rows
 * @param b2cl             the B2CL (B2C large) invoice-level rows
 * @param b2cs             the B2CS (B2C small) aggregated rows
 * @param cdnr             the CDNR (registered) credit/debit-note rows
 * @param cdnur            the CDNUR (unregistered) credit/debit-note rows
 * @param hsn              the Table-12 HSN summary rows
 * @param docs             the Table-13 documents-issued summary rows
 * @param unresolvedStates place-of-supply names needing a state-code mapping (Req 6.3)
 * @param reconciliation   the GSTR-3B-style period totals for the reconcile check (Req 5.5)
 */
public record Gstr1ReturnResponse(
        String sellerGstin,
        int month,
        int year,
        String period,
        List<B2bRow> b2b,
        List<B2clRow> b2cl,
        List<B2csRow> b2cs,
        List<CdnrRow> cdnr,
        List<CdnurRow> cdnur,
        List<HsnRow> hsn,
        List<DocRow> docs,
        List<UnresolvedStateFlag> unresolvedStates,
        Gstr3bSummary reconciliation) {

    /** Maps the pure {@link Gstr1Return} domain model to the API payload (identical figures). */
    public static Gstr1ReturnResponse from(Gstr1Return r) {
        return new Gstr1ReturnResponse(
                r.sellerGstin(),
                r.month(),
                r.year(),
                String.format(Locale.ROOT, "%02d%04d", r.month(), r.year()),
                r.b2b(),
                r.b2cl(),
                r.b2cs(),
                r.cdnr(),
                r.cdnur(),
                r.hsn(),
                r.docs(),
                r.unresolvedStates(),
                r.reconciliation());
    }
}
