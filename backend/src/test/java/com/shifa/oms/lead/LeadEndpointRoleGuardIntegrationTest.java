package com.shifa.oms.lead;

import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.JwtAuthenticationFilter;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.lead.dto.CreateLeadRequest;
import com.shifa.oms.lead.dto.LeadConvertRequest;
import com.shifa.oms.lead.dto.LeadResponse;
import com.shifa.oms.lead.dto.LeadStatusChangeRequest;
import com.shifa.oms.lead.dto.LeadSummaryResponse;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint role-guard integration test for the lead API (task 4.2, design §API,
 * §Testing; Req 7.2). Mirrors {@code EndpointRoleGuardIntegrationTest}: it drives
 * the real {@link LeadController} through {@link MockMvc} with method security
 * ({@code @PreAuthorize}) active, and asserts the backend enforces the role
 * matrix independently of the Angular route guards.
 *
 * <p>All lead endpoints share the class-level
 * {@code @PreAuthorize("hasAnyRole('SALESPERSON','ADMIN')")}: a
 * {@code SALESPERSON} or {@code ADMIN} call passes the guard (2xx), while
 * {@code PACKING_USER} / {@code ACCOUNTANT} are rejected with 403. A dedicated
 * convert test also asserts the response links a WON order.
 *
 * <p>The HTTP filter chain is permit-all so the ONLY authorization decision under
 * test is the method-level guard; callers are authenticated with a real
 * {@link AuthPrincipal}. The controller's {@link LeadService} collaborator is a
 * lightweight recording subclass (no Mockito mock of a concrete class, per the
 * Java 25 runtime gotcha).
 */
