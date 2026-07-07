package com.shifa.oms.notification;

/**
 * A delivery channel a lifecycle notification can travel over (design §5.1).
 *
 * <ul>
 *   <li>{@link #WHATSAPP} — a customer WhatsApp message (addressed to the order's
 *       {@code customer_mobile});</li>
 *   <li>{@link #EMAIL} — a customer milestone email (addressed to the order's
 *       {@code customer_email}); only present for the key milestones
 *       {@code APPROVED}, {@code DISPATCHED}, {@code DELIVERED} (Req 13.5, 13.6);</li>
 *   <li>{@link #IN_APP} — a staff in-app notification (addressed to a role or to
 *       the creating salesperson).</li>
 * </ul>
 */
public enum NotificationChannel {

    /** Customer WhatsApp message. */
    WHATSAPP,

    /** Customer milestone email. */
    EMAIL,

    /** Staff in-app notification (role- or user-addressed). */
    IN_APP
}
