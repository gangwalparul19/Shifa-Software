package com.shifa.oms.insights;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.JwtAuthenticationFilter;
import com.shifa.oms.auth.Role;
import com.shifa.oms.insights.dto.InsightResponse;
import com.shifa.oms.insights.dto.RecomputeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint role-guard integration test for the insights API (task 4.3, design
 * §API, §Testing; Req 2.2, 9.2, 9.4). Mirrors
 * {@code LeadEndpointRoleGuardIntegrationTest}: it drives the real
 * {@link InsightController} through {@link MockMvc} with method security
 * ({@code @PreAuthorize}) active, and asserts the backend enforces the role
 * matrix independently of the Angular route guards.
 *
 * <p>The listing shares the class-level
 * {@code @PreAuthorize("hasAnyRole('ADMIN','SALESPERSON')")}: {@code ADMIN} and
 * {@code SALESPERSON} pass (2xx) while {@code PACKING_USER}/{@code ACCOUNTANT}
 * get 403. The two mutating routes ({@code recompute}, {@code dismiss}) carry a
 * method-level {@code hasRole('ADMIN')}, so only {@code ADMIN} passes and a
 * {@code SALESPERSON} is now 403.
 *
 * <p>The HTTP filter chain is permit-all so the ONLY authorization decision under
 * test is the method-level guard; callers are authenticated with a real
 * {@link AuthPrincipal}. The controller's {@link InsightService} collaborator is
 * a lightweight recording subclass (no Mockito mock of a concrete class, per the
 * Java 25 runtime gotcha).
 */
@WebMvcTest(controllers = InsightController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@Import(InsightEndpointRoleGuardIntegrationTest.TestSecurityAndStubs.class)
class InsightEndpointRoleGuardIntegrationTest {

    private static final List<Role> OPERATIONAL_ROLES = List.of(
            Role.ADMIN, Role.SALESPERSON, Role.PACKING_USER, Role.ACCOUNTANT);

    private static final List<Role> ADMIN_OR_SALESPERSON = List.of(Role.SALESPERSON, Role.ADMIN);

    private static final List<Role> ADMIN_ONLY = List.of(Role.ADMIN);

    @Autowired
    private MockMvc mvc;

    @Test
    void listIsAdminOrSalesperson() throws Exception {
        assertRoleMatrix(get("/api/insights"), ADMIN_OR_SALESPERSON);
    }

    @Test
    void recomputeIsAdminOnly() throws Exception {
        assertRoleMatrix(post("/api/insights/recompute"), ADMIN_ONLY);
    }

    @Test
    void dismissIsAdminOnly() throws Exception {
        assertRoleMatrix(post("/api/insights/5/dismiss"), ADMIN_ONLY);
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
        InsightService insightService() {
            return new StubInsightService();
        }
    }

    /** Canned insight results so a permitted call passes the guard and yields 2xx. */
    static class StubInsightService extends InsightService {
        StubInsightService() {
            // computationService is null: the stub overrides recompute() so it is never used.
            super(null, null, null);
        }

        private static InsightResponse sample() {
            return new InsightResponse(5L, "SALES_ANOMALY", "GLOBAL", 0L, "All sales",
                    "WARNING", "Sales dipped 40%", "detail", BigDecimal.valueOf(-40),
                    LocalDate.of(2025, 3, 1), false);
        }

        @Override
        public List<InsightResponse> list(String type, String scope, String severity,
                                          boolean includeDismissed, AuthPrincipal principal) {
            return List.of(sample());
        }

        @Override
        public InsightResponse dismiss(Long id, AuthPrincipal principal) {
            return sample();
        }

        @Override
        public RecomputeResponse recompute() {
            return new RecomputeResponse(3, LocalDate.of(2025, 3, 1));
        }
    }
}
