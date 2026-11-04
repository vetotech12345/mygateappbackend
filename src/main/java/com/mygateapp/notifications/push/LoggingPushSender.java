package com.mygateapp.notifications.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development provider used when Firebase is not configured: records that a push would have been sent.
 * Logs only the notification type and the last characters of the token.
 */
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public Result send(String deviceToken, PushMessage message) {
        String tail = deviceToken.length() > 6 ? deviceToken.substring(deviceToken.length() - 6) : "******";
        log.info("[push:log] type={} device=…{} title=\"{}\"", message.data().get("type"), tail, message.title());
        return Result.delivered();
    }

    @Override
    public String name() {
        return "log";
    }
}
