package com.nocobase.config;

import com.nocobase.security.JwtAuthenticationFilter;
import com.nocobase.web.ApiResponse;
import com.nocobase.web.RequestIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter();
    }

    /**
     * Prevent Spring Boot from auto-registering JwtAuthenticationFilter
     * as a servlet Filter. It is managed exclusively by the Spring Security
     * filter chain via addFilterBefore.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 5)
    public RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    /**
     * Prevent Spring Boot from auto-registering RequestIdFilter as a servlet
     * Filter. It is managed exclusively by the Spring Security filter chain via
     * addFilterBefore.
     */
    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration(RequestIdFilter filter) {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints - no authentication required
                .requestMatchers("/api/auth:signIn").permitAll()
                .requestMatchers("/api/auth/signIn").permitAll()
                .requestMatchers("/api/auth:check").permitAll()
                .requestMatchers("/api/auth/check").permitAll()
                .requestMatchers("/api/auth:refresh").permitAll()
                .requestMatchers("/api/auth/refresh").permitAll()
                .requestMatchers("/api/auth:logout").permitAll()
                .requestMatchers("/api/auth/logout").permitAll()
                .requestMatchers("/api/bootstrap:setup").permitAll()
                .requestMatchers("/api/bootstrap/setup").permitAll()
                .requestMatchers("/api/health").permitAll()
                .requestMatchers("/api/health/**").permitAll()
                .requestMatchers("/h2-console/**").permitAll()
                .requestMatchers("/static/**").permitAll()
                .requestMatchers("/v/**").permitAll()
                .requestMatchers("/").permitAll()
                // All other API endpoints require authentication
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(
                            new ObjectMapper().writeValueAsString(
                                    ApiResponse.error("Unauthorized")));
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(
                            new ObjectMapper().writeValueAsString(
                                    ApiResponse.error("Access denied")));
                })
            );

        // Add JWT filter before UsernamePasswordAuthenticationFilter
        http.addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        // Add RequestIdFilter before JWT filter so requestId is available for all downstream processing
        http.addFilterBefore(requestIdFilter(), JwtAuthenticationFilter.class);

        return http.build();
    }
}
