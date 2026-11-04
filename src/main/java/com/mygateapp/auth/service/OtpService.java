package com.mygateapp.auth.service;

import com.mygateapp.auth.sms.SmsSender;
import com.mygateapp.common.Hashing;
import com.mygateapp.common.web.RateLimiter;
import com.mygateapp.config.AppProperties;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * OTP issue and verification, stored in Redis:
 * <ul>
 *   <li>{@code otp:req:{requestId}} – hash {subject, hash, attempts}; expires with the OTP</li>
 *   <li>{@code otp:latest:{subject}} – the latest request id (a new request invalidates older codes)</li>
 *   <li>{@code otp:cooldown:{subject}} – resend cooldown</li>
 * </ul>
 * Only an HMAC of the code is stored. Attempts are counted before comparison so at most {@code maxAttempts}
 * guesses are possible per code. Redis failures fail closed (503).
 */
@Service
public class OtpService {

    public record Challenge(String requestId, long resendAfterSeconds, long expiresInSeconds) {
    }

    private final StringRedisTemplate redis;
    private final RateLimiter rateLimiter;
    private final SmsSender smsSender;
    private final AppProperties.Otp config;
    private final byte[] hashKey;

    public OtpService(StringRedisTemplate redis, RateLimiter rateLimiter, SmsSender smsSender, AppProperties properties) {
        this.redis = redis;
        this.rateLimiter = rateLimiter;
        this.smsSender = smsSender;
        this.config = properties.otp();
        this.hashKey = Hashing.deriveKey(properties.jwt().secret(), "otp");
    }

    /** Rate limits that apply before we look the user up (also slows down number enumeration). */
    public void checkRequestLimits(String countryCode, String mobile, String clientIp) {
        String subject = subject(countryCode, mobile);
        try {
            if (config.resendCooldown().toMillis() > 0) {
                Long ttl = redis.getExpire("otp:cooldown:" + subject);
                if (ttl != null && ttl > 0) {
                    throw new ApiException(ErrorCode.OTP_RATE_LIMITED,
                            "Please wait " + ttl + " seconds before requesting a new code.");
                }
            }
            if (!rateLimiter.tryAcquire("otp-mobile:" + subject, config.maxRequestsPerMobilePerHour(), Duration.ofHours(1))) {
                throw new ApiException(ErrorCode.OTP_RATE_LIMITED);
            }
            if (clientIp != null
                    && !rateLimiter.tryAcquire("otp-ip:" + clientIp, config.maxRequestsPerIpPerHour(), Duration.ofHours(1))) {
                throw new ApiException(ErrorCode.OTP_RATE_LIMITED);
            }
        } catch (DataAccessException e) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public Challenge issue(String countryCode, String mobile) {
        String subject = subject(countryCode, mobile);
        String code = StringUtils.hasText(config.fixedCode()) ? config.fixedCode() : Hashing.randomDigits(config.length());
        String requestId = UUID.randomUUID().toString();
        String requestKey = "otp:req:" + requestId;
        try {
            redis.opsForHash().putAll(requestKey, Map.of(
                    "subject", subject,
                    "hash", hash(requestId, code),
                    "attempts", "0"));
            redis.expire(requestKey, config.ttl());
            redis.opsForValue().set("otp:latest:" + subject, requestId, config.ttl());
            if (config.resendCooldown().toMillis() > 0) {
                redis.opsForValue().set("otp:cooldown:" + subject, "1", config.resendCooldown());
            }
        } catch (DataAccessException e) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            smsSender.sendOtp(countryCode, mobile, code, config.ttl().toMinutes());
        } catch (RuntimeException e) {
            safeDelete(requestKey, "otp:latest:" + subject, "otp:cooldown:" + subject);
            throw e instanceof ApiException api ? api : new ApiException(ErrorCode.OTP_DELIVERY_FAILED);
        }
        return new Challenge(requestId, config.resendCooldown().toSeconds(), config.ttl().toSeconds());
    }

    /** Verifies the code; consumes it on success. */
    public void verify(String countryCode, String mobile, String code, String requestId, String clientIp) {
        String subject = subject(countryCode, mobile);
        try {
            if (clientIp != null && !rateLimiter.tryAcquire("otp-verify-ip:" + clientIp,
                    config.maxRequestsPerIpPerHour() * config.maxAttempts(), Duration.ofHours(1))) {
                throw new ApiException(ErrorCode.OTP_RATE_LIMITED);
            }
            String effectiveRequestId = StringUtils.hasText(requestId)
                    ? requestId : redis.opsForValue().get("otp:latest:" + subject);
            if (effectiveRequestId == null) {
                throw new ApiException(ErrorCode.OTP_EXPIRED);
            }
            String requestKey = "otp:req:" + effectiveRequestId;
            Map<Object, Object> data = redis.opsForHash().entries(requestKey);
            if (data.isEmpty() || !subject.equals(data.get("subject"))) {
                throw new ApiException(ErrorCode.OTP_EXPIRED);
            }
            Long attempts = redis.opsForHash().increment(requestKey, "attempts", 1);
            if (attempts == null || attempts > config.maxAttempts()) {
                throw new ApiException(ErrorCode.OTP_LOCKED);
            }
            String expected = (String) data.get("hash");
            if (code == null || !Hashing.constantTimeEquals(expected, hash(effectiveRequestId, code.trim()))) {
                long left = config.maxAttempts() - attempts;
                if (left <= 0) {
                    throw new ApiException(ErrorCode.OTP_LOCKED);
                }
                throw new ApiException(ErrorCode.OTP_INVALID,
                        "Incorrect code. " + left + (left == 1 ? " attempt" : " attempts") + " left.");
            }
            redis.delete(java.util.List.of(requestKey, "otp:latest:" + subject));
        } catch (DataAccessException e) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private String hash(String requestId, String code) {
        return Hashing.hmacSha256Hex(hashKey, requestId + ":" + code);
    }

    private static String subject(String countryCode, String mobile) {
        return countryCode.replace("+", "") + "-" + mobile;
    }

    private void safeDelete(String... keys) {
        try {
            redis.delete(java.util.List.of(keys));
        } catch (DataAccessException ignored) {
            // keys expire on their own
        }
    }
}
