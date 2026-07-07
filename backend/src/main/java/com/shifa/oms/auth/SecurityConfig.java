package com.shifa.oms.auth;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Central Spring Security configuration (design: "Auth & RBAC").
 *
 * <ul>
 *   <li>Stateless JWT authentication — no server session; the
 *       {@link JwtAuthenticationFilter} authenticates each request from its
 *       bearer token.</li>
 *   <li>Public access to the catalog/product/search endpoints, the auth
 *       endpoints, the courier/WhatsApp webhooks (HMAC-validated separately),
 *       and PWA/static assets; every other {@code /api/**} route requires
 *       authentication (Req 5.2).</li>
 *   <li>Method-level authorization via {@code @PreAuthorize} (Req 5.3, 5.4,
 *       5.5), enabled by {@link EnableMethodSecurity}.</li>
 *   <li>401 / 403 responses rendered as the standard error envelope by
 *       {@link RestAuthenticationEntryPoint} / {@link RestAccessDeniedHandler}.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /** BCrypt password hashing for stored credentials (Req 5.1). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS policy for local development: permits the Angular storefront and admin
     * dev servers (any localhost/127.0.0.1 port) to call the API with the
     * Authorization header. Production origins should be restricted via config.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:*", "http://127.0.0.1:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                // Allow the Angular dev servers (storefront/admin) to call the API cross-origin.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Stateless REST API: no CSRF token, no HTTP session.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Async re-dispatches (e.g. the long-lived admin SSE stream at
                        // /api/admin/events) and error dispatches must not be re-authorized:
                        // Spring Security 6.1+ filters every dispatcher type by default, but
                        // the SecurityContext is not populated on the ASYNC re-dispatch, so
                        // the original (already authorized) request would otherwise fail with
                        // a spurious AccessDeniedException once its response is committed.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        // Public authentication endpoints (Req 5.2): login, refresh,
                        // and customer self-registration (Phase B: /api/auth/register).
                        .requestMatchers("/api/auth/**").permitAll()
                        // Public customer order tracking by order code (Req 13.4).
                        .requestMatchers(HttpMethod.GET, "/api/track/**").permitAll()
                        // Inbound webhooks are unauthenticated to the browser but
                        // HMAC-validated inside their handlers (courier/WhatsApp).
                        .requestMatchers("/api/webhooks/**").permitAll()
                        // PWA shell and static assets.
                        .requestMatchers(
                                "/", "/index.html", "/favicon.ico",
                                "/manifest.webmanifest", "/ngsw-worker.js", "/ngsw.json",
                                "/assets/**", "/*.js", "/*.css").permitAll()
                        // Everything else under the API requires authentication.
                        .requestMatchers("/api/**").authenticated()
                        // Non-API paths (served static shell) are open.
                        .anyRequest().permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // No HTTP Basic / form login: authentication is bearer-token only.
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
