package com.shifa.oms.integration.quikshipx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic in-process {@link QuikShipXClient}, active by default
 * ({@code app.quikshipx.mode=MOCK}). Mirrors the existing mock courier and mock
 * WhatsApp clients.
 *
 * <p>This is what lets the whole publication path — approval branch, outbox, drainer,
 * retry ladder, shipment persistence, admin UI — be exercised end to end with no
 * credentials and no network, including in production during the staged rollout before
 * the live secret is switched on.
 *
 * <p>Derived values are a pure function of the order reference, so a retry produces the
 * same identifiers and the publication-idempotence property holds here too.
 */
@Component
@ConditionalOnProperty(name = "app.quikshipx.mode", havingValue = "MOCK", matchIfMissing = true)
public class MockQuikShipXClient implements QuikShipXClient {

    private static final Logger log = LoggerFactory.getLogger(MockQuikShipXClient.class);

    private final QuikShipXProperties properties;

    /** Submissions seen, newest last. Exposed for local diagnostics and tests. */
    private final List<ShipmentSubmission> submissions = Collections.synchronizedList(new ArrayList<>());

    public MockQuikShipXClient(QuikShipXProperties properties) {
        this.properties = properties;
    }

    @Override
    public ShipmentAcceptance createShipment(ShipmentSubmission submission) {
        submissions.add(submission);
        String reference = submission.orderReference();

        // Log the REDACTED form: the body carries client_code / user_id / user_secret,
        // and a raw log line would leak them.
        log.info("MOCK QuikShipX create-order accepted reference={} items={} payMode={}",
                reference,
                submission.productDetails().size(),
                submission.shipmentDetails() == null ? "?" : submission.shipmentDetails().shipmentPayMode());

        String shipmentId = "QSX-" + reference;
        // A deterministic fake QuikShipX order id, mirroring their numeric order_id.
        String quikshipxOrderId = String.valueOf(100000 + Math.abs(reference.hashCode()) % 900000);
        String awb = "QSXAWB" + String.format("%010d", Math.abs(reference.hashCode()) % 1_000_000_000L);
        return new ShipmentAcceptance(
                reference,
                shipmentId,
                quikshipxOrderId,
                awb,
                "QuikShipX Partner",
                "https://quikshipx.com/track/" + awb,
                null, // No label URL: QuikShipX documents no label endpoint.
                properties.isTestSecret(),
                "{\"mock\":true,\"id\":\"" + shipmentId + "\",\"order_id\":" + quikshipxOrderId
                        + ",\"awb\":\"" + awb + "\"}");
    }

    @Override
    public QuikShipXStatusEvent fetchStatus(String shipmentReference) throws QuikShipXClientException {
        // Even the mock refuses: pretending to return a status would make the status
        // path look implemented when QuikShipX exposes no such endpoint.
        throw new QuikShipXClientException(
                "QuikShipX exposes no documented status-query operation; "
                        + "status mirroring stays disabled until one is confirmed.",
                false);
    }

    /** The submissions this mock has received, oldest first. */
    public List<ShipmentSubmission> submissions() {
        synchronized (submissions) {
            return List.copyOf(submissions);
        }
    }
}
