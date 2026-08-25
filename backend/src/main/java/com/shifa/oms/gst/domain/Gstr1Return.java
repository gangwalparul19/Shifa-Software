package com.shifa.oms.gst.domain;

import java.util.List;

/**
 * The portal-ready GSTR-1 outward-supply return for a single reporting period (GST filing
 * compliance, Tier 1 — composes Reqs 1–6).
 *
 * <p>This is the pure in-memory model that the {@code Gstr1Builder} produces and the exporter renders
 * into the GST Offline Tool's section-wise CSV / portal JSON. It carries the seller GSTIN and return
 * period (Req 5.4), the seven portal sections (b2b, b2cl, b2cs, cdnr, cdnur, hsn, docs — Req 5.1),
 * the list of place-of-supply state names that could not be resolved to a code (Req 6.3), and a
 * {@link GstEngine.Gstr3bSummary reconciliation} of the same period's totals so callers can verify
 * the sections reconcile to the CA GST dashboard figures (Req 5.5).
 *
 * @param sellerGstin      the seller's GSTIN (Req 5.4)
 * @param month            the return-period month (1–12) (Req 5.4)
 * @param year             the return-period financial/calendar year (Req 5.4)
 * @param b2b              the B2B (registered-buyer) invoice-level rows
 * @param b2cl             the B2CL (B2C large) invoice-level rows
 * @param b2cs             the B2CS (B2C small) aggregated rows
 * @param cdnr             the CDNR (registered) credit/debit-note rows
 * @param cdnur            the CDNUR (unregistered) credit/debit-note rows
 * @param hsn              the Table-12 HSN summary rows (grouped by HSN + rate)
 * @param docs             the Table-13 documents-issued summary rows
 * @param unresolvedStates place-of-supply names needing a state-code mapping (Req 6.3)
 * @param reconciliation   the GSTR-3B-style period totals for the reconcile check (Req 5.5)
 */
public record Gstr1Return(
        String sellerGstin,
        int month,
        int year,
        List<B2bRow> b2b,
        List<B2clRow> b2cl,
        List<B2csRow> b2cs,
        List<CdnrRow> cdnr,
        List<CdnurRow> cdnur,
        List<HsnRow> hsn,
        List<DocRow> docs,
        List<UnresolvedStateFlag> unresolvedStates,
        GstEngine.Gstr3bSummary reconciliation) {
}
