package com.shifa.oms.lead;

import com.shifa.oms.audit.AuditEvent;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.SalespersonScopeResolver;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Shared wiring for the {@link LeadService} property tests (Properties 3, 4, 5, 6, 8).
 *
 * <p>Builds a real {@link LeadService} over a Mockito-mocked {@link LeadRepository}
 * <em>interface</em> whose finders are answered faithfully from an in-memory
 * store (so the repository behaves like the scoping / due / search SQL), a real
 * {@link SalespersonScopeResolver}, and a recording subclass of the concrete
 * {@link AuditService} that counts writes. This honours the Java 25 runtime
 * gotcha: only the repository <em>interface</em> is mocked; the concrete
 * {@code AuditService} is a real recording instance, never a Mockito mock.
 */
final class LeadServiceTestSupport {

    private LeadServiceTestSupport() {
    }

    /** A recording {@link AuditService} that counts audit writes without a database. */
    static AuditService recordingAudit(AtomicInteger counter) {
        return new AuditService(null, new CurrentUserService()) {
            @Override
            public AuditEvent record(String action, String entityType, String entityId, String summary) {
                counter.incrementAndGet();
                return null;
            }

            @Override
            public AuditEvent record(Long actorUserId, String actorUsername, String action,
                                     String entityType, String entityId, String summary) {
                counter.incrementAndGet();
                return null;
            }
        };
    }

    /**
     * A {@link LeadRepository} mock backed by {@code store}: {@code save} appends
     * (assigning a sequential id when absent) and every finder replays the
     * production scoping / due / search semantics over the store, so a service
     * test genuinely exercises the wiring it computes (owner constraint, term,
     * status/source filter, due predicate).
     */
    static LeadRepository inMemoryRepository(List<LeadEntity> store) {
        LeadRepository repo = mock(LeadRepository.class);
        AtomicInteger seq = new AtomicInteger(1000);

        lenient().when(repo.save(any(LeadEntity.class))).thenAnswer(inv -> {
            LeadEntity lead = inv.getArgument(0);
            if (lead.getId() == null) {
                setId(lead, (long) seq.incrementAndGet());
            }
            if (store.stream().noneMatch(l -> l == lead)) {
                store.add(lead);
            }
            return lead;
        });

        lenient().when(repo.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return store.stream().filter(l -> id.equals(l.getId())).findFirst();
        });

        lenient().when(repo.findByIdAndOwnerUserId(anyLong(), anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            Long owner = inv.getArgument(1);
            return store.stream()
                    .filter(l -> id.equals(l.getId()) && owner.equals(l.getOwnerUserId()))
                    .findFirst();
        });

        lenient().when(repo.findAllScoped(nullable(Long.class))).thenAnswer(inv -> {
            Long owner = inv.getArgument(0);
            return scoped(store, owner);
        });

        lenient().when(repo.search(nullable(Long.class), nullable(String.class),
                nullable(String.class), nullable(String.class))).thenAnswer(inv -> {
            Long owner = inv.getArgument(0);
            String term = inv.getArgument(1);
            String status = inv.getArgument(2);
            String source = inv.getArgument(3);
            List<LeadEntity> result = new ArrayList<>();
            for (LeadEntity l : scoped(store, owner)) {
                if (term != null) {
                    String t = term.toLowerCase();
                    boolean hit = (l.getCustomerName() != null
                            && l.getCustomerName().toLowerCase().contains(t))
                            || (l.getCustomerMobile() != null && l.getCustomerMobile().contains(term));
                    if (!hit) {
                        continue;
                    }
                }
                if (status != null && !status.equals(l.getStatus().name())) {
                    continue;
                }
                if (source != null && !source.equals(l.getLeadSource().name())) {
                    continue;
                }
                result.add(l);
            }
            return result;
        });

        lenient().when(repo.findDueFollowUps(nullable(Long.class), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    Long owner = inv.getArgument(0);
                    LocalDate today = inv.getArgument(1);
                    List<LeadEntity> result = new ArrayList<>();
                    for (LeadEntity l : scoped(store, owner)) {
                        if (LeadService.isDueFollowUp(l.getStatus(), l.getFollowUpDate(), today)) {
                            result.add(l);
                        }
                    }
                    return result;
                });

        return repo;
    }

    /** A real {@link LeadService} over the in-memory repo and a recording audit. */
    static LeadService service(LeadRepository repo, AtomicInteger auditCount, Clock clock) {
        return new LeadService(repo, new SalespersonScopeResolver(), recordingAudit(auditCount),
                new NoOpOrderService(), clock);
    }

    /**
     * A real {@link LeadService} whose convert path delegates to the supplied
     * {@link com.shifa.oms.order.OrderService} (a recording subclass), for the
     * convert property test. Honours the Java 25 gotcha: the collaborator is a
     * real recording instance, never a Mockito mock of a concrete class.
     */
    static LeadService service(LeadRepository repo, AtomicInteger auditCount,
                               com.shifa.oms.order.OrderService orderService, Clock clock) {
        return new LeadService(repo, new SalespersonScopeResolver(), recordingAudit(auditCount),
                orderService, clock);
    }

    /** A no-op {@link com.shifa.oms.order.OrderService} for tests that never convert. */
    static final class NoOpOrderService extends com.shifa.oms.order.OrderService {
        NoOpOrderService() {
            super(null, null, null, null, null, null, null, null);
        }
    }

    private static List<LeadEntity> scoped(List<LeadEntity> store, Long owner) {
        List<LeadEntity> result = new ArrayList<>();
        for (LeadEntity l : store) {
            if (owner == null || owner.equals(l.getOwnerUserId())) {
                result.add(l);
            }
        }
        return result;
    }

    /** A lead fixture with an assigned id, owner, status and optional follow-up date. */
    static LeadEntity lead(long id, String name, com.shifa.oms.order.LeadSource source,
                           long ownerUserId, LeadStatus status, LocalDate followUpDate) {
        LeadEntity lead = new LeadEntity(name, source, ownerUserId);
        lead.setStatus(status);
        lead.setFollowUpDate(followUpDate);
        setId(lead, id);
        return lead;
    }

    static Optional<LeadEntity> byId(List<LeadEntity> store, long id) {
        return store.stream().filter(l -> Long.valueOf(id).equals(l.getId())).findFirst();
    }

    static void setId(LeadEntity lead, long id) {
        try {
            Field field = LeadEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(lead, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to set lead id in test fixture", e);
        }
    }
}
