package com.mygateapp.common.web;

import com.mygateapp.config.AppProperties;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * General API rate limit per authenticated user (or per client IP for anonymous calls). Runs inside the security
 * filter chain after authentication. If Redis is unavailable the request is allowed (fail-open) so that a cache
 * outage does not take the gate offline; OTP limits are enforced separately and fail closed.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimiter rateLimiter;
    private final AppProperties.RateLimit config;
    private final JsonErrorWriter errorWriter;

    public RateLimitFilter(RateLimiter rateLimiter, AppProperties.RateLimit config, JsonErrorWriter errorWriter) {
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !config.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String bucket = bucketFor(request);
        boolean allowed;
        try {
            allowed = rateLimiter.tryAcquire("api:" + bucket, config.requestsPerMinute(), WINDOW);
        } catch (RuntimeException ex) {
            log.warn("Rate limiter unavailable, allowing request: {}", ex.getClass().getSimpleName());
            allowed = true;
        }
        if (!allowed) {
            response.setHeader("Retry-After", String.valueOf(WINDOW.toSeconds()));
            errorWriter.write(request, response, ErrorCode.RATE_LIMITED, null);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String bucketFor(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return "user:" + user.userId();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
