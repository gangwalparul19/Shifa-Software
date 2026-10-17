package com.shifa.oms.lead;

import com.shifa.oms.order.LeadSource;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The Lead aggregate root, mapped to the {@code leads} table (design &sect;3.2).
 * Owns its status history through a unidirectional {@code @OneToMany} with a
 * {@code lead_id} join column, so the whole aggregate persists atomically in one
 * transaction (Req 2.6, 7.5) — mirroring {@link com.shifa.oms.order.OrderEntity}.
 *
 * <p>The channel reuses the existing {@link LeadSource} enum (no new enum);
 * {@link #status} and {@link #lostReason} persist as their {@code @Enumerated}
 * STRING names. {@code created_at}/{@code updated_at} are filled by DB defaults
 * and are therefore not written on insert/update (following the
 * {@code OrderEntity} pattern). {@link #remindedOn} is the "last reminded"
 * marker the follow-up reminder job uses to de-dup at most one reminder per due
 * day (design &sect;Follow-up Reminders).
 */
@Entity
@Table(name = "leads")
public class LeadEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false, length = 120)
    private String customerName;

    @Column(name = "customer_mobile", length = 10)
    private String customerMobile;

    @Column(name = "customer_email", length = 150)
    private String customerEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "lead_source", nullable = false, length = 20)
    private LeadSource leadSource;

    @Column(name = "lead_source_note", length = 200)
    private String leadSourceNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LeadStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "lost_reason", length = 20)
    private LostReason lostReason;

    @Column(name = "lost_reason_note", length = 200)
    private String lostReasonNote;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @Column(name = "reminded_on")
    private LocalDate remindedOn;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /**
     * The original creator of the lead (CRM expansion, design &sect;1.1). Kept
     * separate from {@link #ownerUserId} (the current assignee) so a reassignment
     * never loses who created the lead (Req 1.1). Backfilled to the owner for
     * leads that predate this column.
     */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    /**
     * Whether an ADMIN created/uploaded this lead — the permanent "Lead by Admin"
     * marker (Req 3). Defaults to {@code false} (user-captured).
     */
    @Column(name = "created_by_admin", nullable = false)
    private boolean createdByAdmin;

    /**
     * Optional time-of-day for the follow-up (Req 6). {@code null} means the
     * configured default time-of-day applies, preserving today's date-only
     * behaviour for existing leads.
     */
    @Column(name = "follow_up_time")
    private LocalTime followUpTime;

    /** When the current assignee ({@link #ownerUserId}) was set (Req 1.2). */
    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    /**
     * Optional shipping address captured with the lead (CRM expansion, Req 7).
     * Kept nullable so existing leads are unaffected; when present it pre-fills
     * the lead-to-order conversion so the salesperson need not re-type it.
     */
    @Column(name = "address_line", length = 250)
    private String addressLine;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "postal_code", length = 10)
    private String postalCode;

    @Column(name = "converted_order_id")
    private Long convertedOrderId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_id", nullable = false)
    @OrderBy("id ASC")
    private List<LeadStatusHistory> statusHistory = new ArrayList<>();

    protected LeadEntity() {
        // Required by JPA.
    }

    public LeadEntity(String customerName, LeadSource leadSource, Long ownerUserId) {
        this.customerName = customerName;
        this.leadSource = leadSource;
        this.ownerUserId = ownerUserId;
        this.status = LeadStatus.INITIAL;
    }

    /** Appends a status-history row to the aggregate. */
    public void addStatusHistory(LeadStatusHistory entry) {
        this.statusHistory.add(entry);
    }

    public Long getId() {
        return id;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerMobile() {
        return customerMobile;
    }

    public void setCustomerMobile(String customerMobile) {
        this.customerMobile = customerMobile;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
    }

    public LeadSource getLeadSource() {
        return leadSource;
    }

    public void setLeadSource(LeadSource leadSource) {
        this.leadSource = leadSource;
    }

    public String getLeadSourceNote() {
        return leadSourceNote;
    }

    public void setLeadSourceNote(String leadSourceNote) {
        this.leadSourceNote = leadSourceNote;
    }

    public LeadStatus getStatus() {
        return status;
    }

    public void setStatus(LeadStatus status) {
        this.status = status;
    }

    public LostReason getLostReason() {
        return lostReason;
    }

    public void setLostReason(LostReason lostReason) {
        this.lostReason = lostReason;
    }

    public String getLostReasonNote() {
        return lostReasonNote;
    }

    public void setLostReasonNote(String lostReasonNote) {
        this.lostReasonNote = lostReasonNote;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDate getFollowUpDate() {
        return followUpDate;
    }

    public void setFollowUpDate(LocalDate followUpDate) {
        this.followUpDate = followUpDate;
    }

    public LocalDate getRemindedOn() {
        return remindedOn;
    }

    public void setRemindedOn(LocalDate remindedOn) {
        this.remindedOn = remindedOn;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Long getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(Long createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public boolean isCreatedByAdmin() {
        return createdByAdmin;
    }

    public void setCreatedByAdmin(boolean createdByAdmin) {
        this.createdByAdmin = createdByAdmin;
    }

    public LocalTime getFollowUpTime() {
        return followUpTime;
    }

    public void setFollowUpTime(LocalTime followUpTime) {
        this.followUpTime = followUpTime;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(LocalDateTime assignedAt) {
        this.assignedAt = assignedAt;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public void setAddressLine(String addressLine) {
        this.addressLine = addressLine;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public Long getConvertedOrderId() {
        return convertedOrderId;
    }

    public void setConvertedOrderId(Long convertedOrderId) {
        this.convertedOrderId = convertedOrderId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public List<LeadStatusHistory> getStatusHistory() {
        return statusHistory;
    }
}
