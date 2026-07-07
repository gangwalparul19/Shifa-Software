package com.shifa.oms.courier;

import java.util.Optional;

/**
 * Abstraction over the external Courier API (design "Courier Integration").
 *
 * <p>There is no live courier API in local development, so the default backend
 * is {@link MockCourierClient} (deterministic fake AWBs, labels, and a way to
 * simulate tracking updates). A real HTTP implementation can be dropped in
 * behind this contract for the cloud deployment, selected via {@code app.courier.mode}.
 *
 * <p>Calls are side-effecting and may fail or time out; callers invoke them from
 * the outbox drainer with bounded retries so a slow courier never blocks the
 * synchronous packing flow (Req 12.1, 12.4).
 */
public interface CourierClient {

    /**
     * Requests an AWB and shipping label for a packed order (Req 12.1, 12.2).
     *
     * @param request the order details and COD amount
     * @return the assigned AWB, courier name, and estimated delivery
     * @throws CourierClientException on a courier error or timeout (Req 12.4)
     */
    CourierAssignmentResult assign(CourierAssignmentRequest request);

    /**
     * Polls the courier for the latest tracking status of an AWB, used by the
     * scheduled fallback that reconciles missed webhooks (Req 13.2).
     *
     * @param awb the AWB to poll
     * @return the latest tracking event if known, else empty
     */
    Optional<CourierTrackingEvent> pollLatest(String awb);
}
