package com.nocobase.acl;

import com.nocobase.entity.Role;
import com.nocobase.entity.User;
import com.nocobase.entity.UserRole;
import com.nocobase.repository.RoleRepository;
import com.nocobase.repository.UserRepository;
import com.nocobase.repository.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Provides the current user context for data-layer authorization.
 * Extracts userId from Spring SecurityContext and resolves role names from RoleRepository.
 */
@Component
public class CurrentUserContext {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserContext.class);

    private static final Set<String> ADMIN_ROLES = Set.of("admin", "root");

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    public CurrentUserContext(UserRoleRepository userRoleRepository, RoleRepository roleRepository,
                              UserRepository userRepository) {
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
    }

    /**
     * Get the current authenticated user ID.
     */
    public Optional<Long> getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long userId) {
            return Optional.of(userId);
        }
        return Optional.empty();
    }

    /**
     * Get the current user ID, throwing if not authenticated.
     */
    public Long requireUserId() {
        return getCurrentUserId()
                .orElseThrow(() -> new RuntimeException("User not authenticated"));
    }

    /**
     * Get the current authenticated user's email.
     */
    public Optional<String> getCurrentUserEmail() {
        return getCurrentUserId()
                .flatMap(userRepository::findById)
                .map(User::getEmail);
    }

    /**
     * Get the role names for the current user, resolved from RoleRepository.
     * Returns actual role names like "admin", "root", "member" — not role IDs.
     */
    public List<String> getCurrentUserRoles() {
        return getCurrentUserId()
                .map(userId -> {
                    List<UserRole> userRoles = userRoleRepository.findByUserId(userId);
                    List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
                    if (roleIds.isEmpty()) return Collections.<String>emptyList();

                    List<Role> roles = roleRepository.findAllById(roleIds);
                    return roles.stream()
                            .map(Role::getName)
                            .filter(Objects::nonNull)
                            .toList();
                })
                .orElse(Collections.emptyList());
    }

    /**
     * Check if the current user has a specific role name.
     */
    public boolean hasRole(String roleName) {
        return getCurrentUserRoles().contains(roleName);
    }

    /**
     * Check if current user is admin or root (bypass ACL).
     * Checks database roles first, then falls back to Spring Security granted authorities
     * (ROLE_admin, ROLE_root) set by the JWT authentication filter.
     */
    public boolean isAdmin() {
        List<String> roles = getCurrentUserRoles();
        if (roles.stream().anyMatch(ADMIN_ROLES::contains)) {
            return true;
        }
        // Fallback: check Spring Security granted authorities
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            return auth.getAuthorities().stream()
                    .anyMatch(a -> {
                        String authority = a.getAuthority();
                        return "ROLE_admin".equals(authority) || "ROLE_root".equals(authority);
                    });
        }
        return false;
    }
}