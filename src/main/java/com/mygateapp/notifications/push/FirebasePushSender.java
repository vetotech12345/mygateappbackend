package com.mygateapp.notifications.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Firebase Cloud Messaging through the Firebase Admin SDK. Credentials are read from a service-account JSON file
 * whose path comes from {@code FIREBASE_CREDENTIALS_PATH}; the file is never exposed or logged.
 */
public class FirebasePushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FirebasePushSender.class);
    private static final String APP_NAME = "mygateapp-push";

    private final FirebaseMessaging messaging;

    public FirebasePushSender(String credentialsPath) {
        this.messaging = FirebaseMessaging.getInstance(initialize(credentialsPath));
    }

    private static FirebaseApp initialize(String credentialsPath) {
        for (FirebaseApp app : FirebaseApp.getApps()) {
            if (APP_NAME.equals(app.getName())) {
                return app;
            }
        }
        try (InputStream in = Files.newInputStream(Path.of(credentialsPath))) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .build();
            return FirebaseApp.initializeApp(options, APP_NAME);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read Firebase credentials from FIREBASE_CREDENTIALS_PATH", e);
        }
    }

    @Override
    public Result send(String deviceToken, PushMessage message) {
        Message fcmMessage = Message.builder()
                .setToken(deviceToken)
                .setNotification(com.google.firebase.messaging.Notification.builder()
                        .setTitle(message.title())
                        .setBody(message.body())
                        .build())
                .putAllData(message.data())
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(message.highPriority() ? AndroidConfig.Priority.HIGH : AndroidConfig.Priority.NORMAL)
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .putHeader("apns-priority", message.highPriority() ? "10" : "5")
                        .setAps(Aps.builder().setSound("default").build())
                        .build())
                .build();
        try {
            messaging.send(fcmMessage);
            return Result.delivered();
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            log.warn("FCM send failed: {}", code);
            if (code == null) {
                return new Result(Outcome.RETRYABLE_FAILURE, "FCM error");
            }
            return switch (code) {
                case UNREGISTERED, SENDER_ID_MISMATCH -> new Result(Outcome.INVALID_TOKEN, code.name());
                case INVALID_ARGUMENT -> new Result(Outcome.INVALID_TOKEN, code.name());
                case UNAVAILABLE, INTERNAL, QUOTA_EXCEEDED -> new Result(Outcome.RETRYABLE_FAILURE, code.name());
                default -> new Result(Outcome.PERMANENT_FAILURE, code.name());
            };
        } catch (RuntimeException e) {
            log.warn("FCM send failed: {}", e.getClass().getSimpleName());
            return new Result(Outcome.RETRYABLE_FAILURE, e.getClass().getSimpleName());
        }
    }

    @Override
    public String name() {
        return "firebase";
    }
}
