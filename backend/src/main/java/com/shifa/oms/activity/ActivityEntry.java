package com.shifa.oms.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One row of the unified CRM activity log (CRM expansion Phase 2, Task 9,
 * design &sect;2.1), mapped to {@code crm_activities} (V85).
 *
 * <p>An activity is an append-only touch on a lead and/or a customer (keyed by
 * mobile, matching Customer 360). It is the superset read model behind the
 * per-lead and per-customer timelines (Req 11) and the signal the missed
 * follow-up job checks "was the lead acted on since the reminder?" against
 * (Req 10). {@code created_at} is filled by the DB default (same convention as
 * {@link com.shifa.oms.lead.LeadEntity}), so it is never written on insert.
 */
@Entity
@Table(name = "crm_activities")
public class ActivityEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_type", nullable = false, length = 40)
    private String activityType;

    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "customer_mobile", length = 10)
    private String customerMobile;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_name", length = 150)
    private String actorName;

    @Column(name = "summary", nullable = false, length = 500)
    private String summary;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ActivityEntry() {
        // Required by JPA.
    }

    public ActivityEntry(String activityType, Long leadId, String customerMobile,
                         Long actorUserId, String actorName, String summary, String detail) {
        this.activityType = activityType;
        this.leadId = leadId;
        this.customerMobile = customerMobile;
        this.actorUserId = actorUserId;
        this.actorName = actorName;
        this.summary = summary;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public String getActivityType() {
        return activityType;
    }

    public Long getLeadId() {
        return leadId;
    }

    public String getCustomerMobile() {
        return customerMobile;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public String getActorName() {
        return actorName;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetail() {
        return detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
