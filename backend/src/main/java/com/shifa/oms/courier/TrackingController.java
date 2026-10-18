package com.shifa.oms.courier;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.courier.dto.TrackByMobileResponse;
import com.shifa.oms.courier.dto.TrackingResponse;
import com.shifa.oms.invoice.InvoiceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final PublicTrackingRateLimiter rateLimiter;

    public TrackingController(TrackingService trackingService,
                              InvoiceService invoiceService,
                              PublicTrackingRateLimiter rateLimiter) {
        this.trackingService = trackingService;
        this.invoiceService = invoiceService;
        this.rateLimiter = rateLimiter;
    }

    /** Current status, AWB, and courier tracking link for an order (Req 13.4). */
    @GetMapping("/{orderCode}")
    public TrackingResponse track(@PathVariable String orderCode) {
        return trackingService.track(orderCode);
    }

    /**
     * Token-gated customer tracking (ENHANCEMENT 2.2): resolves an order by its
     * opaque per-order token rather than the enumerable order code, returning a
     * customer-friendly status + stage timeline + courier link. Backs the public
     * {@code /track/{token}} page shared with the customer. Still public (under
     * {@code /api/track/**}); the token itself is the access control.
     */
    @GetMapping("/t/{token}")
    public com.shifa.oms.courier.dto.PublicTrackingResponse trackByToken(@PathVariable String token) {
        return trackingService.trackByToken(token);
    }

    /**
     * Public self-service lookup of a customer's orders by their registered
     * mobile number, backing the {@code /track-order} page. Returns privacy-safe
     * tracking cards (no money/address/mobile/id); the customer then opens one
     * via {@link #trackByToken(String)}. Still public (under {@code /api/track/**}).
     *
     * <p>Rate-limited per client IP (anti-scraping): beyond the window a 429 is
     * returned. An unknown/malformed mobile yields an empty list (not a 404), so
     * nothing can be probed.
     */
    @GetMapping("/by-mobile")
    public TrackByMobileResponse byMobile(@RequestParam String mobile, HttpServletRequest httpRequest) {
        if (!rateLimiter.tryAcquire(clientIp(httpRequest))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many lookups, please wait a few minutes.");
        }
        return trackingService.ordersByMobile(mobile);
    }

    /**
     * Best-effort client IP. Behind Nginx the real client is the first entry of
     * {@code X-Forwarded-For}; fall back to the socket address otherwise (mirrors
     * {@code AuthController}).
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
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