@WebMvcTest(controllers = LeadController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@Import(LeadEndpointRoleGuardIntegrationTest.TestSecurityAndStubs.class)
class LeadEndpointRoleGuardIntegrationTest {

    private static final List<Role> OPERATIONAL_ROLES = List.of(
            Role.ADMIN, Role.SALESPERSON, Role.PACKING_USER, Role.ACCOUNTANT);

    private static final List<Role> LEAD_ROLES = List.of(Role.SALESPERSON, Role.ADMIN);

    private static final String CAPTURE_JSON = """
            {"customerName":"Asha","leadSource":"WHATSAPP"}
            """;

    private static final String STATUS_JSON = """
            {"toStatus":"CONTACTED"}
            """;

    private static final String CONVERT_JSON = """
            {"addressLine":"12 MG Road","city":"Pune","state":"Maharashtra","postalCode":"411001",
             "items":[{"productId":1,"quantity":1}],"amountReceived":"100.00"}
            """;

    @Autowired
    private MockMvc mvc;

    @Test
    void captureIsSalespersonOrAdmin() throws Exception {
        assertRoleMatrix(post("/api/leads")
                .contentType(MediaType.APPLICATION_JSON).content(CAPTURE_JSON), LEAD_ROLES);
    }

    @Test
    void listIsSalespersonOrAdmin() throws Exception {
        assertRoleMatrix(get("/api/leads"), LEAD_ROLES);
    }

    @Test
    void pipelineIsSalespersonOrAdmin() throws Exception {
        assertRoleMatrix(get("/api/leads/pipeline"), LEAD_ROLES);
    }

    @Test
    void dueFollowUpsIsSalespersonOrAdmin() throws Exception {
        assertRoleMatrix(get("/api/leads/follow-ups/due"), LEAD_ROLES);
    }

    @Test
    void detailIsSalespersonOrAdmin() throws Exception {
        assertRoleMatrix(get("/api/leads/5"), LEAD_ROLES);
    }

    @Test
    void statusChangeIsSalespersonOrAdmin() throws Exception {
        assertRoleMatrix(post("/api/leads/5/status")
                .contentType(MediaType.APPLICATION_JSON).content(STATUS_JSON), LEAD_ROLES);
    }

    @Test
    void editIsSalespersonOrAdmin() throws Exception {
        assertRoleMatrix(put("/api/leads/5")
                .contentType(MediaType.APPLICATION_JSON).content(CAPTURE_JSON), LEAD_ROLES);
    }

    @Test
    void convertIsSalespersonOrAdmin() throws Exception {
        assertRoleMatrix(post("/api/leads/5/convert")
                .contentType(MediaType.APPLICATION_JSON).content(CONVERT_JSON), LEAD_ROLES);
    }

    @Test
    void reportsAreSalespersonOrAdmin() throws Exception {
        assertRoleMatrix(get("/api/leads/reports/conversion"), LEAD_ROLES);
    }

    /** Convert returns the lead marked WON with the created order linked (Req 4.2, 4.3). */
    @Test
    void convertLinksAWonOrder() throws Exception {
        mvc.perform(post("/api/leads/5/convert")
                        .contentType(MediaType.APPLICATION_JSON).content(CONVERT_JSON)
                        .with(authFor(Role.SALESPERSON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WON"))
                .andExpect(jsonPath("$.convertedOrderId").value(9001));
    }

    // --- Harness ------------------------------------------------------------

    private void assertRoleMatrix(MockHttpServletRequestBuilder request, List<Role> allowed)
            throws Exception {
        for (Role role : OPERATIONAL_ROLES) {
            if (allowed.contains(role)) {
                mvc.perform(request.with(authFor(role)))
                        .andExpect(status().is2xxSuccessful());
            } else {
                mvc.perform(request.with(authFor(role)))
                        .andExpect(status().isForbidden());
            }
        }
    }

    private static RequestPostProcessor authFor(Role role) {
        AuthPrincipal principal = new AuthPrincipal(1L, "user-" + role.name().toLowerCase(), role);
        var token = new UsernamePasswordAuthenticationToken(
                principal, "n/a", List.of(new SimpleGrantedAuthority(role.authority())));
        return authentication(token);
    }

    // --- Test wiring --------------------------------------------------------

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityAndStubs {

        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        @Bean
        CurrentUserService currentUserService() {
            return new CurrentUserService();
        }

        @Bean
        LeadService leadService() {
            return new StubLeadService();
        }
    }

    /** Canned lead results so a permitted call passes the guard and yields 2xx. */
    static class StubLeadService extends LeadService {
        StubLeadService() {
            super(mock(LeadRepository.class), new SalespersonScopeResolver(),
                    new AuditService(null, new CurrentUserService()), new NoOpOrderService());
        }

        private static LeadResponse sample(LeadStatus status, Long convertedOrderId) {
            LeadEntity lead = new LeadEntity("Asha", LeadSource.WHATSAPP, 1L);
            lead.setStatus(status);
            lead.setConvertedOrderId(convertedOrderId);
            return LeadResponse.from(lead);
        }

        @Override
        public LeadResponse capture(CreateLeadRequest request, AuthPrincipal actor) {
            return sample(LeadStatus.NEW, null);
        }

        @Override
        public List<LeadSummaryResponse> list(String q, LeadStatus status, LeadSource source,
                                              AuthPrincipal actor) {
            return List.of();
        }

        @Override
        public Map<LeadStatus, Long> pipelineCounts(AuthPrincipal actor) {
            return Map.of();
        }

        @Override
        public List<LeadSummaryResponse> dueFollowUps(AuthPrincipal actor) {
            return List.of();
        }

        @Override
        public LeadResponse detail(Long id, AuthPrincipal actor) {
            return sample(LeadStatus.NEW, null);
        }

        @Override
        public LeadResponse transition(Long id, LeadStatus toStatus, LostReason lostReason,
                                       String lostReasonNote, AuthPrincipal actor) {
            return sample(toStatus, null);
        }

        @Override
        public LeadResponse setFollowUp(Long id, LocalDate followUpDate, AuthPrincipal actor) {
            return sample(LeadStatus.NEW, null);
        }

        @Override
        public LeadResponse edit(Long id, CreateLeadRequest request, AuthPrincipal actor) {
            return sample(LeadStatus.NEW, null);
        }

        @Override
        public LeadResponse convert(Long id, LeadConvertRequest request, AuthPrincipal actor) {
            // A linked, WON lead: the converted order id is set on success (Req 4.2, 4.3).
            return sample(LeadStatus.WON, 9001L);
        }

        @Override
        public com.shifa.oms.lead.dto.LeadReports.ConversionReport reportConversion(
                LocalDate from, LocalDate to, AuthPrincipal actor) {
            return new com.shifa.oms.lead.dto.LeadReports.ConversionReport(List.of(), List.of());
        }
    }

    /** A no-op {@link OrderService} — the stubbed convert never touches it. */
    static final class NoOpOrderService extends OrderService {
        NoOpOrderService() {
            super(null, null, null, null, null, null, null, null);
        }
    }
}
