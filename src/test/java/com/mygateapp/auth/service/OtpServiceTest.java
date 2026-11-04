package com.mygateapp.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mygateapp.auth.sms.SmsSender;
import com.mygateapp.common.Hashing;
import com.mygateapp.common.web.RateLimiter;
import com.mygateapp.config.AppProperties;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.util.unit.DataSize;

/** OTP rules with Redis mocked out: hashing, attempt limiting, single use and rate limits. */
class OtpServiceTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-0001";

    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashes;
    private ValueOperations<String, String> values;
    private RateLimiter rateLimiter;
    private SmsSender sms;
    private OtpService service;
    private final Map<Object, Object> stored = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hashes = mock(HashOperations.class);
        values = mock(ValueOperations.class);
        rateLimiter = mock(RateLimiter.class);
        sms = mock(SmsSender.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        AppProperties properties = new AppProperties(
                new AppProperties.Jwt(SECRET, "test", Duration.ofMinutes(15), Duration.ofDays(30)),
                new AppProperties.Otp(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(30), 5, 30, null),
                new AppProperties.Sms("mock", null, null, null, null),
                new AppProperties.Push("log", null, 5),
                new AppProperties.Cors(List.of()),
                new AppProperties.RateLimit(false, 100),
                new AppProperties.Files("x", DataSize.ofMegabytes(5), Duration.ofHours(1), null),
                new AppProperties.Idempotency(Duration.ofHours(24)));
        service = new OtpService(redis, rateLimiter, sms, properties);
    }

    @Test
    void issuedCodeIsSentBySmsAndOnlyItsHmacIsStored() {
        OtpService.Challenge challenge = service.issue("+91", "9876543210");
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sms).sendOtp(eq("+91"), eq("9876543210"), code.capture(), eq(5L));
        assertThat(code.getValue()).matches("\\d{6}");
        ArgumentCaptor<Map<Object, Object>> saved = captureStoredHash();
        assertThat(saved.getValue().get("hash")).isNotEqualTo(code.getValue());
        assertThat(saved.getValue().get("hash")).isEqualTo(
                Hashing.hmacSha256Hex(Hashing.deriveKey(SECRET, "otp"), challenge.requestId() + ":" + code.getValue()));
        assertThat(challenge.expiresInSeconds()).isEqualTo(300);
        assertThat(challenge.resendAfterSeconds()).isEqualTo(30);
    }

    @Test
    void correctCodeIsConsumed() {
        OtpService.Challenge challenge = service.issue("+91", "9876543210");
        String code = sentCode();
        prepareStoredChallenge(challenge, 1L);
        service.verify("+91", "9876543210", code, challenge.requestId(), "10.0.0.1");
        verify(redis).delete(List.of("otp:req:" + challenge.requestId(), "otp:latest:91-9876543210"));
    }

    @Test
    void wrongCodeReportsAttemptsLeft() {
        OtpService.Challenge challenge = service.issue("+91", "9876543210");
        prepareStoredChallenge(challenge, 2L);
        assertThatThrownBy(() -> service.verify("+91", "9876543210", "000000", challenge.requestId(), null))
                .isInstanceOf(ApiException.class)
                .hasMessage("Incorrect code. 3 attempts left.")
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.OTP_INVALID);
    }

    @Test
    void sixthAttemptIsLockedEvenWithTheRightCode() {
        OtpService.Challenge challenge = service.issue("+91", "9876543210");
        String code = sentCode();
        prepareStoredChallenge(challenge, 6L);
        assertThatThrownBy(() -> service.verify("+91", "9876543210", code, challenge.requestId(), null))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.OTP_LOCKED);
    }

    @Test
    void unknownOrForeignRequestIsExpired() {
        when(hashes.entries("otp:req:missing")).thenReturn(Map.of());
        assertThatThrownBy(() -> service.verify("+91", "9876543210", "123456", "missing", null))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.OTP_EXPIRED);

        OtpService.Challenge challenge = service.issue("+91", "9876543210");
        prepareStoredChallenge(challenge, 1L);
        // The same request id presented with another number does not match.
        assertThatThrownBy(() -> service.verify("+91", "9999999999", "123456", challenge.requestId(), null))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.OTP_EXPIRED);
    }

    @Test
    void rateLimitsAndCooldownBlockNewRequests() {
        when(redis.getExpire("otp:cooldown:91-9876543210")).thenReturn(12L);
        assertThatThrownBy(() -> service.checkRequestLimits("+91", "9876543210", "10.0.0.1"))
                .hasMessage("Please wait 12 seconds before requesting a new code.");

        when(redis.getExpire(anyString())).thenReturn(-2L);
        when(rateLimiter.tryAcquire(eq("otp-mobile:91-9876543210"), anyInt(), any(Duration.class))).thenReturn(false);
        assertThatThrownBy(() -> service.checkRequestLimits("+91", "9876543210", "10.0.0.1"))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.OTP_RATE_LIMITED);
    }

    @Test
    void failedSmsDeliveryRemovesTheChallenge() {
        org.mockito.Mockito.doThrow(new ApiException(ErrorCode.OTP_DELIVERY_FAILED))
                .when(sms).sendOtp(anyString(), anyString(), anyString(), anyLong());
        assertThatThrownBy(() -> service.issue("+91", "9876543210"))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.OTP_DELIVERY_FAILED);
        verify(redis).delete(org.mockito.ArgumentMatchers.<List<String>>any());
        verify(values, never()).get(anyString());
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<Object, Object>> captureStoredHash() {
        ArgumentCaptor<Map<Object, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hashes).putAll(anyString(), captor.capture());
        return captor;
    }

    private String sentCode() {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sms).sendOtp(anyString(), anyString(), code.capture(), anyLong());
        return code.getValue();
    }

    private void prepareStoredChallenge(OtpService.Challenge challenge, long attemptsAfterIncrement) {
        Map<Object, Object> saved = captureStoredHash().getValue();
        stored.clear();
        stored.putAll(saved);
        String key = "otp:req:" + challenge.requestId();
        when(hashes.entries(key)).thenReturn(stored);
        when(hashes.increment(key, "attempts", 1)).thenReturn(attemptsAfterIncrement);
    }
}
