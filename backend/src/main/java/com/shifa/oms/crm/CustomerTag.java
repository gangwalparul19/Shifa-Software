package com.shifa.oms.crm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A free-form segment/label attached to a customer (FEATURE-ROADMAP §1.4),
 * mapped to the {@code customer_tags} table (V34).
 *
 * <p>Customers are derived data keyed by {@code customer_mobile}, so a tag is
 * keyed by the same mobile rather than a customer FK. The pair
 * {@code (customer_mobile, tag)} is unique.
 */
@Entity
@Table(name = "customer_tags")
public class CustomerTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_mobile", nullable = false, length = 10)
    private String customerMobile;

    @Column(name = "tag", nullable = false, length = 40)
    private String tag;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected CustomerTag() {
        // Required by JPA.
    }

    public CustomerTag(String customerMobile, String tag, Long createdBy) {
        this.customerMobile = customerMobile;
        this.tag = tag;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getCustomerMobile() {
        return customerMobile;
    }

    public String getTag() {
        return tag;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
