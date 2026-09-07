package com.nocobase.controller;

import com.nocobase.service.AuthService;
import com.nocobase.web.ApiResponse;
import com.nocobase.web.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Authentication controller providing signIn, check, current user, and refresh endpoints.
 *
 * <p>All business logic is delegated to {@link AuthService}. This controller
 * handles HTTP concerns only (request mapping, header extraction, response formatting).
 *
 * <p><b>Security guarantees:</b>
 * <ul>
 *   <li>Passwords are never returned in any response</li>
 *   <li>Token text never appears in error messages or logs</li>
 *   <li>Token expiry, invalid token, and missing token produce stable, non-leaking responses</li>
 *   <li>JWT secret is read from secure config (environment variable or properties file),
 *       never uses a hardcoded default</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    @Autowired
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Sign in with email and password.
     * POST /api/auth:signIn or /api/auth/signIn
     * Returns a JWT token and user info (without password).
     */
    @PostMapping({"/auth:signIn", "/auth/signIn"})
    public ResponseEntity<?> signIn(@RequestBody Map<String, String> credentials) {
        String email = credentials.get("email");
        String password = credentials.get("password");
        return ResponseEntity.ok(ApiResponse.success(authService.signIn(email, password)));
    }

    /**
     * Check if the current token is valid.
     * GET /api/auth:check or /api/auth/check
     * Returns user info if the token is valid.
     */
    @GetMapping({"/auth:check", "/auth/check"})
    public ResponseEntity<?> checkAuth(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = authService.extractUserIdFromAuthHeader(authHeader);
        return ResponseEntity.ok(ApiResponse.success(authService.check(userId)));
    }

    /**
     * Get the current authenticated user's profile.
     * GET /api/auth:user or /api/auth/user
     * Returns user info without password.
     */
    @GetMapping({"/auth:user", "/auth/user"})
    public ResponseEntity<?> currentUser(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = authService.extractUserIdFromAuthHeader(authHeader);
        return ResponseEntity.ok(ApiResponse.success(authService.currentUser(userId)));
    }

    /**
     * Refresh the current JWT token (issue a new token with the same claims).
     * POST /api/auth:refresh or /api/auth/refresh
     * The old token must still be valid (not expired).
     */
    @PostMapping({"/auth:refresh", "/auth/refresh"})
    public ResponseEntity<?> refresh(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new UnauthorizedException("No token provided");
        }
        String token = authHeader.substring(7);
        return ResponseEntity.ok(ApiResponse.success(authService.refresh(token)));
    }

    /**
     * Logout (stateless JWT: no server-side session to invalidate).
     * POST /api/auth:logout or /api/auth/logout
     * Always returns success. Token invalidation is handled client-side.
     */
    @PostMapping({"/auth:logout", "/auth/logout"})
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok(ApiResponse.success(authService.logout()));
    }
}