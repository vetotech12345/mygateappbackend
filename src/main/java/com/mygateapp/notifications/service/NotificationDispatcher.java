package com.mygateapp.notifications.service;

import com.mygateapp.config.AppProperties;
import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.notifications.entity.NotificationOutbox;
import com.mygateapp.notifications.entity.UserDevice;
import com.mygateapp.notifications.push.PushSender;
import com.mygateapp.notifications.repository.DeviceRepository;
import com.mygateapp.notifications.repository.NotificationRepository;
import com.mygateapp.notifications.repository.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends pending push jobs. Jobs are dispatched right after the business transaction commits and, as a safety net,
 * by a polling job that retries failures with exponential backoff. Rows are claimed with
 * {@code FOR UPDATE SKIP LOCKED}, so several instances never send the same job twice.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final int BATCH_SIZE = 50;
    private static final int INVALID_TOKEN_LIMIT = 1;

    private final OutboxRepository outbox;
    private final NotificationRepository notifications;
    private final DeviceRepository devices;
    private final PushSender pushSender;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int maxAttempts;

    public NotificationDispatcher(OutboxRepository outbox, NotificationRepository notifications, DeviceRepository devices,
                                  PushSender pushSender, PlatformTransactionManager transactionManager, Clock clock,
                                  AppProperties properties) {
        this.outbox = outbox;
        this.notifications = notifications;
        this.devices = devices;
        this.pushSender = pushSender;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.maxAttempts = properties.push().maxAttempts();
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutboxCreated(NotificationService.OutboxCreated event) {
        for (UUID id : event.outboxIds()) {
            try {
                transactions.executeWithoutResult(status -> outbox.lockPending(id).ifPresent(this::process));
            } catch (RuntimeException ex) {
                log.warn("Immediate push dispatch failed for job {}; the retry job will pick it up: {}", id,
                        ex.getClass().getSimpleName());
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.jobs.push-retry-delay:PT15S}", initialDelayString = "PT20S")
    public void dispatchDue() {
        int processed;
        do {
            processed = transactions.execute(status -> {
                List<NotificationOutbox> due = outbox.lockDue(clock.instant(), BATCH_SIZE);
                due.forEach(this::process);
                return due.size();
            });
        } while (processed == BATCH_SIZE);
    }

    /** Must run inside a transaction holding the row lock of {@code job}. */
    void process(NotificationOutbox job) {
        Instant now = clock.instant();
        Notification notification = notifications.findById(job.getNotificationId()).orElse(null);
        if (notification == null) {
            finish(job, NotificationOutbox.Status.SKIPPED, "Notification deleted", now);
            return;
        }
        List<UserDevice> targets = devices.findByUserIdAndActiveTrue(notification.getUserId());
        if (targets.isEmpty()) {
            finish(job, NotificationOutbox.Status.SKIPPED, "No registered devices", now);
            return;
        }
        PushSender.PushMessage message = toMessage(notification);
        int delivered = 0;
        boolean retry = false;
        String lastError = null;
        for (UserDevice device : targets) {
            PushSender.Result result = pushSender.send(device.getToken(), message);
            switch (result.outcome()) {
                case DELIVERED -> {
                    delivered++;
                    device.setFailureCount(0);
                    device.setLastSeenAt(now);
                }
                case INVALID_TOKEN -> {
                    device.setFailureCount(device.getFailureCount() + 1);
                    if (device.getFailureCount() >= INVALID_TOKEN_LIMIT) {
                        device.setActive(false);
                        device.setDeactivatedReason("INVALID_TOKEN:" + result.error());
                    }
                    lastError = result.error();
                }
                case RETRYABLE_FAILURE -> {
                    retry = true;
                    lastError = result.error();
                }
                case PERMANENT_FAILURE -> lastError = result.error();
            }
        }
        job.setAttempts(job.getAttempts() + 1);
        if (delivered > 0) {
            finish(job, NotificationOutbox.Status.SENT, delivered < targets.size() ? lastError : null, now);
        } else if (retry && job.getAttempts() < maxAttempts) {
            job.setLastError(lastError);
            job.setNextAttemptAt(now.plus(backoff(job.getAttempts())));
        } else {
            finish(job, NotificationOutbox.Status.FAILED, lastError, now);
        }
    }

    private static Duration backoff(int attempts) {
        return Duration.ofSeconds(Math.min(15L * (1L << Math.min(attempts, 8)), 3600));
    }

    private static void finish(NotificationOutbox job, NotificationOutbox.Status status, String error, Instant now) {
        job.setStatus(status);
        job.setLastError(error);
        job.setProcessedAt(now);
    }

    /** Payload keys match the Flutter {@code PushMessage.fromData} parser. */
    static PushSender.PushMessage toMessage(Notification notification) {
        Map<String, String> data = new HashMap<>();
        data.put("type", notification.getType().name());
        data.put("notificationId", notification.getId().toString());
        if (notification.getReferenceId() != null) {
            data.put("referenceId", notification.getReferenceId().toString());
        }
        if (notification.getReferenceType() != null) {
            data.put("referenceType", notification.getReferenceType().name());
        }
        return new PushSender.PushMessage(notification.getTitle(), notification.getBody(), data,
                notification.getType().mandatory());
    }
}
