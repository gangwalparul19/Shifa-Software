package com.shifa.oms.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shifa.oms.auth.Role;
import jakarta.annotation.PostConstruct;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.List;

/**
 * Sends browser Web Push notifications to staff devices (FEATURE-ROADMAP §8.3),
 * signed with VAPID.
 *
 * <p><strong>Config-gated.</strong> Sending only happens when both VAPID keys
 * are configured ({@code app.push.vapid.public-key} / {@code private-key}). When
 * unset (the default), every send is a silent no-op — subscriptions are still
 * stored harmlessly, and the in-app notification bell is unaffected. This mirrors
 * the mock/gated pattern used by mail/WhatsApp so the app runs with no external
 * setup, and real push can be switched on by dropping in a VAPID keypair.
 *
 * <p>Best-effort and never-throwing: a failed push must never break the business
 * operation (e.g. an order transition) that triggered the notification.
 */
@Service
public class WebPushService {

    private static final Logger log = LoggerFactory.getLogger(WebPushService.class);

    private final PushSubscriptionRepository repository;
    private final ObjectMapper objectMapper;
    private final String publicKey;
    private final String privateKey;
    private final String subject;

    private PushService pushService;

    public WebPushService(PushSubscriptionRepository repository,
                          ObjectMapper objectMapper,
                          @Value("${app.push.vapid.public-key:}") String publicKey,
                          @Value("${app.push.vapid.private-key:}") String privateKey,
                          @Value("${app.push.vapid.subject:mailto:admin@shifa.local}") String subject) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.subject = subject;
    }

    @PostConstruct
    void init() {
        if (!isEnabled()) {
            log.info("Web push disabled (no VAPID keys configured) — pushes are a no-op.");
            return;
        }
        try {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            PushService service = new PushService();
            service.setPublicKey(publicKey);
            service.setPrivateKey(privateKey);
            service.setSubject(subject);
            this.pushService = service;
            log.info("Web push enabled (VAPID configured).");
        } catch (Exception e) {
            this.pushService = null;
            log.warn("Failed to initialise web push; pushes disabled: {}", e.getMessage());
        }
    }

    /** Whether VAPID keys are configured (the public key is safe to expose to clients). */
    public boolean isEnabled() {
        return publicKey != null && !publicKey.isBlank()
                && privateKey != null && !privateKey.isBlank();
    }

    /** The VAPID public key clients need to subscribe (empty when disabled). */
    public String publicKey() {
        return publicKey == null ? "" : publicKey;
    }

    /** Sends a push to every device of a single user. Best-effort. */
    public void sendToUser(Long userId, String title, String body, String url) {
        if (pushService == null || userId == null) {
            return;
        }
        send(repository.findByUserId(userId), title, body, url);
    }

    /** Sends a push to every device of every active user in a role. Best-effort. */
    public void sendToRole(Role role, String title, String body, String url) {
        if (pushService == null || role == null) {
            return;
        }
        send(repository.findByUserRole(role), title, body, url);
    }

    private void send(List<PushSubscriptionEntity> subs, String title, String body, String url) {
        if (subs.isEmpty()) {
            return;
        }
        byte[] payload = payload(title, body, url);
        for (PushSubscriptionEntity sub : subs) {
            try {
                pushService.send(new Notification(
                        sub.getEndpoint(), sub.getP256dh(), sub.getAuthSecret(), payload));
            } catch (Exception e) {
                log.debug("Web push to endpoint failed (kept): {}", e.getMessage());
            }
        }
    }

    /**
     * Builds the JSON payload in the shape the Angular service worker renders
     * automatically: a top-level {@code notification} object with a {@code data.url}
     * used by the click handler to deep-link.
     */
    private byte[] payload(String title, String body, String url) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode notification = root.putObject("notification");
        notification.put("title", title == null ? "Shifa OMS" : title);
        notification.put("body", body == null ? "" : body);
        notification.put("icon", "/icons/shifa-icon.svg");
        notification.put("badge", "/icons/shifa-icon.svg");
        ObjectNode data = notification.putObject("data");
        data.put("url", url == null || url.isBlank() ? "/" : url);
        try {
            return objectMapper.writeValueAsBytes(root);
        } catch (Exception e) {
            return ("{\"notification\":{\"title\":\"Shifa OMS\"}}").getBytes(StandardCharsets.UTF_8);
        }
    }
}
