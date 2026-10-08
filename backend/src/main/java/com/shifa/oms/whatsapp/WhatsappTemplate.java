package com.shifa.oms.whatsapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A customizable WhatsApp message template (V44), mapped to
 * {@code whatsapp_templates}.
 *
 * <p>Managers (ADMIN / ACCOUNTANT / TEAM_LEAD) create and edit these; any staff
 * member who can message a customer (order / customer screens) sees the active
 * ones as one-tap quick messages. The {@link #body} carries {@code {placeholder}}
 * tokens ({@code {name}}, {@code {orderCode}}, {@code {total}}, {@code {remaining}},
 * {@code {brand}}) that are substituted client-side against the order/customer in
 * context before the {@code wa.me} deep link is opened.
 */
@Entity
@Table(name = "whatsapp_templates")
public class WhatsappTemplate {

    public static final String DEFAULT_ICON = "ti-message-dots";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_key", nullable = false, length = 60, unique = true)
    private String templateKey;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    @Column(name = "icon", nullable = false, length = 40)
    private String icon = DEFAULT_ICON;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected WhatsappTemplate() {
        // Required by JPA.
    }

    public WhatsappTemplate(String templateKey, String title, String body, String icon,
                            int sortOrder, Long createdBy, String createdByName) {
        this.templateKey = templateKey;
        this.title = title;
        this.body = body;
        this.icon = (icon == null || icon.isBlank()) ? DEFAULT_ICON : icon;
        this.sortOrder = sortOrder;
        this.active = true;
        this.createdBy = createdBy;
        this.createdByName = createdByName;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = (icon == null || icon.isBlank()) ? DEFAULT_ICON : icon;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
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

    /** Stamps {@link #updatedAt}; call from the service after mutating fields. */
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
