package com.shifa.oms.notification;

import java.util.Objects;

/**
 * A single notification the {@code NotificationMatrix} specifies for a lifecycle
 * event: a {@link NotificationChannel} paired with the {@link NotificationRecipient}
 * it is addressed to (design §5.1).
 *
 * <p>Immutable value type; a lifecycle event maps to a {@code Set<NotificationSpec>},
 * and the workflow enqueues exactly that set (Property 16).
 *
 * @param channel   the delivery channel
 * @param recipient the addressee
 */
public record NotificationSpec(NotificationChannel channel, NotificationRecipient recipient) {

    public NotificationSpec {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(recipient, "recipient");
    }

    /** Convenience factory. */
    public static NotificationSpec of(NotificationChannel channel, NotificationRecipient recipient) {
        return new NotificationSpec(channel, recipient);
    }
}
