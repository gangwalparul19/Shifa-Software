package com.shifa.oms.gst;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.gst.domain.B2bRow;
import com.shifa.oms.gst.domain.B2clRow;
import com.shifa.oms.gst.domain.B2csRow;
import com.shifa.oms.gst.domain.CdnrRow;
import com.shifa.oms.gst.domain.CdnurRow;
import com.shifa.oms.gst.domain.DocRow;
import com.shifa.oms.gst.domain.Gstr1Return;
import com.shifa.oms.gst.domain.HsnRow;
import com.shifa.oms.gst.domain.SupplyType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Gstr1Exporter} (GST filing compliance, Task 8.3 — Reqs 5.1, 5.2, 5.4).
 *
 * <p>These pin the GST Offline Tool section CSV header/column layout by worked example, assert the
 * portal JSON carries the seller GSTIN and the {@code fp} period (MMYYYY), and verify {@code toZip}
 * bundles exactly the seven section entries. The exporter is a plain {@code @Component} with an
 * injected Jackson {@link ObjectMapper}, so it is instantiated directly (no Spring context).
 */
class Gstr1ExporterTest {

    private static final String SELLER_GSTIN = "23AABCS1234F1Z5";

    private final Gstr1Exporter exporter = new Gstr1Exporter(new ObjectMapper());

    // ------------------------------------------------------------------ fixture

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** A GSTR-1 return with a couple of rows across every portal section for August 2025. */
    private static Gstr1Return sampleReturn() {
        List<B2bRow> b2b = List.of(
                new B2bRow("27ABCDE1234F1Z5", "SHR-GST-001", LocalDate.of(2025, 8, 5),
                        bd("11800.00"), "Maharashtra", "27", bd("18"), bd("10000.00"),
                        bd("0.00"), bd("0.00"), bd("1800.00")),
                new B2bRow("23AABCS1234F1Z5", "SHR-GST-002", LocalDate.of(2025, 8, 6),
                        bd("1050.00"), "Madhya Pradesh", "23", bd("5"), bd("1000.00"),
                        bd("25.00"), bd("25.00"), bd("0.00")));

        List<B2clRow> b2cl = List.of(
                new B2clRow("SHR-GST-010", LocalDate.of(2025, 8, 10), bd("300000.00"),
                        "Karnataka", "29", bd("18"), bd("254237.29"), bd("45762.71")));

        List<B2csRow> b2cs = List.of(
                new B2csRow("OE", "Maharashtra", "27", SupplyType.INTER, bd("18"),
                        bd("5000.00"), bd("0.00"), bd("0.00"), bd("900.00")),
                new B2csRow("OE", "Madhya Pradesh", "23", SupplyType.INTRA, bd("5"),
                        bd("2000.00"), bd("50.00"), bd("50.00"), bd("0.00")));

        List<CdnrRow> cdnr = List.of(
                new CdnrRow("27ABCDE1234F1Z5", "CN-001", LocalDate.of(2025, 8, 20), "SHR-GST-001",
                        "Maharashtra", "27", bd("1180.00"), bd("18"), bd("1000.00"),
                        bd("0.00"), bd("0.00"), bd("180.00")));

        List<CdnurRow> cdnur = List.of(
                new CdnurRow("CN-050", LocalDate.of(2025, 8, 21), "SHR-GST-010", "Karnataka", "29",
                        bd("3000.00"), bd("18"), bd("2542.37"), bd("0.00"), bd("0.00"), bd("457.63")));

        List<HsnRow> hsn = List.of(
                new HsnRow("30049011", "NOS", bd("5"), bd("10"), bd("1000.00"),
                        bd("25.00"), bd("25.00"), bd("0.00"), true, null),
                new HsnRow("3304", "NOS", bd("18"), bd("5"), bd("10000.00"),
                        bd("0.00"), bd("0.00"), bd("1800.00"), false, "HSN 3304 needs 6 digits"));

        List<DocRow> docs = List.of(
                new DocRow("Invoices for outward supply", "SHR-GST-001", "SHR-GST-010", 12, 1));

        return new Gstr1Return(SELLER_GSTIN, 8, 2025, b2b, b2cl, b2cs, cdnr, cdnur, hsn, docs,
                List.of(), null);
    }

