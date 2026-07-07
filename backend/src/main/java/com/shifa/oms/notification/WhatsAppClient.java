package com.shifa.oms.notification;

/**
 * Abstraction over the WhatsApp Business API (Meta Cloud) for sending
 * pre-approved template messages (design "Notification Service (WhatsApp)").
 *
 * <p>There is no live Meta API in local development, so the default backend is
 * {@link MockWhatsAppClient} (records what it "sent" and can simulate a send
 * failure). A real HTTP implementation can be dropped in behind this contract
 * for the cloud deployment, selected via {@code app.whatsapp.mode}.
 *
 * <p>Sends are side-effecting and may fail or time out; callers invoke them from
 * the WhatsApp outbox drainer with bounded retries, so a slow or unavailable
 * Meta API never blocks the courier status flow (Req 14.1, 14.4).
 */
public interface WhatsAppClient {

    /**
     * Sends a resolved, pre-approved template message to the customer (Req 14.1,
     * 14.2, 14.3).
     *
     * @param message the fully-resolved template message
     * @throws WhatsAppClientException on a send error, rejection, or timeout (Req 14.4)
     */
    void send(WhatsAppMessage message);
}
