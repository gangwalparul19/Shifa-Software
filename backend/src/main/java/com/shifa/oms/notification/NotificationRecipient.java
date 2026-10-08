package com.shifa.oms.notification;

import com.shifa.oms.auth.Role;

import java.util.Objects;

/**
 * The addressee of a single {@link NotificationSpec} in the {@code NotificationMatrix}
 * (design §5.1). A recipient is one of:
 *
 * <ul>
 *   <li>{@link Kind#CUSTOMER} — the order's customer (WhatsApp → {@code customer_mobile},
 *       email → {@code customer_email});</li>
 *   <li>{@link Kind#ROLE} — every active user holding a specific staff {@link Role}
 *       (in-app fan-out, Req 13.4);</li>
 *   <li>{@link Kind#SALESPERSON_CREATOR} — the specific salesperson who created the
 *       order (in-app, addressed by user id, Req 7.3).</li>
 * </ul>
 *
 * <p>Immutable value type; {@link #role()} is non-null only for {@link Kind#ROLE}.
 */
public record NotificationRecipient(Kind kind, Role role) {

    /** The kind of addressee this recipient targets. */
    public enum Kind {
        /** The order's customer. */
        CUSTOMER,
        /** Every active user of a staff role. */
        ROLE,
        /** The creating salesperson (addressed by user id). */
        SALESPERSON_CREATOR
    }

    public NotificationRecipient {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.ROLE) {
            Objects.requireNonNull(role, "role is required for a ROLE recipient");
        } else if (role != null) {
            throw new IllegalArgumentException("role must be null unless kind == ROLE");
        }
    }

    /** The order's customer. */
    public static NotificationRecipient customer() {
        return new NotificationRecipient(Kind.CUSTOMER, null);
    }

    /** Every active user holding {@code role} (in-app fan-out). */
    public static NotificationRecipient role(Role role) {
        return new NotificationRecipient(Kind.ROLE, role);
    }

    /** The creating salesperson (addressed by user id). */
    public static NotificationRecipient salespersonCreator() {
        return new NotificationRecipient(Kind.SALESPERSON_CREATOR, null);
    }
}
