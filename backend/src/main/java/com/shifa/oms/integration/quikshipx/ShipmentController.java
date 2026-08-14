package com.shifa.oms.integration.quikshipx;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.integration.quikshipx.dto.PublishNowResponse;
import com.shifa.oms.integration.quikshipx.dto.SetAwbRequest;
import com.shifa.oms.integration.quikshipx.dto.ShipmentResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read access to an order's QuikShipX shipment, and the ADMIN fallback-mode toggle
 * (Req 10.1–10.6, 13.3–13.5).
 *
 * <p>There is deliberately <b>no label-download endpoint</b>: QuikShipX documents no label
 * operation, so the packing team downloads the label from the QuikShipX portal. The
 * response carries the order reference so they can find the shipment there, and the label
 * URL only if the create-order response happened to return one.
 */
@RestController
@RequestMapping("/api")
public class ShipmentController {

    private static final Logger log = LoggerFactory.getLogger(ShipmentController.class);

    private final OrderShipmentRepository shipmentRepository;
    private final FallbackModeService fallbackModeService;
    private final QuikShipXPublisher publisher;
    private final QuikShipXProperties properties;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final QuikShipXTrackingService trackingService;

    public ShipmentController(OrderShipmentRepository shipmentRepository,
                              FallbackModeService fallbackModeService,
                              QuikShipXPublisher publisher,
                              QuikShipXProperties properties,
                              CurrentUserService currentUserService,
                              AuditService auditService,
                              QuikShipXTrackingService trackingService) {
        this.shipmentRepository = shipmentRepository;
        this.fallbackModeService = fallbackModeService;
        this.publisher = publisher;
        this.properties = properties;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.trackingService = trackingService;
    }