    /** Splits a section CSV into its lines (the exporter terminates every row with {@code \n}). */
    private static List<String> lines(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split("\n", -1)) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ CSV layout

    @Test
    void b2bCsvPinsHeaderAndRowLayout() {
        List<String> l = lines(exporter.toSectionCsvs(sampleReturn()).get("b2b"));
        assertThat(l.get(0)).isEqualTo("GSTIN/UIN of Recipient,Invoice Number,Invoice date,"
                + "Invoice Value,Place Of Supply,Reverse Charge,Invoice Type,Rate,Taxable Value,"
                + "Cess Amount");
        assertThat(l.get(1)).isEqualTo(
                "27ABCDE1234F1Z5,SHR-GST-001,05-Aug-2025,11800.00,27-Maharashtra,N,Regular,18,"
                        + "10000.00,0.00");
        assertThat(l.get(2)).isEqualTo(
                "23AABCS1234F1Z5,SHR-GST-002,06-Aug-2025,1050.00,23-Madhya Pradesh,N,Regular,5,"
                        + "1000.00,0.00");
    }

    @Test
    void b2clCsvPinsHeaderAndRowLayout() {
        List<String> l = lines(exporter.toSectionCsvs(sampleReturn()).get("b2cl"));
        assertThat(l.get(0)).isEqualTo(
                "Invoice Number,Invoice date,Invoice Value,Place Of Supply,Rate,Taxable Value,"
                        + "Cess Amount,E-Commerce GSTIN");
        assertThat(l.get(1)).isEqualTo(
                "SHR-GST-010,10-Aug-2025,300000.00,29-Karnataka,18,254237.29,0.00,");
    }

    @Test
    void b2csCsvPinsHeaderAndRowLayout() {
        List<String> l = lines(exporter.toSectionCsvs(sampleReturn()).get("b2cs"));
        assertThat(l.get(0)).isEqualTo(
                "Type,Place Of Supply,Rate,Taxable Value,Cess Amount,E-Commerce GSTIN");
        assertThat(l.get(1)).isEqualTo("OE,27-Maharashtra,18,5000.00,0.00,");
        assertThat(l.get(2)).isEqualTo("OE,23-Madhya Pradesh,5,2000.00,0.00,");
    }

    @Test
    void cdnrCsvPinsHeaderAndRowLayout() {
        List<String> l = lines(exporter.toSectionCsvs(sampleReturn()).get("cdnr"));
        assertThat(l.get(0)).isEqualTo(
                "GSTIN/UIN of Recipient,Note Number,Note date,Note Type,Place Of Supply,Note Value,"
                        + "Rate,Taxable Value,Cess Amount");
        assertThat(l.get(1)).isEqualTo(
                "27ABCDE1234F1Z5,CN-001,20-Aug-2025,C,27-Maharashtra,1180.00,18,1000.00,0.00");
    }

    @Test
    void cdnurCsvPinsHeaderAndRowLayout() {
        List<String> l = lines(exporter.toSectionCsvs(sampleReturn()).get("cdnur"));
        assertThat(l.get(0)).isEqualTo(
                "UR Type,Note Number,Note date,Note Type,Place Of Supply,Note Value,Rate,"
                        + "Taxable Value,Cess Amount");
        assertThat(l.get(1)).isEqualTo(
                "B2CL,CN-050,21-Aug-2025,C,29-Karnataka,3000.00,18,2542.37,0.00");
    }

    @Test
    void hsnCsvPinsHeaderAndRowLayout() {
        List<String> l = lines(exporter.toSectionCsvs(sampleReturn()).get("hsn"));
        assertThat(l.get(0)).isEqualTo(
                "HSN,UQC,Total Quantity,Rate,Taxable Value,Integrated Tax Amount,"
                        + "Central Tax Amount,State/UT Tax Amount,Cess Amount");
        assertThat(l.get(1)).isEqualTo("30049011,NOS,10,5,1000.00,0.00,25.00,25.00,0.00");
        assertThat(l.get(2)).isEqualTo("3304,NOS,5,18,10000.00,1800.00,0.00,0.00,0.00");
    }

