package com.shifa.oms.pipeline;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.JwtAuthenticationFilter;
import com.shifa.oms.auth.Role;
import com.shifa.oms.permission.CrmPermissionRepository;
import com.shifa.oms.permission.PermissionService;
import com.shifa.oms.pipeline.dto.PipelineResponse;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint role-guard test for the configurable-pipeline API (CRM expansion
 * Phase 3, Task 18/20). The admin CRUD is {@code ADMIN or @perms.has(
 * MANAGE_PIPELINES)}; with the safe-default seed (ADMIN only) a SALESPERSON is
 * 403. The read endpoint {@code GET /api/pipelines} is open to the lead roles.
 */
@WebMvcTest(controllers = PipelineController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@Import(PipelineEndpointRoleGuardIntegrationTest.TestConfig.class)
class PipelineEndpointRoleGuardIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void listActiveIsOpenToLeadRoles() throws Exception {
        var request = get("/api/pipelines");
        mvc.perform(request.with(authFor(Role.ADMIN))).andExpect(status().is2xxSuccessful());
        mvc.perform(request.with(authFor(Role.SALESPERSON))).andExpect(status().is2xxSuccessful());
        mvc.perform(request.with(authFor(Role.TEAM_LEAD))).andExpect(status().is2xxSuccessful());
        mvc.perform(request.with(authFor(Role.PACKING_USER))).andExpect(status().isForbidden());
    }

    @Test
    void adminCrudIsAdminOrManagePipelines() throws Exception {
        mvc.perform(get("/api/admin/pipelines").with(authFor(Role.ADMIN)))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/admin/pipelines").with(authFor(Role.SALESPERSON)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/pipelines")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Retail\"}")
                        .with(authFor(Role.TEAM_LEAD)))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor authFor(Role role) {
        AuthPrincipal principal = new AuthPrincipal(1L, "user-" + role.name().toLowerCase(), role);
        var token = new UsernamePasswordAuthenticationToken(
                principal, "n/a", List.of(new SimpleGrantedAuthority(role.authority())));
        return authentication(token);
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        @Bean
        PipelineService pipelineService() {
            return new StubPipelineService();
        }

        @Bean("perms")
        PermissionService perms() {
            return new PermissionService(mock(CrmPermissionRepository.class));
        }
    }

    /** Canned pipeline results so a permitted call yields 2xx. */
    static class StubPipelineService extends PipelineService {
        StubPipelineService() {
            super(mock(PipelineRepository.class), mock(PipelineStageRepository.class));
        }

        @Override
        public List<PipelineResponse> listActive() {
            return List.of();
        }

        @Override
        public List<PipelineResponse> listAll() {
            return List.of();
        }
    }
}
