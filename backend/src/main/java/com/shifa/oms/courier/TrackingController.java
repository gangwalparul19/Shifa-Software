package com.shifa.oms.courier;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.common.ClientIpResolver;
import com.shifa.oms.courier.dto.TrackByMobileResponse;
import com.shifa.oms.courier.dto.TrackingResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
    private final PublicTrackingRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    @Autowired
    public TrackingController(TrackingService trackingService,
                              PublicTrackingRateLimiter rateLimiter,
                              ClientIpResolver clientIpResolver) {
        this.trackingService = trackingService;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
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
        if (!rateLimiter.tryAcquire(clientIpResolver.resolve(httpRequest))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many lookups, please wait a few minutes.");
        }
        return trackingService.ordersByMobile(mobile);
    }

    // There is deliberately NO public invoice download here. An invoice carries the
    // customer's name, address and mobile plus the seller GSTIN, and generating one
    // allocates the next GST invoice number; neither belongs behind a guessable order
    // code with no login. Staff download invoices via the authenticated
    // GET /api/orders/{id}/invoice.
}
