package com.shifa.oms.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A manually recorded business expense, mapped to the {@code expenses} table
 * (Feature C3): a category (rent, salaries, marketing, ...), an amount, and the
 * date it was incurred. Expenses feed the profit &amp; loss report.
 *
 * <p>{@code created_by} is a best-effort actor snapshot (nullable);
 * {@code created_at} is filled before the first insert so the DB never receives
 * a null (mirroring {@code OrderReturn}). {@code incurred_on} is the business
 * date used for window filtering / summing (distinct from the record's
 * {@code created_at}).
 */
@Entity
@Table(name = "expenses")
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category", nullable = false, length = 100)
    private String category;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "incurred_on", nullable = false)
    private LocalDate incurredOn;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Expense() {
        // Required by JPA.
    }

    public Expense(String category, String description, BigDecimal amount,
                   LocalDate incurredOn, Long createdBy) {
        this.category = category;
        this.description = description;
        this.amount = amount;
        this.incurredOn = incurredOn;
        this.createdBy = createdBy;
    }

    /** Fills the creation timestamp before the first insert so the DB never receives a null. */
    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getIncurredOn() {
        return incurredOn;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
