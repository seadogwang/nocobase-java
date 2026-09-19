package com.nocobase.controller;

import com.nocobase.entity.Role;
import com.nocobase.entity.User;
import com.nocobase.entity.UserRole;
import com.nocobase.repository.RoleRepository;
import com.nocobase.repository.UserRepository;
import com.nocobase.repository.UserRoleRepository;
import com.nocobase.service.AuditLogService;
import com.nocobase.web.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * First-admin bootstrap controller.
 * <p>
 * POST /api/bootstrap:setup creates the initial admin user when the users table
 * is empty. Admin credentials are read from environment variables, never from
 * the request body.
 *
 * <p><b>Security guarantees:</b>
 * <ul>
 *   <li>Credentials come from env vars only (NOCOBASE_ADMIN_EMAIL,
 *       NOCOBASE_ADMIN_PASSWORD, NOCOBASE_ADMIN_NICKNAME)</li>
 *   <li>Password is never logged or returned in any response</li>
 *   <li>Idempotent: any existing user results in 409 Conflict</li>
 *   <li>If env vars are not configured, returns 503 Service Unavailable</li>
 *   <li>Password strength is validated (min 8 chars, requires letter + digit)</li>
 *   <li>Bootstrap operation is audit-logged</li>
 *   <li>Concurrency-safe: ReentrantLock ensures only one admin is created even
 *       under concurrent bootstrap requests</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class BootstrapController {

    private static final Logger log = LoggerFactory.getLogger(BootstrapController.class);

    @Value("${NOCOBASE_ADMIN_EMAIL:#{null}}")
    private String adminEmail;

    @Value("${NOCOBASE_ADMIN_PASSWORD:#{null}}")
    private String adminPassword;

    @Value("${NOCOBASE_ADMIN_NICKNAME:Admin}")
    private String adminNickname;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final ReentrantLock bootstrapLock = new ReentrantLock();

    /**
     * Bootstrap the first admin user (colon-route variant).
     * Only available when the users table is empty.
     * Credentials are read from environment variables.
     *
     * <p>Concurrency: uses a ReentrantLock to guard the critical section.
     * Once the lock is acquired, the database operation runs inside a fresh
     * transaction (via TransactionTemplate) so that the thread sees the
     * latest committed state.
     *
     * @return 201 Created with user info (password excluded),
     *         409 Conflict if users already exist,
     *         503 Service Unavailable if env vars are not configured
     */
    @PostMapping("/bootstrap:setup")
    public ResponseEntity<?> setup() {
        // Check if env vars are configured (no transaction needed)
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            log.warn("Bootstrap endpoint called but NOCOBASE_ADMIN_EMAIL and/or NOCOBASE_ADMIN_PASSWORD are not configured");
            return ResponseEntity.status(503).body(ApiResponse.error(
                    "Bootstrap is not configured. Set NOCOBASE_ADMIN_EMAIL, NOCOBASE_ADMIN_PASSWORD, "
                    + "and optionally NOCOBASE_ADMIN_NICKNAME environment variables."));
        }

        // Fast-path: if users already exist, reject without acquiring the lock
        if (userRepository.count() > 0) {
            log.warn("Bootstrap rejected: users table is not empty (count={})", userRepository.count());
            return ResponseEntity.status(409).body(ApiResponse.error(
                    "Cannot bootstrap: users already exist in the database. "
                    + "Bootstrap is only available on a fresh installation."));
        }

        // Validate password strength (password value is never logged)
        validatePasswordStrength(adminPassword);

        log.debug("Bootstrapping admin user: email={}, nickname={}", adminEmail, adminNickname);

        bootstrapLock.lock();
        try {
            // Run the critical section inside a fresh transaction so that the
            // thread sees the latest committed state (important for concurrency).
            return transactionTemplate.execute(status -> {
                // Check again inside the lock and fresh transaction:
                // another thread may have created a user while we were waiting
                if (userRepository.count() > 0) {
                    log.warn("Bootstrap rejected (after lock): users table is not empty (count={})",
                            userRepository.count());
                    return ResponseEntity.status(409).body(ApiResponse.error(
                            "Cannot bootstrap: users already exist in the database. "
                            + "Bootstrap is only available on a fresh installation."));
                }

                try {
                    // Create admin user
                    User user = new User();
                    user.setEmail(adminEmail);
                    user.setNickname(adminNickname);
                    user.setPassword(passwordEncoder.encode(adminPassword));
                    user = userRepository.save(user);

                    // Assign admin role
                    Role adminRole = roleRepository.findByName("admin")
                            .orElseThrow(() -> new IllegalStateException(
                                    "Admin role not found in database. Ensure DataInitializer has run before bootstrap."));

                    UserRole adminUserRole = new UserRole();
                    adminUserRole.setUserId(user.getId());
                    adminUserRole.setRoleId(adminRole.getId());
                    userRoleRepository.save(adminUserRole);

                    // Assign root role
                    Role rootRole = roleRepository.findByName("root")
                            .orElseThrow(() -> new IllegalStateException(
                                    "Root role not found in database. Ensure DataInitializer has run before bootstrap."));

                    UserRole rootUserRole = new UserRole();
                    rootUserRole.setUserId(user.getId());
                    rootUserRole.setRoleId(rootRole.getId());
                    userRoleRepository.save(rootUserRole);

                    // Audit log the bootstrap operation (password is never included in details)
                    auditLogService.auditSuccess("bootstrap", "system", "admin",
                            Map.of("email", adminEmail, "nickname", adminNickname));

                    log.info("Admin user bootstrapped successfully: id={}", user.getId());
                    log.debug("Admin user bootstrapped successfully: id={}, email={}", user.getId(), adminEmail);

                    // Return user info without password
                    return ResponseEntity.status(201).body(ApiResponse.success(Map.of(
                            "id", user.getId(),
                            "email", user.getEmail(),
                            "nickname", user.getNickname(),
                            "createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : null
                    )));
                } catch (Exception e) {
                    log.error("Bootstrap failed for email={}: {}", adminEmail, e.getMessage());
                    auditLogService.auditFailure("bootstrap", "system", "admin",
                            Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
                    throw e;
                }
            });
        } finally {
            bootstrapLock.unlock();
        }
    }

    /**
     * Bootstrap the first admin user (slash-route variant).
     * Delegates to the same setup logic as the colon-route variant.
     *
     * @return 201 Created with user info (password excluded),
     *         409 Conflict if users already exist,
     *         503 Service Unavailable if env vars are not configured
     */
    @PostMapping("/bootstrap/setup")
    public ResponseEntity<?> setupSlash() {
        return setup();
    }

    /**
     * Validate password strength.
     * Requirements: minimum 8 characters, at least one letter and one digit.
     *
     * @param password the password to validate (never logged)
     */
    private void validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters long");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) hasLetter = true;
            if (Character.isDigit(c)) hasDigit = true;
            if (hasLetter && hasDigit) break;
        }
        if (!hasLetter) {
            throw new IllegalArgumentException("Password must contain at least one letter");
        }
        if (!hasDigit) {
            throw new IllegalArgumentException("Password must contain at least one digit");
        }
    }
}