package com.mygateapp.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

class ProductionSafetyCheckTest {

    private static AppProperties properties(String secret, String sms, String fixedOtp, List<String> origins) {
        return new AppProperties(
                new AppProperties.Jwt(secret, "mygateapp", Duration.ofMinutes(15), Duration.ofDays(30)),
                new AppProperties.Otp(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(30), 5, 30, fixedOtp),
                new AppProperties.Sms(sms, "acc", "key", "sender", "https://api.twilio.com"),
                new AppProperties.Push("firebase", "/secrets/firebase.json", 5),
                new AppProperties.Cors(origins),
                new AppProperties.RateLimit(true, 240),
                new AppProperties.Files("./data", DataSize.ofMegabytes(5), Duration.ofHours(1), null),
                new AppProperties.Idempotency(Duration.ofHours(24)));
    }

    private static MockEnvironment prod() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    private static final String GOOD_SECRET = "a-very-long-production-secret-value-0123456789";

    @Test
    void validProductionConfigurationStarts() {
        assertThatCode(() -> new ProductionSafetyCheck(
                properties(GOOD_SECRET, "twilio", null, List.of("https://admin.example.com")), prod()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void shortSecretsAreRejectedEverywhere() {
        assertThatThrownBy(() -> new ProductionSafetyCheck(properties("short", "mock", null, List.of()),
                new MockEnvironment()).afterPropertiesSet()).hasMessageContaining("at least 32 bytes");
    }

    @Test
    void developmentConveniencesAreRejectedInProduction() {
        assertThatThrownBy(() -> new ProductionSafetyCheck(properties(GOOD_SECRET, "mock", null, List.of()), prod())
                .afterPropertiesSet()).hasMessageContaining("mock SMS provider");
        assertThatThrownBy(() -> new ProductionSafetyCheck(properties(GOOD_SECRET, "twilio", "123456", List.of()), prod())
                .afterPropertiesSet()).hasMessageContaining("fixed OTP");
        assertThatThrownBy(() -> new ProductionSafetyCheck(properties(GOOD_SECRET, "twilio", null, List.of("*")), prod())
                .afterPropertiesSet()).hasMessageContaining("Wildcard CORS");
    }
}
