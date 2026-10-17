package com.shifa.oms.lead;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A managed Lead_Tag definition, mapped to the {@code lead_tags} table (CRM
 * expansion, design &sect;1.1). Tags are a managed vocabulary (not free strings)
 * so a later phase can scope / rename / retire them; the five call dispositions
 * (Not Pick, DND, Busy, Wrong Number, Call Back) are seeded by migration V83.
 *
 * <p>{@link #kind} distinguishes a seeded {@code DISPOSITION} from a {@code
 * CUSTOM} admin-created tag. {@code created_at} defaults in the DB and is not
 * written on insert/update (the {@code OrderEntity}/{@code LeadEntity} pattern).
 */
@Entity
@Table(name = "lead_tags")
public class LeadTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 40)
    private String name;

    @Column(name = "color", length = 16)
    private String color;

    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected LeadTag() {
        // Required by JPA.
    }

    public LeadTag(String name, String color, String kind, int sortOrder) {
        this.name = name;
        this.color = color;
        this.kind = kind;
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

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
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
}