    /**
     * The shipment for an order: AWB, courier, tracking link, last mirrored status and the
     * QuikShipX order reference.
     *
     * <p>Open to every staff role that can already see the order detail, since this is the
     * same tracking information a customer would be told over the phone.
     */
    @GetMapping("/orders/{id}/shipment")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALESPERSON','TEAM_LEAD','PACKING_USER')")
    public ShipmentResponse get(@PathVariable Long id) {
        return shipmentRepository.findByOrderId(id)
                // A mirrored status is present (at minimum "Pending" from publication), so
                // the UI shows QuikShipX's status rather than the "maintained in portal"
                // placeholder. It stays a placeholder only if somehow no status was recorded.
                .map(shipment -> ShipmentResponse.from(shipment,
                        shipment.getLastStatusToken() != null || properties.isStatusFeedAvailable()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order " + id + " has no courier shipment."));
    }

    /**
     * Queries QuikShipX now for the order's live status via the track-order API and
     * mirrors it onto the shipment (returns the raw QuikShipX response too, so the
     * exact field names can be confirmed on first use). ADMIN-only and audited.
     *
     * <p>Works immediately per order; the scheduled poller does the same for all
     * active shipments once {@code app.quikshipx.status-feed-available} is on.
     */
    @PostMapping("/admin/orders/{id}/track-quikshipx")
    @PreAuthorize("hasRole('ADMIN')")
    public QuikShipXTrackingService.TrackResult trackNow(@PathVariable Long id) {
        String actor = currentUserService.requireCurrentUser().username();
        QuikShipXTrackingService.TrackResult result = trackingService.trackByOrderId(id);
        auditService.record(AuditActions.QUIKSHIPX_STATUS_MIRRORED, AuditActions.ENTITY_ORDER,
                String.valueOf(id),
                "Manual QuikShipX track by " + actor + ": "
                        + (result.ok() ? result.status() : "failed — " + result.detail()));
        return result;
    }

    /**
     * Records an AWB an admin copied from the QuikShipX portal and immediately tracks the
     * shipment by it (ADMIN-only, audited).
     *
     * <p>QuikShipX's create-order response is undocumented and frequently returns no AWB,
     * so tracking falls back to our order reference, which QuikShipX does not recognise
     * ("Shipment Not Found"). Entering the portal AWB here lets us track by the key
     * QuikShipX actually resolves (tracking_type {@code awb}).
     */
    @PostMapping("/admin/orders/{id}/quikshipx-awb")
    @PreAuthorize("hasRole('ADMIN')")
    public QuikShipXTrackingService.TrackResult setAwbAndTrack(@PathVariable Long id,
                                                               @Valid @RequestBody SetAwbRequest request) {
        String actor = currentUserService.requireCurrentUser().username();
        QuikShipXTrackingService.TrackResult result = trackingService.setAwbAndTrack(id, request.awb());
        auditService.record(AuditActions.QUIKSHIPX_STATUS_MIRRORED, AuditActions.ENTITY_ORDER,
                String.valueOf(id),
                "AWB set to " + request.awb() + " by " + actor + "; track: "
                        + (result.ok() ? result.status() : "failed — " + result.detail()));
        return result;
    }

    /**
     * Returns fulfilment authority for one order to Shifa OMS: the internal label becomes
     * printable again, the order rejoins the packing queue, and courier status events for
     * it are ignored (Req 13.3).
     *
     * <p>ADMIN only, and audited, because it overrides the configured fulfilment model for
     * a single shipment.
     */
    @PostMapping("/admin/orders/{id}/fallback-mode")
    @PreAuthorize("hasRole('ADMIN')")
    public FallbackModeService.Result enableFallbackMode(@PathVariable Long id) {
        return fallbackModeService.enable(id, currentUserService.requireCurrentUser().username());
    }

    /**
     * Sends an already-approved order to QuikShipX now, without waiting for the drainer or
     * re-approving (spec {@code shopify-quikshipx-order-sync}, Req 5.1).
     *
     * <p>Runs the same {@link QuikShipXPublisher} the drainer uses, so it honours every
     * eligibility guard and the {@code UNIQUE(order_id)} shipment constraint — calling it
     * twice cannot create two shipments. It is synchronous on purpose: the admin pressed a
     * button and wants the answer, be it the AWB, a skip reason (credentials missing,
     * already published, defaults incomplete) or a QuikShipX rejection, rather than a queued
     * "maybe later".
     *
     * <p>A QuikShipX-side failure is returned as a {@code PUBLICATION_FAILED} body, not a
     * 500: it is an expected outcome the admin should read and retry, not a server error.
     * The order is left untouched in that case, so a retry is safe.
     */
    @PostMapping("/admin/orders/{id}/publish-quikshipx")
    @PreAuthorize("hasRole('ADMIN')")
    public PublishNowResponse publishNow(@PathVariable Long id) {
        String actor = currentUserService.requireCurrentUser().username();
        PublishNowResponse response;
        try {
            QuikShipXPublisher.Result result = publisher.publish(id);
            response = result.published()
                    ? shipmentRepository.findByOrderId(id)
                            .map(PublishNowResponse::published)
                            // Published but the row is somehow unreadable — still report success.
                            .orElseGet(() -> new PublishNowResponse(
                                    true, "PUBLISHED", result.detail(), result.detail(),
                                    null, null, properties.isTestSecret()))
                    : PublishNowResponse.skipped(result);
        } catch (QuikShipXPublicationException e) {
            // The publisher already left the order unchanged; surface the reason so the
            // admin can fix configuration or retry.
            response = PublishNowResponse.failed(e.getMessage());
        }

        auditService.record(AuditActions.QUIKSHIPX_PUBLISHED, AuditActions.ENTITY_ORDER,
                String.valueOf(id),
                "Manual QuikShipX publish by " + actor + ": " + response.outcome()
                        + (response.detail() == null ? "" : " — " + response.detail()));
        log.info("Manual QuikShipX publish for order {} by {}: {}", id, actor, response.outcome());
        return response;
    }
}
