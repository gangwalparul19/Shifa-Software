package com.shifa.oms.gst;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.gst.domain.B2bRow;
import com.shifa.oms.gst.domain.B2clRow;
import com.shifa.oms.gst.domain.B2csRow;
import com.shifa.oms.gst.domain.CdnrRow;
import com.shifa.oms.gst.domain.CdnurRow;
import com.shifa.oms.gst.domain.DocRow;
import com.shifa.oms.gst.domain.Gstr1Return;
import com.shifa.oms.gst.domain.HsnRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Renders a {@link Gstr1Return} into the GST Offline Tool's section-wise layout (GST filing
 * compliance, Req 5): per-section CSV files (b2b, b2cl, b2cs, cdnr, cdnur, hsn, docs) matching the
 * portal's column layout, the seven CSVs bundled as a single ZIP, and a portal-schema JSON document.
 *
 * <p>The exporter is a pure renderer over the in-memory {@link Gstr1Return}: every figure is read
 * straight off the return, so the exported totals reconcile to the CA GST dashboard (Req 5.5) and the
 * JSON re-parses to the same per-section totals (Property 13). Sections with no data are emitted
 * header-only in CSV and omitted from the JSON, never failing the export (Req 5.6). The seller GSTIN
 * and the return period (as {@code fp = MMYYYY}) are carried on every artifact (Req 5.4).
 *
 * <p>CSV escaping follows the same approach as {@link GstReportExporter}; portal JSON is built with
 * the shared Jackson {@link ObjectMapper}.
 */
@Component
public class Gstr1Exporter {

    /** The ordered set of section keys / CSV file base-names (Req 5.1). */
    public static final List<String> SECTIONS =
            List.of("b2b", "b2cl", "b2cs", "cdnr", "cdnur", "hsn", "docs");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final ObjectMapper objectMapper;

