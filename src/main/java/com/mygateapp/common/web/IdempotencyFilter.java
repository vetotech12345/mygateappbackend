package com.mygateapp.common.web;

import com.mygateapp.exception.ErrorCode;
import com.mygateapp.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.ObjectMapper;

/**
 * Optional {@code Idempotency-Key} support for authenticated POST requests. The first successful response for a
 * (user, key) pair is stored in Redis and replayed for retries, so a retried "register visitor" or "raise complaint"
 * never creates a duplicate. A key reused for a different endpoint is rejected.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");
    private static final Duration IN_PROGRESS_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final JsonErrorWriter errorWriter;
    private final Duration ttl;

    public IdempotencyFilter(StringRedisTemplate redis, ObjectMapper objectMapper, JsonErrorWriter errorWriter, Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.errorWriter = errorWriter;
        this.ttl = ttl;
    }

    record StoredResponse(String state, String endpoint, int status, String contentType, String body) {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || request.getHeader(HEADER) == null
                || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader(HEADER).trim();
        if (!VALID_KEY.matcher(key).matches()) {
            errorWriter.write(request, response, ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key must be 8-100 characters (letters, digits, . _ : -).");
            return;
        }
        String endpoint = request.getMethod() + " " + request.getRequestURI();
        String redisKey = "idem:" + user.userId() + ":" + sha256(key);

        Boolean acquired;
        try {
            String marker = objectMapper.writeValueAsString(new StoredResponse("IN_PROGRESS", endpoint, 0, null, null));
            acquired = redis.opsForValue().setIfAbsent(redisKey, marker, IN_PROGRESS_TTL);
        } catch (RuntimeException ex) {
            log.warn("Idempotency store unavailable, processing without it: {}", ex.getClass().getSimpleName());
            chain.doFilter(request, response);
            return;
        }

        if (!Boolean.TRUE.equals(acquired)) {
            replayOrReject(request, response, redisKey, endpoint);
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(request, wrapper);
            int status = wrapper.getStatus();
            if (status >= 200 && status < 300) {
                String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
                String value = objectMapper.writeValueAsString(
                        new StoredResponse("DONE", endpoint, status, wrapper.getContentType(), body));
                redis.opsForValue().set(redisKey, value, ttl);
                stored = true;
            }
        } finally {
            if (!stored) {
                safeDelete(redisKey);
            }
            wrapper.copyBodyToResponse();
        }
    }

    private void replayOrReject(HttpServletRequest request, HttpServletResponse response, String redisKey, String endpoint)
            throws IOException {
        String raw = redis.opsForValue().get(redisKey);
        if (raw == null) {
            errorWriter.write(request, response, ErrorCode.IDEMPOTENCY_KEY_IN_USE, null);
            return;
        }
        StoredResponse stored = objectMapper.readValue(raw, StoredResponse.class);
        if (!endpoint.equals(stored.endpoint())) {
            errorWriter.write(request, response, ErrorCode.IDEMPOTENCY_KEY_MISMATCH, null);
            return;
        }
        if (!"DONE".equals(stored.state())) {
            errorWriter.write(request, response, ErrorCode.IDEMPOTENCY_KEY_IN_USE, null);
            return;
        }
        response.setStatus(stored.status());
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        response.setHeader("Idempotent-Replayed", "true");
        if (stored.body() != null) {
            response.getOutputStream().write(stored.body().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void safeDelete(String redisKey) {
        try {
            redis.delete(redisKey);
        } catch (RuntimeException ex) {
            log.warn("Could not release idempotency key: {}", ex.getClass().getSimpleName());
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
