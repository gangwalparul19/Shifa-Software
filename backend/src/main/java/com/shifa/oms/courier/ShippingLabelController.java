package com.shifa.oms.courier;

import com.shifa.oms.courier.dto.BulkShippingLabelRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin courier shipping-label printing endpoint (Req 12.5).
 *
 * <p>Restricted to {@code ADMIN} via method security (401 unauthenticated, 403
 * non-admin). Mirrors the internal-label bulk endpoint: it streams a single
 * {@code application/pdf} with one courier shipping-label block per requested
 * order that has an assigned AWB.
 */
@RestController
@RequestMapping("/api/admin/labels/shipping")
@PreAuthorize("hasRole('ADMIN')")
public class ShippingLabelController {

    private final ShippingLabelService shippingLabelService;

    public ShippingLabelController(ShippingLabelService shippingLabelService) {
        this.shippingLabelService = shippingLabelService;
    }

    /** Print a combined PDF of courier shipping labels for orders with an AWB (Req 12.5). */
    @PostMapping("/bulk")
    public ResponseEntity<byte[]> bulkShippingLabels(@Valid @RequestBody BulkShippingLabelRequest request) {
        byte[] pdf = shippingLabelService.bulkShippingLabelPdf(request.orderIds());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"shipping-labels-bulk.pdf\"")
                .body(pdf);
    }
}
