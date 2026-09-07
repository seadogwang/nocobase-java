package com.nocobase.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT utility for token generation, validation, and parsing.
 *
 * <p><b>Security:</b>
 * <ul>
 *   <li>The JWT secret MUST be configured via {@code nocobase.jwt.secret} or the
 *       {@code NOCOBASE_JWT_SECRET} environment variable.</li>
 *   <li>If the secret is not configured, the application refuses to start with a
 *       clear error message. No default production secret is ever used.</li>
 *   <li>Token text is NEVER logged or included in error messages.</li>
 * </ul>
 */
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    private static final String DEFAULT_SECRET = "default-secret-key-for-nocobase-java-backend-must-be-at-least-256-bits";
    private static final int MIN_SECRET_LENGTH = 32; // 256 bits minimum

    @Value("${nocobase.jwt.secret:}")
    private String secret;

    @Value("${nocobase.jwt.expiration:86400000}")
    private long expiration;

    /**
     * Validate the JWT secret on startup. Refuses to start if the secret is
     * not configured, is the default value, or is too short.
     */
    @PostConstruct
    public void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret is not configured. Set 'nocobase.jwt.secret' in application.yml "
                    + "or the NOCOBASE_JWT_SECRET environment variable. "
                    + "The secret must be at least 256 bits (32 characters) long.");
        }
        if (DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "JWT secret is set to the default insecure value. "
                    + "Configure a secure secret via 'nocobase.jwt.secret' or NOCOBASE_JWT_SECRET.");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT secret is too short (" + secret.length() + " characters). "
                    + "The secret must be at least " + MIN_SECRET_LENGTH + " characters (256 bits) long.");
        }
        log.info("JWT secret validated (length: {} characters)", secret.length());
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate a signed JWT token.
     *
     * @param userId the user ID
     * @param email  the user email (set as subject)
     * @param claims additional claims
     * @return the signed JWT token string
     */
    public String generateToken(Long userId, String email, Map<String, Object> claims) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        JwtBuilder builder = Jwts.builder()
                .subject(email)
                .claim("userId", userId)
                .issuedAt(now)
                .expiration(expiryDate);

        if (claims != null) {
            claims.forEach(builder::claim);
        }

        return builder.signWith(getSigningKey()).compact();
    }

    /**
     * Validate and parse a JWT token. Returns null if the token is invalid,
     * expired, or malformed. Never logs token text.
     *
     * @param token the JWT token string
     * @return the parsed Claims, or null if invalid
     */
    public Claims validateToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            // Token expired — this is expected, don't log the token
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            // Invalid token — don't log the token text
            return null;
        }
    }

    /**
     * Extract the user ID from a JWT token.
     *
     * @param token the JWT token string
     * @return the user ID, or null if the token is invalid
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = validateToken(token);
        if (claims == null) {
            return null;
        }
        // userId claim may be stored as Integer by JJWT for small values
        Object userIdObj = claims.get("userId");
        if (userIdObj instanceof Number) {
            return ((Number) userIdObj).longValue();
        }
        return null;
    }

    /**
     * Extract the email (subject) from a JWT token.
     *
     * @param token the JWT token string
     * @return the email, or null if the token is invalid
     */
    public String getEmailFromToken(String token) {
        Claims claims = validateToken(token);
        return claims != null ? claims.getSubject() : null;
    }

    /**
     * Check if a JWT token is expired.
     *
     * @param token the JWT token string
     * @return true if the token is expired or invalid
     */
    public boolean isTokenExpired(String token) {
        Claims claims = validateToken(token);
        if (claims == null) {
            return true;
        }
        return claims.getExpiration().before(new Date());
    }
}