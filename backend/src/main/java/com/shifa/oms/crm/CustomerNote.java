package com.shifa.oms.crm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A staff-authored note on a customer's timeline (FEATURE-ROADMAP §1.1), mapped
 * to the {@code customer_notes} table (V34).
 *
 * <p>Customers are derived data keyed by {@code customer_mobile}, so a note is
 * keyed by that mobile. The author's id and a display-name snapshot are captured
 * so the timeline reads sensibly even if the author is later renamed/removed.
 */
@Entity
@Table(name = "customer_notes")
public class CustomerNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_mobile", nullable = false, length = 10)
    private String customerMobile;

    @Column(name = "note", nullable = false, length = 1000)
    private String note;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected CustomerNote() {
        // Required by JPA.
    }

    public CustomerNote(String customerMobile, String note, Long createdBy, String createdByName) {
        this.customerMobile = customerMobile;
        this.note = note;
        this.createdBy = createdBy;
        this.createdByName = createdByName;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getCustomerMobile() {
        return customerMobile;
    }

    public String getNote() {
        return note;
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
}