    public Gstr1Exporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Renders each portal section to its GST Offline Tool CSV. Empty sections are emitted header-only.
     *
     * @return an ordered map of section key → CSV text (keys per {@link #SECTIONS})
     */
    public Map<String, String> toSectionCsvs(Gstr1Return r) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("b2b", b2bCsv(r));
        out.put("b2cl", b2clCsv(r));
        out.put("b2cs", b2csCsv(r));
        out.put("cdnr", cdnrCsv(r));
        out.put("cdnur", cdnurCsv(r));
        out.put("hsn", hsnCsv(r));
        out.put("docs", docsCsv(r));
        return out;
    }

    /** Bundles the seven section CSVs into a single ZIP (one {@code <section>.csv} entry each). */
    public byte[] toZip(Gstr1Return r) {
        Map<String, String> csvs = toSectionCsvs(r);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : csvs.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey() + ".csv"));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "GSTR1_ZIP_FAILED",
                    "Could not build the GSTR-1 CSV bundle.");
        }
        return bytes.toByteArray();
    }

    /**
     * Renders the portal-schema JSON: {@code gstin}, {@code fp} (MMYYYY), and the section arrays
     * (including {@code doc_issue}). Sections with no data are omitted (Req 5.3, 5.4, 5.6).
     */
    public String toPortalJson(Gstr1Return r) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("gstin", nn(r.sellerGstin()));
        root.put("fp", fp(r.month(), r.year()));

        putArrayIfPresent(root, "b2b", b2bJson(r.b2b()));
        putArrayIfPresent(root, "b2cl", b2clJson(r.b2cl()));
        putArrayIfPresent(root, "b2cs", b2csJson(r.b2cs()));
        putArrayIfPresent(root, "cdnr", cdnrJson(r.cdnr()));
        putArrayIfPresent(root, "cdnur", cdnurJson(r.cdnur()));
        putArrayIfPresent(root, "hsn", hsnJson(r.hsn()));
        putArrayIfPresent(root, "doc_issue", docsJson(r.docs()));

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "GSTR1_JSON_FAILED",
                    "Could not render the GSTR-1 portal JSON.");
        }
    }

    // ---------------------------------------------------------------- CSV sections

    private static String b2bCsv(Gstr1Return r) {
        StringBuilder sb = new StringBuilder();
        line(sb, "GSTIN/UIN of Recipient", "Invoice Number", "Invoice date", "Invoice Value",
                "Place Of Supply", "Reverse Charge", "Invoice Type", "Rate", "Taxable Value",
                "Cess Amount");
        for (B2bRow row : r.b2b()) {
            line(sb, nn(row.buyerGstin()), nn(row.orderCode()), date(row.date()), money(row.invoiceValue()),
                    pos(row.stateCode(), row.placeOfSupply()), "N", "Regular", plain(row.rate()),
                    money(row.taxable()), "0.00");
        }
        return sb.toString();
    }

    private static String b2clCsv(Gstr1Return r) {
        StringBuilder sb = new StringBuilder();
        line(sb, "Invoice Number", "Invoice date", "Invoice Value", "Place Of Supply", "Rate",
                "Taxable Value", "Cess Amount", "E-Commerce GSTIN");
        for (B2clRow row : r.b2cl()) {
            line(sb, nn(row.orderCode()), date(row.date()), money(row.invoiceValue()),
                    pos(row.stateCode(), row.placeOfSupply()), plain(row.rate()), money(row.taxable()),
                    "0.00", "");
        }
        return sb.toString();
    }

    private static String b2csCsv(Gstr1Return r) {
        StringBuilder sb = new StringBuilder();
        line(sb, "Type", "Place Of Supply", "Rate", "Taxable Value", "Cess Amount", "E-Commerce GSTIN");
        for (B2csRow row : r.b2cs()) {
            line(sb, nn(row.type()), pos(row.stateCode(), row.placeOfSupply()), plain(row.rate()),
                    money(row.taxable()), "0.00", "");
        }
        return sb.toString();
    }

    private static String cdnrCsv(Gstr1Return r) {
        StringBuilder sb = new StringBuilder();
        line(sb, "GSTIN/UIN of Recipient", "Note Number", "Note date", "Note Type", "Place Of Supply",
                "Note Value", "Rate", "Taxable Value", "Cess Amount");
        for (CdnrRow row : r.cdnr()) {
            line(sb, nn(row.buyerGstin()), nn(row.noteNumber()), date(row.noteDate()), "C",
                    pos(row.stateCode(), row.placeOfSupply()), money(row.noteValue()), plain(row.rate()),
                    money(row.taxable()), "0.00");
        }
        return sb.toString();
    }

    private static String cdnurCsv(Gstr1Return r) {
        StringBuilder sb = new StringBuilder();
        line(sb, "UR Type", "Note Number", "Note date", "Note Type", "Place Of Supply", "Note Value",
                "Rate", "Taxable Value", "Cess Amount");
        for (CdnurRow row : r.cdnur()) {
            line(sb, "B2CL", nn(row.noteNumber()), date(row.noteDate()), "C",
                    pos(row.stateCode(), row.placeOfSupply()), money(row.noteValue()), plain(row.rate()),
                    money(row.taxable()), "0.00");
        }
        return sb.toString();
    }

    private static String hsnCsv(Gstr1Return r) {
        StringBuilder sb = new StringBuilder();
        line(sb, "HSN", "UQC", "Total Quantity", "Rate", "Taxable Value", "Integrated Tax Amount",
                "Central Tax Amount", "State/UT Tax Amount", "Cess Amount");
        for (HsnRow row : r.hsn()) {
            line(sb, nn(row.hsn()), nn(row.uqc()), plain(row.quantity()), plain(row.rate()),
                    money(row.taxable()), money(row.igst()), money(row.cgst()), money(row.sgst()), "0.00");
        }
        return sb.toString();
    }

    private static String docsCsv(Gstr1Return r) {
        StringBuilder sb = new StringBuilder();
        line(sb, "Nature of Document", "Sr. No. From", "Sr. No. To", "Total Number", "Cancelled");
        for (DocRow row : r.docs()) {
            line(sb, nn(row.natureOfDocument()), nn(row.fromNumber()), nn(row.toNumber()),
                    String.valueOf(row.totalCount()), String.valueOf(row.cancelledCount()));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- JSON sections

    private ArrayNode b2bJson(List<B2bRow> rows) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (B2bRow row : rows) {
            ObjectNode n = arr.addObject();
            n.put("gstin", nn(row.buyerGstin()));
            n.put("inum", nn(row.orderCode()));
            n.put("idt", date(row.date()));
            n.put("val", num(row.invoiceValue()));
            n.put("pos", nn(row.stateCode()));
            n.put("rt", num(row.rate()));
            n.put("txval", num(row.taxable()));
            n.put("camt", num(row.cgst()));
            n.put("samt", num(row.sgst()));
            n.put("iamt", num(row.igst()));
        }
        return arr;
    }

    private ArrayNode b2clJson(List<B2clRow> rows) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (B2clRow row : rows) {
            ObjectNode n = arr.addObject();
            n.put("inum", nn(row.orderCode()));
            n.put("idt", date(row.date()));
            n.put("val", num(row.invoiceValue()));
            n.put("pos", nn(row.stateCode()));
            n.put("rt", num(row.rate()));
            n.put("txval", num(row.taxable()));
            n.put("iamt", num(row.igst()));
        }
        return arr;
    }

    private ArrayNode b2csJson(List<B2csRow> rows) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (B2csRow row : rows) {
            ObjectNode n = arr.addObject();
            n.put("sply_ty", row.supplyType() == null ? "" : row.supplyType().name());
            n.put("typ", nn(row.type()));
            n.put("pos", nn(row.stateCode()));
            n.put("rt", num(row.rate()));
            n.put("txval", num(row.taxable()));
            n.put("camt", num(row.cgst()));
            n.put("samt", num(row.sgst()));
            n.put("iamt", num(row.igst()));
        }
        return arr;
    }

    private ArrayNode cdnrJson(List<CdnrRow> rows) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (CdnrRow row : rows) {
            ObjectNode n = arr.addObject();
            n.put("gstin", nn(row.buyerGstin()));
            n.put("nt_num", nn(row.noteNumber()));
            n.put("nt_dt", date(row.noteDate()));
            n.put("ntty", "C");
            n.put("onum", nn(row.originalOrderCode()));
            n.put("pos", nn(row.stateCode()));
            n.put("val", num(row.noteValue()));
            n.put("rt", num(row.rate()));
            n.put("txval", num(row.taxable()));
            n.put("camt", num(row.cgst()));
            n.put("samt", num(row.sgst()));
            n.put("iamt", num(row.igst()));
        }
        return arr;
    }

    private ArrayNode cdnurJson(List<CdnurRow> rows) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (CdnurRow row : rows) {
            ObjectNode n = arr.addObject();
            n.put("typ", "B2CL");
            n.put("nt_num", nn(row.noteNumber()));
            n.put("nt_dt", date(row.noteDate()));
            n.put("ntty", "C");
            n.put("onum", nn(row.originalOrderCode()));
            n.put("pos", nn(row.stateCode()));
            n.put("val", num(row.noteValue()));
            n.put("rt", num(row.rate()));
            n.put("txval", num(row.taxable()));
            n.put("camt", num(row.cgst()));
            n.put("samt", num(row.sgst()));
            n.put("iamt", num(row.igst()));
        }
        return arr;
    }

    private ArrayNode hsnJson(List<HsnRow> rows) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (HsnRow row : rows) {
            ObjectNode n = arr.addObject();
            n.put("hsn_sc", nn(row.hsn()));
            n.put("uqc", nn(row.uqc()));
            n.put("qty", num(row.quantity()));
            n.put("rt", num(row.rate()));
            n.put("txval", num(row.taxable()));
            n.put("iamt", num(row.igst()));
            n.put("camt", num(row.cgst()));
            n.put("samt", num(row.sgst()));
        }
        return arr;
    }

    private ArrayNode docsJson(List<DocRow> rows) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (DocRow row : rows) {
            ObjectNode n = arr.addObject();
            n.put("doc_nature", nn(row.natureOfDocument()));
            n.put("from", nn(row.fromNumber()));
            n.put("to", nn(row.toNumber()));
            n.put("totnum", row.totalCount());
            n.put("cancel", row.cancelledCount());
        }
        return arr;
    }

    private static void putArrayIfPresent(ObjectNode root, String field, ArrayNode arr) {
        if (arr != null && !arr.isEmpty()) {
            root.set(field, arr);
        }
    }

    // ---------------------------------------------------------------- CSV/format helpers

    private static void line(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells[i]));
        }
        sb.append('\n');
    }

    private static String escape(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    /** GST Offline Tool place-of-supply format: {@code "NN-State Name"} (code prefix when resolved). */
    private static String pos(String stateCode, String stateName) {
        String name = nn(stateName);
        if (stateCode == null || stateCode.isBlank()) {
            return name;
        }
        return name.isBlank() ? stateCode : stateCode + "-" + name;
    }

    private static String date(LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }

    private static String money(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    /** Numeric JSON value rounded to 2 decimals so JSON totals reconcile to the CSV/dashboard. */
    private static double num(BigDecimal v) {
        return v == null ? 0.0 : v.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String nn(String v) {
        return v == null ? "" : v;
    }

    private static String fp(int month, int year) {
        return String.format(Locale.ROOT, "%02d%04d", month, year);
    }
}
