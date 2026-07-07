package com.shifa.oms.label;

import com.shifa.oms.label.dto.BulkLabelRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal company label printing endpoints (Req 10.4).
 *
 * <p>Restricted to the {@code ADMIN} role via method security (unauthenticated →
 * 401, non-admin → 403). Both endpoints stream a {@code application/pdf} body:
 * one endpoint prints a single order's internal label, the other prints a single
 * combined PDF with one internal-label block per requested order.
 */
@RestController
@RequestMapping("/api/admin/labels/internal")
@PreAuthorize("hasRole('ADMIN')")
public class LabelController {

    private final LabelService labelService;

    public LabelController(LabelService labelService) {
        this.labelService = labelService;
    }

    /** Print the internal company label PDF for a single order (Req 10.4). */
    @GetMapping("/{orderId}")
    public ResponseEntity<byte[]> internalLabel(@PathVariable Long orderId) {
        byte[] pdf = labelService.internalLabelPdf(orderId);
        return pdfResponse(pdf, "internal-label-" + orderId + ".pdf");
    }

    /**
     * Print a single combined PDF containing one internal-label block per
     * requested order (Req 10.4).
     */
    @PostMapping("/bulk")
    public ResponseEntity<byte[]> bulkInternalLabels(@Valid @RequestBody BulkLabelRequest request) {
        byte[] pdf = labelService.bulkInternalLabelPdf(request.orderIds());
        return pdfResponse(pdf, "internal-labels-bulk.pdf");
    }

    private ResponseEntity<byte[]> pdfResponse(byte[] pdf, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(pdf);
    }
}
