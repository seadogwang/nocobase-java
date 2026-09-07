package com.nocobase.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Extracts or generates a request ID for every HTTP request.
 * <p>
 * Priority:
 * <ol>
 *   <li>X-Request-Id HTTP header (caller-provided) -- validated for length and allowed chars</li>
 *   <li>Generated UUID (fallback)</li>
 * </ol>
 * <p>
 * The request ID is stored in {@link RequestIdContext} (ThreadLocal) and
 * also set in SLF4J {@link MDC} for log correlation.
 * Same request always gets the same requestId.
 * <p>
 * Both ThreadLocal and MDC are cleaned up in a {@code finally} block after
 * the request completes.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestIdFilter extends OncePerRequestFilter implements Ordered {

    /**
     * Allowed characters: alphanumeric, dash, underscore.
     * Max length: 255 characters (arbitrary but prevents abuse).
     */
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[a-zA-Z0-9_-]{1,255}");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");

        if (requestId != null && !requestId.isEmpty()) {
            // Validate caller-provided request ID
            if (!VALID_REQUEST_ID.matcher(requestId).matches()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"errors\":[{\"code\":\"INVALID_REQUEST_ID\",\"message\":\"X-Request-Id must be 1-255 alphanumeric, dash, or underscore characters\"}]}");
                return;
            }
        } else {
            // Generate a new UUID-based request ID
            requestId = UUID.randomUUID().toString().replace("-", "");
        }

        RequestIdContext.set(requestId);
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestIdContext.clear();
            MDC.remove("requestId");
        }
    }
}