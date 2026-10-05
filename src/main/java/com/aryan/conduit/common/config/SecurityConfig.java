package com.aryan.conduit.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.aryan.conduit.security.service.WorkflowOwnershipFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            Converter<Jwt, ? extends AbstractAuthenticationToken> jwtConverter,
            @Value("${conduit.security.metrics-token:}") String metricsToken,
            WorkflowOwnershipFilter workflowOwnershipFilter) throws Exception {
        String configuredMetricsToken = metricsToken == null ? "" : metricsToken;
        OncePerRequestFilter metricsFilter = new OncePerRequestFilter() {
            @Override protected boolean shouldNotFilter(HttpServletRequest request) {
                return !"/actuator/prometheus".equals(request.getServletPath());
            }
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String authorization = request.getHeader("Authorization");
                String supplied = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
                if (configuredMetricsToken.length() < 32 || !MessageDigest.isEqual(configuredMetricsToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Metrics authentication required");
                    return;
                }
                var auth = new UsernamePasswordAuthenticationToken("prometheus", "", List.of(new SimpleGrantedAuthority("ROLE_METRICS")));
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
                chain.doFilter(request, response);
            }
        };
        BearerTokenResolver apiTokenResolver = request -> "/actuator/prometheus".equals(request.getServletPath())
                ? null : new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver().resolve(request);
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> response.sendError(401, "Authentication required"))
                        .accessDeniedHandler((request, response, exception) -> response.sendError(403, "Insufficient role")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/actuator/health", "/actuator/health/**", "/error") .permitAll()
                        .requestMatchers("/actuator/prometheus").hasRole("METRICS")
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/auth/users").hasRole("ADMIN")
                        .requestMatchers("/api/auth/audit").hasRole("ADMIN")
                        .requestMatchers("/api/workflow-versions").hasRole("ADMIN")
                        .requestMatchers("/api/notifications/**").hasRole("ADMIN")
                        .requestMatchers("/api/workflows/*/credentials/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/workflows/*/credentials").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/workflows/*/credentials/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/workflows/*/credentials/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/workflow-versions/*/publish", "/api/workflow-versions/*/rollback/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout-all").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/auth/password").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/auth/mfa/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/workflows/*/execute", "/api/workflows/*/execute-with-options", "/api/workflow-versions/*/execute",
                                "/api/executions/*/replay", "/api/executions/*/pause", "/api/executions/*/resume", "/api/executions/*/cancel",
                                "/api/human-tasks/*/approve", "/api/human-tasks/*/reject", "/api/human-tasks/*/respond",
                                "/api/context/*/test", "/api/output/test/*")
                            .hasAnyRole("ADMIN", "BUILDER", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/workflows", "/api/workflows/import", "/api/workflows/*/clone",
                                "/api/workflows/*/validate", "/api/workflows/*/versions", "/api/workflows/*/environments",
                                "/api/workflow-versions/*", "/api/tasks/**", "/api/dependencies/**")
                            .hasAnyRole("ADMIN", "BUILDER")
                        .requestMatchers(HttpMethod.PUT, "/api/workflows/*/**", "/api/tasks/**", "/api/dependencies/**", "/api/workflow-versions/**", "/api/workflows/*/environments/**")
                            .hasAnyRole("ADMIN", "BUILDER")
                        .requestMatchers(HttpMethod.DELETE, "/api/tasks/**", "/api/dependencies/**", "/api/workflows/*/environments/**")
                            .hasAnyRole("ADMIN", "BUILDER")
                        .requestMatchers(HttpMethod.POST, "/api/notifications/*/read", "/api/notifications/read-all").authenticated()
                        // Explicitly classify every write API above. Any new write route is admin-only until reviewed.
                        .requestMatchers(HttpMethod.POST, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                        .requestMatchers("/api/operations/**", "/actuator/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(metricsFilter, org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .addFilterAfter(workflowOwnershipFilter, org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(resource -> resource.bearerTokenResolver(apiTokenResolver)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }

    @Bean
    FilterRegistrationBean<WorkflowOwnershipFilter> disableContainerRegistration(WorkflowOwnershipFilter filter) {
        FilterRegistrationBean<WorkflowOwnershipFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${conduit.security.allowed-origins}") String origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With"));
        configuration.setExposedHeaders(List.of("Retry-After"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
}
