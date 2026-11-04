package com.mygateapp.auth.sms;

import com.mygateapp.common.MobileNumbers;
import com.mygateapp.config.AppProperties;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Chooses the SMS provider from {@code app.sms.provider} (SMS_PROVIDER). */
@Configuration
public class SmsProviders {

    private static final Logger log = LoggerFactory.getLogger(SmsProviders.class);

    @Bean
    SmsSender smsSender(AppProperties properties) {
        AppProperties.Sms sms = properties.sms();
        return switch (sms.provider().toLowerCase()) {
            case "mock" -> {
                log.warn("SMS provider: MOCK (development only). OTPs are not delivered; use the configured fixed OTP.");
                yield new MockSmsSender();
            }
            case "twilio" -> {
                if (!StringUtils.hasText(sms.accountId()) || !StringUtils.hasText(sms.apiKey())
                        || !StringUtils.hasText(sms.senderId())) {
                    throw new IllegalStateException("SMS_PROVIDER=twilio requires SMS_ACCOUNT_ID, SMS_API_KEY and SMS_SENDER_ID");
                }
                yield new TwilioSmsSender(sms, RestClient.builder());
            }
            case "none" -> {
                log.error("No SMS provider configured (SMS_PROVIDER=none): OTP login will fail until one is configured.");
                yield new DisabledSmsSender();
            }
            default -> throw new IllegalStateException("Unknown SMS_PROVIDER: " + sms.provider());
        };
    }

    /** DEVELOPMENT ONLY. Pretends to send; the code is NOT logged. Rejected in the prod profile. */
    static final class MockSmsSender implements SmsSender {

        @Override
        public void sendOtp(String countryCode, String mobile, String code, long validMinutes) {
            log.info("[sms:mock] OTP issued for {} {} (code not logged; use the configured development OTP)",
                    countryCode, MobileNumbers.mask(mobile));
        }

        @Override
        public String name() {
            return "mock";
        }
    }

    static final class DisabledSmsSender implements SmsSender {

        @Override
        public void sendOtp(String countryCode, String mobile, String code, long validMinutes) {
            throw new ApiException(ErrorCode.OTP_DELIVERY_FAILED);
        }

        @Override
        public String name() {
            return "none";
        }
    }

    /** Twilio Programmable Messaging REST API. Untested against the live service in this repository. */
    static final class TwilioSmsSender implements SmsSender {

        private final RestClient client;
        private final AppProperties.Sms config;

        TwilioSmsSender(AppProperties.Sms config, RestClient.Builder builder) {
            this.config = config;
            String credentials = Base64.getEncoder().encodeToString(
                    (config.accountId() + ":" + config.apiKey()).getBytes(StandardCharsets.UTF_8));
            this.client = builder
                    .baseUrl(config.baseUrl())
                    .defaultHeader("Authorization", "Basic " + credentials)
                    .build();
        }

        @Override
        public void sendOtp(String countryCode, String mobile, String code, long validMinutes) {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("To", countryCode + mobile);
            if (config.senderId().startsWith("MG")) {
                form.add("MessagingServiceSid", config.senderId());
            } else {
                form.add("From", config.senderId());
            }
            form.add("Body", "Your MyGateApp verification code is " + code + ". It expires in " + validMinutes
                    + " minutes. Do not share it with anyone.");
            try {
                client.post()
                        .uri("/2010-04-01/Accounts/{account}/Messages.json", config.accountId())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientException e) {
                log.error("SMS delivery via Twilio failed for {}: {}", MobileNumbers.mask(mobile), e.getClass().getSimpleName());
                throw new ApiException(ErrorCode.OTP_DELIVERY_FAILED);
            }
        }

        @Override
        public String name() {
            return "twilio";
        }
    }
}
