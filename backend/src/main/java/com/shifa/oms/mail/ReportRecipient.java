package com.shifa.oms.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * An extra address that receives the admin report emails, mapped to {@code report_recipients} (V111).
 *
 * <p>Admin users with an email on their profile are included automatically; this table is for anyone
 * else: an admin whose profile has no email, or a person who is not a user at all. A row stays until
 * an admin removes it, and can be paused ({@code active = false}) without losing it.
 */
@Entity
@Table(name = "report_recipients")
public class ReportRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** Optional display name, for example the admin's name. */
    @Column(name = "label", length = 100)
    private String label;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by_name", length = 100)
    private String createdByName;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ReportRecipient() {
        // Required by JPA.
    }

    public ReportRecipient(String email, String label, Long createdById, String createdByName) {
        this.email = email;
        this.label = label;
        this.createdById = createdById;
        this.createdByName = createdByName;
        this.active = true;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getLabel() { return label; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Long getCreatedById() { return createdById; }
    public String getCreatedByName() { return createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
