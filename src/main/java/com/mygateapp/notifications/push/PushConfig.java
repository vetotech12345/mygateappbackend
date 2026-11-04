package com.mygateapp.notifications.push;

import com.mygateapp.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    @Bean
    PushSender pushSender(AppProperties properties) {
        AppProperties.Push push = properties.push();
        if ("firebase".equalsIgnoreCase(push.provider())) {
            if (!StringUtils.hasText(push.firebaseCredentialsPath())) {
                throw new IllegalStateException("PUSH_PROVIDER=firebase requires FIREBASE_CREDENTIALS_PATH");
            }
            log.info("Push notifications: Firebase Cloud Messaging");
            return new FirebasePushSender(push.firebaseCredentialsPath());
        }
        log.info("Push notifications: development log provider (no messages leave the server)");
        return new LoggingPushSender();
    }
}
