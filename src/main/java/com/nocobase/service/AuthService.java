package com.nocobase.service;

import com.nocobase.entity.User;
import com.nocobase.repository.UserRepository;
import com.nocobase.security.JwtUtil;
import com.nocobase.web.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Authentication service providing signIn, check, current user, refresh, and logout.
 *
 * <p>Encapsulates all {@link UserRepository} access so that {@link com.nocobase.controller.AuthController}
 * only depends on this service, not on JPA repositories directly.
 *
 * <p><b>Security guarantees:</b>
 * <ul>
 *   <li>Passwords are never returned in any response</li>
 *   <li>Token text never appears in error messages or logs</li>
 *   <li>Token expiry, invalid token, and missing token produce stable, non-leaking responses</li>
 * </ul>
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Autowired
    public AuthService(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Sign in with email and password.
     * Returns a JWT token and user info (without password).
     */
    public Map<String, Object> signIn(String email, String password) {
        if (email == null || email.isBlank()) {
            throw new UnauthorizedException("Email is required");
        }
        if (password == null || password.isBlank()) {
            throw new UnauthorizedException("Password is required");
        }

        Optional<User> userOpt = userRepository.findByEmail(email);

        if (userOpt.isEmpty()) {
            throw new UnauthorizedException("Invalid credentials");
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new UnauthorizedException("Invalid credentials");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), Map.of(
                "nickname", user.getNickname()
        ));

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("user", Map.of(
                "id", user.getId(),
                "email", user.getEmail(),
                "nickname", user.getNickname()
        ));

        return response;
    }

    /**
     * Check if the current token is valid.
     * Returns user info if the token is valid.
     */
    public Map<String, Object> check(Long userId) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            throw new UnauthorizedException("User not found");
        }

        User user = userOpt.get();
        Map<String, Object> userData = new HashMap<>();
        userData.put("id", user.getId());
        userData.put("email", user.getEmail());
        userData.put("nickname", user.getNickname());

        return userData;
    }

    /**
     * Get the current authenticated user's profile.
     * Returns user info without password.
     */
    public Map<String, Object> currentUser(Long userId) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            throw new UnauthorizedException("User not found");
        }

        User user = userOpt.get();
        Map<String, Object> userData = new HashMap<>();
        userData.put("id", user.getId());
        userData.put("email", user.getEmail());
        userData.put("nickname", user.getNickname());
        userData.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
        userData.put("updatedAt", user.getUpdatedAt() != null ? user.getUpdatedAt().toString() : null);

        return userData;
    }

    /**
     * Refresh the current JWT token (issue a new token with the same claims).
     * The old token must still be valid (not expired).
     */
    public Map<String, Object> refresh(String token) {
        var claims = jwtUtil.validateToken(token);

        if (claims == null) {
            throw new UnauthorizedException("Invalid or expired token");
        }

        // userId claim may be stored as Integer by JJWT for small values
        Object userIdObj = claims.get("userId");
        Long userId = null;
        if (userIdObj instanceof Number) {
            userId = ((Number) userIdObj).longValue();
        }
        String email = claims.getSubject();

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            throw new UnauthorizedException("User not found");
        }

        User user = userOpt.get();
        String newToken = jwtUtil.generateToken(user.getId(), user.getEmail(), Map.of(
                "nickname", user.getNickname()
        ));

        Map<String, Object> response = new HashMap<>();
        response.put("token", newToken);
        response.put("user", Map.of(
                "id", user.getId(),
                "email", user.getEmail(),
                "nickname", user.getNickname()
        ));

        return response;
    }

    /**
     * Logout (stateless JWT: no server-side session to invalidate).
     * Always returns success. Token invalidation is handled client-side.
     */
    public Map<String, Object> logout() {
        return Map.of("message", "Logged out successfully");
    }

    /**
     * Extract user ID from the Authorization header.
     *
     * @param authHeader the Authorization header value
     * @return the user ID
     * @throws UnauthorizedException with a stable message (never contains token text)
     */
    public Long extractUserIdFromAuthHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new UnauthorizedException("No token provided");
        }

        String token = authHeader.substring(7);
        if (token.isBlank()) {
            throw new UnauthorizedException("No token provided");
        }

        if (jwtUtil.isTokenExpired(token)) {
            throw new UnauthorizedException("Token has expired");
        }

        Long userId = jwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            throw new UnauthorizedException("Invalid token");
        }

        return userId;
    }
}