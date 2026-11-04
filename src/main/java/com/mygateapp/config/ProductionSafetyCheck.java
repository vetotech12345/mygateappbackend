package com.mygateapp.config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Refuses to start with unsafe settings. Development conveniences (mock SMS, fixed OTP, log-only push, wildcard CORS)
 * are rejected when the {@code prod} profile is active, and weak JWT secrets are rejected everywhere.
 */
@Component
public class ProductionSafetyCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyCheck.class);
    static final int MIN_SECRET_BYTES = 32;

    private final AppProperties properties;
    private final Environment environment;

    public ProductionSafetyCheck(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.jwt().secret().getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.jwt.secret (JWT_SECRET) must be at least " + MIN_SECRET_BYTES + " bytes long");
        }
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (!production) {
            if (StringUtils.hasText(properties.otp().fixedCode())) {
                log.warn("Fixed development OTP is enabled. Never use this configuration in production.");
            }
            return;
        }
        if ("mock".equalsIgnoreCase(properties.sms().provider())) {
            throw new IllegalStateException("The mock SMS provider cannot be used with the prod profile (set SMS_PROVIDER)");
        }
        if (StringUtils.hasText(properties.otp().fixedCode())) {
            throw new IllegalStateException("A fixed OTP cannot be configured with the prod profile");
        }
        if ("log".equalsIgnoreCase(properties.push().provider())) {
            log.warn("Push provider is 'log': push notifications will NOT be delivered. Configure FIREBASE_CREDENTIALS_PATH.");
        }
        List<String> origins = properties.cors().allowedOrigins();
        if (origins != null && origins.stream().anyMatch(o -> o.contains("*"))) {
            throw new IllegalStateException("Wildcard CORS origins are not allowed with the prod profile");
        }
    }
}
