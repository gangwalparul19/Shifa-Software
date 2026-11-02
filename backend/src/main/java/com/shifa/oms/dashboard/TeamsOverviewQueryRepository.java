package com.shifa.oms.dashboard;

import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Read-only aggregates behind the admin "team-wise sales" overview
 * ({@link TeamsOverviewService}). Everything is grouped in SQL (by creator /
 * owner and status) so the service no longer loads every order and lead ever
 * created for every team and adds them up in Java. JPQL on
 * {@link OrderEntity}, so the soft-delete restriction on the entity (deleted
 * orders are hidden) applies exactly as it did for the native
 * {@code active = 1} finder this replaces. Callers must pass non-empty id
 * collections.
 */
public interface TeamsOverviewQueryRepository extends Repository<OrderEntity, Long> {

    /** Lifetime order count, value and customer-outstanding per creator and status. */
    @Query("""
            select o.createdBy as personId, o.orderStatus as orderStatus, count(o) as orderCount,
                   sum(o.totalAmount) as totalAmount, sum(o.customerOutstanding) as outstandingAmount
            from OrderEntity o
            where o.createdBy in :creatorIds
            group by o.createdBy, o.orderStatus
            """)
    List<CreatorStatusRow> orderTotalsByCreator(@Param("creatorIds") Collection<Long> creatorIds);

    /**
     * As {@link #orderTotalsByCreator} but only orders created in the half-open window
     * {@code [rangeStart, rangeEnd)} - the period the admin chose on the page. "Overall" is passed
     * as a very wide window, never as nulls.
     */
    @Query("""
            select o.createdBy as personId, o.orderStatus as orderStatus, count(o) as orderCount,
                   sum(o.totalAmount) as totalAmount, sum(o.customerOutstanding) as outstandingAmount
            from OrderEntity o
            where o.createdBy in :creatorIds
              and o.createdAt >= :rangeStart and o.createdAt < :rangeEnd
            group by o.createdBy, o.orderStatus
            """)
    List<CreatorStatusRow> orderTotalsByCreatorBetween(@Param("creatorIds") Collection<Long> creatorIds,
                                                       @Param("rangeStart") LocalDateTime rangeStart,
                                                       @Param("rangeEnd") LocalDateTime rangeEnd);

    /** Lead counts per owner and status (every status, WON and LOST included). */
    @Query("""
            select l.ownerUserId as ownerId, l.status as leadStatus, count(l) as leadCount
            from LeadEntity l
            where l.ownerUserId in :ownerIds
            group by l.ownerUserId, l.status
            """)
    List<OwnerLeadStatusRow> leadCountsByOwnerAndStatus(@Param("ownerIds") Collection<Long> ownerIds);

    /** Per owner, how many open leads are due or overdue for a follow-up on {@code today}. */
    @Query("""
            select l.ownerUserId as ownerId, count(l) as leadCount
            from LeadEntity l
            where l.ownerUserId in :ownerIds
              and l.followUpDate is not null and l.followUpDate <= :today
              and l.status not in (com.shifa.oms.lead.LeadStatus.WON, com.shifa.oms.lead.LeadStatus.LOST)
            group by l.ownerUserId
            """)
    List<OwnerCountRow> dueFollowUpCountsByOwner(@Param("ownerIds") Collection<Long> ownerIds,
                                                 @Param("today") LocalDate today);

    /**
     * The open leads due or overdue for a follow-up, most overdue first (oldest
     * follow-up date first, then newest-created first). The caller passes a page
     * request to cap how many come back per team.
     */
    @Query("""
            select l.id as leadId, l.customerName as customerName, l.customerMobile as customerMobile,
                   l.leadSource as leadSource, l.status as leadStatus, l.followUpDate as followUpDate,
                   l.ownerUserId as ownerId
            from LeadEntity l
            where l.ownerUserId in :ownerIds
              and l.followUpDate is not null and l.followUpDate <= :today
              and l.status not in (com.shifa.oms.lead.LeadStatus.WON, com.shifa.oms.lead.LeadStatus.LOST)
            order by l.followUpDate asc, l.createdAt desc, l.id desc
            """)
    List<DueLeadRow> dueFollowUps(@Param("ownerIds") Collection<Long> ownerIds,
                                  @Param("today") LocalDate today,
                                  Pageable pageable);

    interface CreatorStatusRow {
        Long getPersonId();

        OrderStatus getOrderStatus();

        Long getOrderCount();

        BigDecimal getTotalAmount();

        BigDecimal getOutstandingAmount();
    }

    interface OwnerLeadStatusRow {
        Long getOwnerId();

        LeadStatus getLeadStatus();

        Long getLeadCount();
    }

    interface OwnerCountRow {
        Long getOwnerId();

        Long getLeadCount();
    }

    interface DueLeadRow {
        Long getLeadId();

        String getCustomerName();

        String getCustomerMobile();

        LeadSource getLeadSource();

        LeadStatus getLeadStatus();

        LocalDate getFollowUpDate();

        Long getOwnerId();
    }
}
