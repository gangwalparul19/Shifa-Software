package com.shifa.oms.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A browser Web Push subscription for a staff user (FEATURE-ROADMAP §8.3),
 * mapped to {@code push_subscriptions} (V36). Holds the endpoint plus the two
 * client keys needed to encrypt a VAPID push.
 */
@Entity
@Table(name = "push_subscriptions")
public class PushSubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "endpoint", nullable = false, length = 500)
    private String endpoint;

    @Column(name = "p256dh", nullable = false, length = 255)
    private String p256dh;

    @Column(name = "auth_secret", nullable = false, length = 255)
    private String authSecret;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PushSubscriptionEntity() {
        // Required by JPA.
    }

    public PushSubscriptionEntity(Long userId, String endpoint, String p256dh, String authSecret) {
        this.userId = userId;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.authSecret = authSecret;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getP256dh() {
        return p256dh;
    }

    public String getAuthSecret() {
        return authSecret;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
