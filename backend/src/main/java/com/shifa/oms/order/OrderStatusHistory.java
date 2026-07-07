package com.shifa.oms.order;

import com.shifa.oms.statemachine.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A persisted status-history record, mapped to the {@code status_history} table
 * (Req 8.4). Exactly one row is written per accepted transition — plus one
 * synthetic creation row (with a {@code null} {@code from_status}) recording the
 * initial {@code Pending_Admin_Approval} status when the order is created.
 *
 * <p>Owned by {@link OrderEntity} through a unidirectional {@code @OneToMany}
 * with an {@code order_id} join column. {@code changed_at} defaults in the DB.
 */
@Entity
@Table(name = "status_history")
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private OrderStatus toStatus;

    @Column(name = "actor", nullable = false, length = 150)
    private String actor;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "changed_at", insertable = false, updatable = false)
    private LocalDateTime changedAt;

    protected OrderStatusHistory() {
        // Required by JPA.
    }

    public OrderStatusHistory(OrderStatus fromStatus, OrderStatus toStatus, String actor, String source) {
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.source = source;
    }

    public Long getId() {
        return id;
    }

    public OrderStatus getFromStatus() {
        return fromStatus;
    }

    public OrderStatus getToStatus() {
        return toStatus;
    }

    public String getActor() {
        return actor;
    }

    public String getSource() {
        return source;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }
}
