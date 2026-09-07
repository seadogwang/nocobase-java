package com.nocobase.security;

import com.nocobase.entity.Role;
import com.nocobase.entity.UserRole;
import com.nocobase.repository.RoleRepository;
import com.nocobase.repository.UserRoleRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthenticationFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Skip JWT validation for public endpoints
        if (isPublicEndpoint(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        Claims claims = jwtUtil.validateToken(token);

        log.debug("JWT filter: path={}, token valid={}",
                path, claims != null);

        if (claims != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            // userId claim may be stored as Integer by JJWT for small values;
            // use Object type and convert to Long
            Object userIdObj = claims.get("userId");
            Long userId = null;
            if (userIdObj instanceof Number) {
                userId = ((Number) userIdObj).longValue();
            }
            String email = claims.getSubject();

            // Load user roles from database and convert to GrantedAuthority
            List<GrantedAuthority> authorities = loadAuthorities(userId);
            log.debug("JWT auth success: path={}, userId={}, roleCount={}",
                    path, userId, authorities.size());

            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                userId, null, authorities
            );

            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Load the user's roles from the database and convert them to Spring Security
     * GrantedAuthority objects (with ROLE_ prefix).
     */
    private List<GrantedAuthority> loadAuthorities(Long userId) {
        if (userId == null) {
            return List.of();
        }
        try {
            List<UserRole> userRoles = userRoleRepository.findByUserId(userId);
            return userRoles.stream()
                    .map(ur -> roleRepository.findById(ur.getRoleId()))
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .map(Role::getName)
                    .map(name -> "ROLE_" + name)
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // If role loading fails, return empty authorities
            // The request will be denied by method security if admin role required
            return List.of();
        }
    }

    private boolean isPublicEndpoint(String path) {
        return path.equals("/api/auth:signIn") ||
               path.equals("/api/auth/signIn") ||
               path.equals("/api/auth:check") ||
               path.equals("/api/auth/check") ||
               path.equals("/api/auth:refresh") ||
               path.equals("/api/auth/refresh") ||
               path.equals("/api/auth:logout") ||
               path.equals("/api/auth/logout") ||
               path.equals("/api/bootstrap:setup") ||
               path.startsWith("/h2-console") ||
               path.startsWith("/static") ||
               path.startsWith("/v/") ||
               path.equals("/");
    }
}
