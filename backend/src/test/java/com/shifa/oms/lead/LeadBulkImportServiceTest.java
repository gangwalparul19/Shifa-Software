package com.shifa.oms.lead;

import com.shifa.oms.audit.AuditEvent;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.lead.dto.LeadImportResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link LeadBulkImportService} (CRM expansion Req 2): mixed
 * valid/invalid rows, the row cap, assignee resolution (username/id/default),
 * tag attachment, and the admin-created marker.
 *
 * <p>Repository <em>interfaces</em> are Mockito-mocked over in-memory stores; the
 * concrete {@link AuditService} is a real recording subclass (Java 25 gotcha).
 */
class LeadBulkImportServiceTest {

    private static final AuthPrincipal ADMIN = new AuthPrincipal(1L, "admin", Role.ADMIN);

    @Test
    void createsValidRowsAndSkipsInvalidWithReasons() {
        Fixture f = new Fixture();
        f.tags.add(tag(5L, "DND"));
        f.users.add(user(42L, "priya", Role.SALESPERSON));
        String csv = String.join("\n",
                "name,mobile,email,source,assignee,tags,followUpDate",
                "Asha,9876543210,asha@example.com,WHATSAPP,priya,DND,2024-07-01",
                ",9876543210,,WHATSAPP,,,",                 // missing name → skip
                "Bad Mobile,123,,WHATSAPP,,,",             // bad mobile → skip
                "Bad Source,,,NOPE,,,",                    // invalid source → skip
                "Minimal,,,,,,");                          // only name → OTHER, assign to admin

        LeadImportResult result = f.service.importFile(
                csv.getBytes(StandardCharsets.UTF_8), "leads.csv", ADMIN);

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.skipped()).hasSize(3);
        assertThat(f.store).hasSize(2);
        // Every imported lead is admin-created (Req 3.1).
        assertThat(f.store).allMatch(LeadEntity::isCreatedByAdmin);

        LeadEntity asha = f.store.stream()
                .filter(l -> "Asha".equals(l.getCustomerName())).findFirst().orElseThrow();
        assertThat(asha.getOwnerUserId()).isEqualTo(42L);  // resolved assignee by username
        assertThat(f.assignments).extracting(LeadTagAssignment::getTagId).contains(5L); // tag applied

        LeadEntity minimal = f.store.stream()
                .filter(l -> "Minimal".equals(l.getCustomerName())).findFirst().orElseThrow();
        assertThat(minimal.getOwnerUserId()).isEqualTo(1L); // default to acting admin
    }

    @Test
    void resolvesAssigneeById() {
        Fixture f = new Fixture();
        f.users.add(user(42L, "priya", Role.SALESPERSON));
        String csv = "name,assignee\nAsha,42";

        LeadImportResult result = f.service.importFile(
                csv.getBytes(StandardCharsets.UTF_8), "leads.csv", ADMIN);

        assertThat(result.created()).isEqualTo(1);
        assertThat(f.store.get(0).getOwnerUserId()).isEqualTo(42L);
    }

    @Test
    void unknownAssigneeSkipsRow() {
        Fixture f = new Fixture();
        String csv = "name,assignee\nAsha,ghost";

        LeadImportResult result = f.service.importFile(
                csv.getBytes(StandardCharsets.UTF_8), "leads.csv", ADMIN);

        assertThat(result.created()).isZero();
        assertThat(result.skipped()).hasSize(1);
    }

    @Test
    void rejectsFileExceedingRowCap() {
        Fixture f = new Fixture(2); // cap = 2 data rows
        String csv = "name\nA\nB\nC";

        assertThatThrownBy(() -> f.service.importFile(
                csv.getBytes(StandardCharsets.UTF_8), "leads.csv", ADMIN))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maximum");
    }

    @Test
    void rejectsFileMissingNameColumn() {
        Fixture f = new Fixture();
        String csv = "mobile\n9876543210";

        assertThatThrownBy(() -> f.service.importFile(
                csv.getBytes(StandardCharsets.UTF_8), "leads.csv", ADMIN))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("name");
    }

    // --- Fixture ------------------------------------------------------------

    private static final class Fixture {
        final List<LeadEntity> store = new ArrayList<>();
        final List<LeadTag> tags = new ArrayList<>();
        final List<LeadTagAssignment> assignments = new ArrayList<>();
        final List<User> users = new ArrayList<>();
        final LeadBulkImportService service;

        Fixture() {
            this(2000);
        }

        Fixture(int maxRows) {
            LeadRepository leadRepo = mock(LeadRepository.class);
            lenient().when(leadRepo.save(any(LeadEntity.class))).thenAnswer(i -> {
                LeadEntity l = i.getArgument(0);
                if (l.getId() == null) {
                    setField(l, "id", (long) (store.size() + 100));
                }
                store.add(l);
                return l;
            });

            LeadTagRepository tagRepo = mock(LeadTagRepository.class);
            lenient().when(tagRepo.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(tags);

            LeadTagAssignmentRepository assignRepo = mock(LeadTagAssignmentRepository.class);
            lenient().when(assignRepo.save(any(LeadTagAssignment.class))).thenAnswer(i -> {
                LeadTagAssignment a = i.getArgument(0);
                assignments.add(a);
                return a;
            });
            lenient().when(assignRepo.existsByLeadIdAndTagId(anyLong(), anyLong())).thenReturn(false);

            UserRepository userRepo = mock(UserRepository.class);
            lenient().when(userRepo.findById(anyLong())).thenAnswer(i -> {
                Long id = i.getArgument(0);
                return users.stream().filter(u -> id.equals(u.getId())).findFirst();
            });
            lenient().when(userRepo.findByUsername(anyString())).thenAnswer(i -> {
                String name = i.getArgument(0);
                return users.stream().filter(u -> name.equals(u.getUsername())).findFirst();
            });

            AuditService audit = new AuditService(null, new CurrentUserService()) {
                @Override
                public AuditEvent record(String action, String entityType, String entityId,
                                         String summary) {
                    return null;
                }
            };

            this.service = new LeadBulkImportService(
                    leadRepo, tagRepo, assignRepo, userRepo, audit, maxRows);
        }
    }

    private static LeadTag tag(long id, String name) {
        LeadTag t = new LeadTag(name, null, "DISPOSITION", 1);
        setField(t, "id", id);
        return t;
    }

    private static User user(long id, String username, Role role) {
        User u = new User(username, "hash", role, username, true);
        setField(u, "id", id);
        return u;
    }

    private static void setField(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