    @Test
    void docsCsvPinsHeaderAndRowLayout() {
        List<String> l = lines(exporter.toSectionCsvs(sampleReturn()).get("docs"));
        assertThat(l.get(0)).isEqualTo("Nature of Document,Sr. No. From,Sr. No. To,Total Number,"
                + "Cancelled");
        assertThat(l.get(1)).isEqualTo("Invoices for outward supply,SHR-GST-001,SHR-GST-010,12,1");
    }

    @Test
    void emptySectionIsEmittedHeaderOnly() {
        Gstr1Return empty = new Gstr1Return(SELLER_GSTIN, 8, 2025, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), null);
        Map<String, String> csvs = exporter.toSectionCsvs(empty);
        // Header present, no data rows (Req 5.6 — empty sections never fail the export).
        assertThat(lines(csvs.get("b2b"))).hasSize(1);
        assertThat(lines(csvs.get("hsn"))).hasSize(1);
    }

    // ------------------------------------------------------------------ ZIP bundle

    @Test
    void toZipContainsTheSevenSectionEntries() throws Exception {
        byte[] zip = exporter.toZip(sampleReturn());

        List<String> entryNames = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                entryNames.add(e.getName());
                in.closeEntry();
            }
        }

        assertThat(entryNames).containsExactly(
                "b2b.csv", "b2cl.csv", "b2cs.csv", "cdnr.csv", "cdnur.csv", "hsn.csv", "docs.csv");
    }

    @Test
    void zipEntryContentMatchesTheSectionCsv() throws Exception {
        Gstr1Return r = sampleReturn();
        String expectedB2b = exporter.toSectionCsvs(r).get("b2b");
        byte[] zip = exporter.toZip(r);

        String b2bFromZip = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals("b2b.csv")) {
                    b2bFromZip = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                in.closeEntry();
            }
        }
        assertThat(b2bFromZip).isEqualTo(expectedB2b);
    }

    // ------------------------------------------------------------------ portal JSON

    @Test
    void portalJsonCarriesSellerGstinAndPeriod() throws Exception {
        JsonNode root = new ObjectMapper().readTree(exporter.toPortalJson(sampleReturn()));

        assertThat(root.get("gstin").asText()).isEqualTo(SELLER_GSTIN);
        // fp is the return period as MMYYYY (Req 5.4): August 2025 -> 082025.
        assertThat(root.get("fp").asText()).isEqualTo("082025");
    }

    @Test
    void portalJsonEmitsPopulatedSectionArrays() throws Exception {
        JsonNode root = new ObjectMapper().readTree(exporter.toPortalJson(sampleReturn()));

        assertThat(root.get("b2b").isArray()).isTrue();
        assertThat(root.get("b2b")).hasSize(2);
        assertThat(root.get("b2cl")).hasSize(1);
        assertThat(root.get("b2cs")).hasSize(2);
        assertThat(root.get("cdnr")).hasSize(1);
        assertThat(root.get("cdnur")).hasSize(1);
        assertThat(root.get("hsn")).hasSize(2);
        // Documents-issued is carried under the portal's doc_issue key.
        assertThat(root.get("doc_issue")).hasSize(1);
    }

    @Test
    void portalJsonOmitsEmptySections() throws Exception {
        Gstr1Return empty = new Gstr1Return(SELLER_GSTIN, 3, 2026, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), null);
        JsonNode root = new ObjectMapper().readTree(exporter.toPortalJson(empty));

        // Seller GSTIN + period always present; empty sections omitted (Req 5.4, 5.6).
        assertThat(root.get("gstin").asText()).isEqualTo(SELLER_GSTIN);
        assertThat(root.get("fp").asText()).isEqualTo("032026");
        assertThat(root.has("b2b")).isFalse();
        assertThat(root.has("doc_issue")).isFalse();
    }
}
