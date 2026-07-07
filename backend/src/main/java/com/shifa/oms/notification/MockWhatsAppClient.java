package com.shifa.oms.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic, in-memory {@link WhatsAppClient} for local development and tests
 * (design "[Free-tier]": there is no live Meta Cloud API locally).
 *
 * <p><strong>Seeing sends.</strong> Every successful send is logged and appended
 * to an in-memory list, readable via {@link #sentMessages()} — so a demo or test
 * can confirm exactly which template + parameters were "sent" to which customer.
 *
 * <p><strong>Simulating a failure (Req 14.4).</strong> There are two ways to make
 * a send fail so the failure/retry/flag path can be demonstrated:
 * <ul>
 *   <li>call {@link #simulateFailure(boolean)} to make the next sends throw; or</li>
 *   <li>send a message whose {@code order_id} parameter contains {@code FAIL}
 *       (case-insensitive) — no external state needed, mirroring the mock courier
 *       client's {@code FAIL} token.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.whatsapp", name = "mode", havingValue = "MOCK", matchIfMissing = true)
public class MockWhatsAppClient implements WhatsAppClient {

    private static final Logger log = LoggerFactory.getLogger(MockWhatsAppClient.class);

    /** Parameter values containing this token trigger a simulated send failure. */
    private static final String FAIL_TOKEN = "FAIL";

    private final List<WhatsAppMessage> sent = new CopyOnWriteArrayList<>();
    private final AtomicBoolean forceFailure = new AtomicBoolean(false);

    @Override
    public void send(WhatsAppMessage message) {
        if (forceFailure.get() || containsFailToken(message)) {
            throw new WhatsAppClientException(
                    "Simulated WhatsApp send failure for template " + message.templateName() + ".");
        }
        sent.add(message);
        log.info("Mock WhatsApp sent template '{}' to {} with params {}",
                message.templateName(), message.recipientMobile(), message.parameters());
    }

    /**
     * Toggles forced-failure mode: while enabled, every {@link #send} throws a
     * {@link WhatsAppClientException} so the retry/flag path can be demonstrated.
     *
     * @param fail {@code true} to make sends fail, {@code false} to restore normal sending
     */
    public void simulateFailure(boolean fail) {
        forceFailure.set(fail);
    }

    /** The messages successfully "sent" so far (defensive copy). */
    public List<WhatsAppMessage> sentMessages() {
        return List.copyOf(sent);
    }

    /** Clears the recorded sends (test/demo convenience). */
    public void clear() {
        sent.clear();
    }

    private boolean containsFailToken(WhatsAppMessage message) {
        return message.parameters().stream()
                .anyMatch(p -> p.value() != null
                        && p.value().toUpperCase(Locale.ROOT).contains(FAIL_TOKEN));
    }
}
