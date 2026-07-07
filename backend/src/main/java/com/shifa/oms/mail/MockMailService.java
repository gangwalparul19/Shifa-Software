package com.shifa.oms.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic, in-memory {@link MailService} for local development and tests
 * (Feature E3), mirroring {@link com.shifa.oms.notification.MockWhatsAppClient}.
 *
 * <p><strong>Seeing sends.</strong> Every successful send is logged at INFO and
 * appended to an in-memory list, readable via {@link #sentMessages()} — so a
 * demo or test can confirm exactly what was "sent" to whom.
 *
 * <p><strong>Simulating a failure.</strong> Call {@link #simulateFailure(boolean)}
 * to make subsequent sends throw a {@link MailException}, so the failure path can
 * be exercised without a real SMTP server.
 */
@Component
@ConditionalOnProperty(prefix = "app.mail", name = "mode", havingValue = "MOCK", matchIfMissing = true)
public class MockMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(MockMailService.class);

    private final List<MailMessage> sent = new CopyOnWriteArrayList<>();
    private final AtomicBoolean forceFailure = new AtomicBoolean(false);

    @Override
    public void send(MailMessage message) {
        if (forceFailure.get()) {
            throw new MailException("Simulated email send failure for subject '" + message.subject() + "'.");
        }
        sent.add(message);
        int textLen = message.body() == null ? 0 : message.body().length();
        if (message.hasHtml()) {
            log.info("Mock email sent to {} with subject '{}' ({} chars text, HTML present: {} chars)",
                    message.to(), message.subject(), textLen, message.htmlBody().length());
        } else {
            log.info("Mock email sent to {} with subject '{}' ({} chars text, no HTML body)",
                    message.to(), message.subject(), textLen);
        }
    }

    /**
     * Toggles forced-failure mode: while enabled, every {@link #send} throws a
     * {@link MailException} so the failure path can be demonstrated.
     *
     * @param fail {@code true} to make sends fail, {@code false} to restore normal sending
     */
    public void simulateFailure(boolean fail) {
        forceFailure.set(fail);
    }

    /** The messages successfully "sent" so far (defensive copy). */
    public List<MailMessage> sentMessages() {
        return List.copyOf(sent);
    }

    /** Clears the recorded sends (test/demo convenience). */
    public void clear() {
        sent.clear();
    }
}
