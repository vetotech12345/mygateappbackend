package com.mygateapp.notifications.push;

import java.util.Map;

/** Delivers one push message to one device. Implementations must never log full tokens or message secrets. */
public interface PushSender {

    record PushMessage(String title, String body, Map<String, String> data, boolean highPriority) {
    }

    enum Outcome { DELIVERED, INVALID_TOKEN, RETRYABLE_FAILURE, PERMANENT_FAILURE }

    record Result(Outcome outcome, String error) {

        public static Result delivered() {
            return new Result(Outcome.DELIVERED, null);
        }
    }

    Result send(String deviceToken, PushMessage message);

    /** Short name for logs and health information. */
    String name();
}
