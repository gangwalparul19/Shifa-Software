package com.shifa.oms.announcement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A staff-wide announcement banner (FEATURE-ROADMAP §8.4), mapped to the
 * {@code staff_announcements} table (V35).
 *
 * <p>Posted by an admin and shown to every signed-in staff member while
 * {@link #active}. Severity is one of {@code info/success/warning/danger}
 * (the shared notification palette).
 */
@Entity
@Table(name = "staff_announcements")
public class Announcement {

    public static final String SEVERITY_INFO = "info";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "severity", nullable = false, length = 20)
    private String severity = SEVERITY_INFO;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * Pinned to the top of the home screen as a prominent card (V109). Independent of the thin
     * app-wide banner every active announcement already gets, and only shown while {@link #active}.
     */
    @Column(name = "show_on_dashboard", nullable = false)
    private boolean showOnDashboard = false;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected Announcement() {
        // Required by JPA.
    }

    public Announcement(String message, String severity, Long createdBy, String createdByName) {
        this.message = message;
        this.severity = severity;
        this.createdBy = createdBy;
        this.createdByName = createdByName;
        this.active = true;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getMessage() {
        return message;
    }

    public String getSeverity() {
        return severity;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isShowOnDashboard() {
        return showOnDashboard;
    }

    public void setShowOnDashboard(boolean showOnDashboard) {
        this.showOnDashboard = showOnDashboard;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public String getCreatedByName() {
        return createdByName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
