package com.shifa.oms.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A CRM to-do task, mapped to {@code tasks} (V86, CRM expansion Phase 2,
 * Task 10, design &sect;2.2). Owned by a CRM_User, optionally linked to a
 * lead/customer, with an optional due date/time, a priority, and completion
 * state (Req 8). {@code created_at}/{@code updated_at} are DB-filled (not
 * written on insert/update). {@link #remindedOn} is the "last reminded" marker
 * the {@code TaskReminderJob} uses to de-dup at most one reminder per due day.
 */
@Entity
@Table(name = "tasks")
public class TaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "customer_mobile", length = 10)
    private String customerMobile;

    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private TaskPriority priority = TaskPriority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private TaskStatus status = TaskStatus.OPEN;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "reminded_on")
    private LocalDate remindedOn;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected TaskEntity() {
        // Required by JPA.
    }

    public TaskEntity(Long ownerUserId, String title) {
        this.ownerUserId = ownerUserId;
        this.title = title;
        this.status = TaskStatus.OPEN;
        this.priority = TaskPriority.NORMAL;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getLeadId() {
        return leadId;
    }

    public void setLeadId(Long leadId) {
        this.leadId = leadId;
    }

    public String getCustomerMobile() {
        return customerMobile;
    }

    public void setCustomerMobile(String customerMobile) {
        this.customerMobile = customerMobile;
    }

    public LocalDateTime getDueAt() {
        return dueAt;
    }

    public void setDueAt(LocalDateTime dueAt) {
        this.dueAt = dueAt;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
        this.priority = priority;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public LocalDate getRemindedOn() {
        return remindedOn;
    }

    public void setRemindedOn(LocalDate remindedOn) {
        this.remindedOn = remindedOn;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
