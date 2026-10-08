package com.shifa.oms.geo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A selectable delivery state / union territory, mapped to the
 * {@code delivery_states} table (V29). Drives the state typeahead on the
 * salesperson New Order form and is managed from the admin Settings page.
 *
 * <p>{@link #active} toggles whether the state appears in the order-entry picker
 * without losing history; {@link #sortOrder} gives an optional manual ordering
 * (the list falls back to name order). {@code created_at}/{@code updated_at} are
 * DB-managed and therefore not written on insert/update, matching the
 * {@code AppSettings}/{@code OrderEntity} conventions.
 */
@Entity
@Table(name = "delivery_states")
public class DeliveryState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected DeliveryState() {
        // Required by JPA.
    }

    public DeliveryState(String name, boolean active, int sortOrder) {
        this.name = name;
        this.active = active;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
