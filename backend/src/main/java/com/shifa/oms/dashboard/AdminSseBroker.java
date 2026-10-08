package com.shifa.oms.dashboard;

import com.shifa.oms.auth.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process registry and fan-out for the staff Server-Sent Events stream
 * (design: "Real-time notifications").
 *
 * <p>Each connected staff member holds one long-lived {@link SseEmitter},
 * registered together with their {@link Role} and user id so events can be
 * delivered to the right audience:
 * <ul>
 *   <li>{@link #broadcastToAdmins(String, Object)} — admin-dashboard signals
 *       (live stats, activity, admin operational notifications) go only to
 *       connected admins, exactly as before;</li>
 *   <li>{@link #sendToRecipients(String, Object, Role, Long)} — a per-recipient
 *       notification (bell update) goes only to the emitters whose role or user
 *       id matches the notification's addressing, so a packing user never
 *       receives an accountant's notification.</li>
 * </ul>
 *
 * <p>SSE is server&rarr;client only and fans out from this single process, which
 * is sufficient for the small staff user base [Free-tier]. The broker holds no
 * persistence: the durable record is the {@code outbox}/{@code admin_notifications}
 * row; {@link OutboxSseRelay} uses {@link #hasActiveAdmins()} so nothing is lost
 * when no admin is watching.
 */
@Component
public class AdminSseBroker {

    private static final Logger log = LoggerFactory.getLogger(AdminSseBroker.class);

    /** Default stream lifetime before the client must reconnect (30 minutes). */
    private static final long DEFAULT_TIMEOUT_MS = 30L * 60L * 1000L;

    /** A connected staff emitter plus who it belongs to (for scoped delivery). */
    private record Connection(SseEmitter emitter, Role role, Long userId) {
    }

    private final List<Connection> connections = new CopyOnWriteArrayList<>();

    /**
     * Registers a new staff connection with its owner's role + user id, wiring the
     * lifecycle callbacks so it is removed from the registry on completion, timeout,
     * or error.
     *
     * @param role   the connecting staff member's role (never {@code null})
     * @param userId the connecting staff member's user id (may be {@code null})
     * @return the created {@link SseEmitter} to return from the controller
     */
    public SseEmitter register(Role role, Long userId) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT_MS);
        Connection connection = new Connection(emitter, role, userId);
        emitter.onCompletion(() -> connections.remove(connection));
        emitter.onTimeout(() -> {
            connections.remove(connection);
            emitter.complete();
        });
        emitter.onError(e -> connections.remove(connection));
        connections.add(connection);
        // A hello event opens the stream promptly so proxies flush headers.
        send("CONNECTED", java.util.Map.of("message", "event stream connected"), emitter);
        return emitter;
    }

    /** Whether at least one ADMIN is currently connected (drives the relay, Req 11.2). */
    public boolean hasActiveAdmins() {
        return connections.stream().anyMatch(c -> c.role() == Role.ADMIN);
    }

    /** Number of currently connected staff (diagnostics/tests). */
    public int activeCount() {
        return connections.size();
    }

    /**
     * Broadcasts a typed event to every connected ADMIN only (live stats, activity,
     * admin operational notifications). Non-admin connections never receive these.
     */
    public void broadcastToAdmins(String eventName, Object data) {
        for (Connection c : connections) {
            if (c.role() == Role.ADMIN) {
                send(eventName, data, c.emitter());
            }
        }
    }

    /**
     * Sends a typed event only to connections that match the given recipient
     * addressing: a specific user (when {@code recipientUserId} is set) and/or a
     * role (when {@code recipientRole} is set). ADMIN connections always receive it
     * too (admins see all notifications, mirroring the bell). A send never throws.
     */
    public void sendToRecipients(String eventName, Object data, Role recipientRole, Long recipientUserId) {
        for (Connection c : connections) {
            boolean matches = c.role() == Role.ADMIN
                    || (recipientUserId != null && recipientUserId.equals(c.userId()))
                    || (recipientRole != null && recipientRole == c.role());
            if (matches) {
                send(eventName, data, c.emitter());
            }
        }
    }

    private void send(String eventName, Object data, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException e) {
            // Client went away between the liveness check and the send.
            connections.removeIf(c -> c.emitter() == emitter);
            log.debug("Dropped a dead SSE emitter while sending {}: {}", eventName, e.getMessage());
        }
    }
}
