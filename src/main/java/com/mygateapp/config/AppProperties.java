package com.mygateapp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Typed application configuration ({@code app.*}). Every value can be overridden with an environment variable;
 * see {@code .env.example}. Secrets have no defaults outside the dev/test profiles.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Otp otp,
        @Valid @NotNull Sms sms,
        @Valid @NotNull Push push,
        @Valid @NotNull Cors cors,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Files files,
        @Valid @NotNull Idempotency idempotency) {

    /** JWT access tokens (HS256) and opaque refresh tokens. */
    public record Jwt(
            @NotBlank String secret,
            @NotBlank String issuer,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl) {
    }

    /**
     * OTP rules. {@code fixedCode} makes every OTP equal to a known value; it is only allowed with the mock SMS
     * provider outside production (enforced by {@link ProductionSafetyCheck}).
     */
    public record Otp(
            @Min(4) int length,
            @NotNull Duration ttl,
            @Min(1) int maxAttempts,
            @NotNull Duration resendCooldown,
            @Min(1) int maxRequestsPerMobilePerHour,
            @Min(1) int maxRequestsPerIpPerHour,
            String fixedCode) {
    }

    /** SMS delivery: {@code mock} (dev/test only) or {@code twilio}. */
    public record Sms(@NotBlank String provider, String accountId, String apiKey, String senderId, String baseUrl) {
    }

    /** Push delivery: {@code log} (dev/test) or {@code firebase}. */
    public record Push(@NotBlank String provider, String firebaseCredentialsPath, @Min(1) int maxAttempts) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record RateLimit(boolean enabled, @Min(1) int requestsPerMinute) {
    }

    /**
     * Uploaded files. Images are served through short-lived signed URLs built from {@code publicBaseUrl}
     * (or the current request when empty).
     */
    public record Files(@NotBlank String storageDir, @NotNull DataSize maxSize, @NotNull Duration urlTtl, String publicBaseUrl) {
    }

    public record Idempotency(@NotNull Duration ttl) {
    }
}
