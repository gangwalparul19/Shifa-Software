package com.shifa.oms.courier;

import com.shifa.oms.courier.dto.TrackingResponse;
import com.shifa.oms.invoice.InvoiceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public customer order-tracking endpoint (Req 13.4).
 *
 * <p>Open like the catalog (permitted in {@link com.shifa.oms.auth.SecurityConfig}):
 * a customer tracks their order by its code without authenticating, receiving the
 * current status, the AWB, and a courier tracking link.
 */
@RestController
@RequestMapping("/api/track")
public class TrackingController {

    private final TrackingService trackingService;
    private final InvoiceService invoiceService;

    public TrackingController(TrackingService trackingService, InvoiceService invoiceService) {
        this.trackingService = trackingService;
        this.invoiceService = invoiceService;
    }

    /** Current status, AWB, and courier tracking link for an order (Req 13.4). */
    @GetMapping("/{orderCode}")
    public TrackingResponse track(@PathVariable String orderCode) {
        return trackingService.track(orderCode);
    }

    /**
     * Public PDF invoice for an order by its code, mirroring the public tracking
     * endpoint above: the storefront customer downloads their own invoice by
     * order code without authenticating (order codes are the same unguessable-ish
     * identifiers this tracking endpoint already exposes by). An unknown code
     * yields 404. The PDF is streamed inline as {@code application/pdf}.
     */
    @GetMapping("/{orderCode}/invoice")
    public ResponseEntity<byte[]> invoice(@PathVariable String orderCode) {
        InvoiceService.InvoiceDocument invoice = invoiceService.invoicePdfByOrderCode(orderCode);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + invoice.filename() + "\"")
                .body(invoice.content());
    }
}
