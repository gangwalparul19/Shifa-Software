package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;

import java.util.Objects;

/**
 * The principal that triggers an order status transition (design §2.2, §4.2):
 * either a human staff user carrying a {@link Role}, or the synthetic
 * {@code SYSTEM} actor behind automatic courier-driven edges
 * (assignment drainer / webhook / poller, Req 15.5).
 *
 * <p>An {@code Actor} carries just what
 * {@link OrderWorkflowService#applyTransition(OrderEntity, com.shifa.oms.statemachine.OrderStatus, Actor)}
 * needs to authorize and record a transition:
 * <ul>
 *   <li>a {@link Role} for a human actor (used by
 *       {@link com.shifa.oms.statemachine.TransitionAuthority}); {@code null}
 *       for {@code SYSTEM};</li>
 *   <li>the {@code name} recorded as the {@code status_history} / audit actor
 *       (a username, or {@code "COURIER_API"} / {@code "SYSTEM"});</li>
 *   <li>the {@code source} recorded on the history row (e.g. {@code ADMIN},
 *       {@code PACKING}, {@code COURIER}, {@code SYSTEM});</li>
 *   <li>a {@code system} flag that selects
 *       {@link com.shifa.oms.statemachine.TransitionAuthority#assertSystemAuthorized}
 *       over the role-based check.</li>
 * </ul>
 *
 * <p>Immutable; safe to pass across module boundaries.
 */
public final class Actor {

    /** The staff role of a human actor; {@code null} for the {@code SYSTEM} actor. */
    private final Role role;
    /** The actor label recorded on the history/audit row (never {@code null}). */
    private final String name;
    /** The transition source recorded on the history row (never {@code null}). */
    private final String source;
    /** Whether this is the automatic {@code SYSTEM} actor. */
    private final boolean system;

    private Actor(Role role, String name, String source, boolean system) {
        this.role = role;
        this.name = Objects.requireNonNull(name, "name");
        this.source = Objects.requireNonNull(source, "source");
        this.system = system;
    }

    /**
     * A human actor derived from the authenticated principal, recording the
     * given {@code source} on the history row (e.g. {@code ADMIN}, {@code PACKING}).
     */
    public static Actor user(AuthPrincipal principal, String source) {
        Objects.requireNonNull(principal, "principal");
        return new Actor(principal.role(), principal.username(), source, false);
    }

    /** A human actor from an explicit username + role (e.g. tests / non-request contexts). */
    public static Actor user(String name, Role role, String source) {
        return new Actor(Objects.requireNonNull(role, "role"), name, source, false);
    }

    /**
     * The automatic {@code SYSTEM} actor behind courier-driven edges (Req 15.5),
     * recording the given actor label (e.g. {@code "COURIER_API"}) and source.
     */
    public static Actor system(String name, String source) {
        return new Actor(null, name, source, true);
    }

    /** Whether this is the automatic {@code SYSTEM} actor. */
    public boolean isSystem() {
        return system;
    }

    /** The staff role of a human actor, or {@code null} for {@code SYSTEM}. */
    public Role role() {
        return role;
    }

    /** The actor label recorded on the status-history / audit row. */
    public String name() {
        return name;
    }

    /** The transition source recorded on the status-history row. */
    public String source() {
        return source;
    }
}
