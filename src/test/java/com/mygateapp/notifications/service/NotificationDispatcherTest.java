package com.mygateapp.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mygateapp.config.AppProperties;
import com.mygateapp.notifications.entity.Notification;
import com.mygateapp.notifications.entity.NotificationOutbox;
import com.mygateapp.notifications.entity.UserDevice;
import com.mygateapp.notifications.enums.NotificationType;
import com.mygateapp.notifications.push.PushSender;
import com.mygateapp.notifications.repository.DeviceRepository;
import com.mygateapp.notifications.repository.NotificationRepository;
import com.mygateapp.notifications.repository.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;

/** Push outcomes: delivered, no devices, invalid tokens (device deactivated), retry with backoff, give up. */
class NotificationDispatcherTest {

    private final Instant now = Instant.parse("2026-11-04T10:00:00Z");
    private NotificationRepository notifications;
    private DeviceRepository devices;
    private PushSender sender;
    private NotificationDispatcher dispatcher;
    private Notification notification;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationRepository.class);
        devices = mock(DeviceRepository.class);
        sender = mock(PushSender.class);
        AppProperties properties = new AppProperties(
                new AppProperties.Jwt("x".repeat(40), "t", Duration.ofMinutes(15), Duration.ofDays(30)),
                new AppProperties.Otp(6, Duration.ofMinutes(5), 5, Duration.ZERO, 5, 5, null),
                new AppProperties.Sms("mock", null, null, null, null),
                new AppProperties.Push("log", null, 3),
                new AppProperties.Cors(List.of()),
                new AppProperties.RateLimit(false, 1),
                new AppProperties.Files("x", DataSize.ofMegabytes(1), Duration.ofHours(1), null),
                new AppProperties.Idempotency(Duration.ofHours(1)));
        dispatcher = new NotificationDispatcher(mock(OutboxRepository.class), notifications, devices, sender,
                mock(PlatformTransactionManager.class), Clock.fixed(now, ZoneOffset.UTC), properties);
        notification = new Notification();
        org.springframework.test.util.ReflectionTestUtils.setField(notification, "id", UUID.randomUUID());
        notification.setUserId(UUID.randomUUID());
        notification.setType(NotificationType.VISITOR_APPROVAL_REQUEST);
        notification.setTitle("Vikram Iyer is at the Main Gate");
        notification.setBody("Guest · Family visit");
        notification.setReferenceType(Notification.ReferenceType.VISITOR);
        notification.setReferenceId(UUID.randomUUID());
        when(notifications.findById(any())).thenReturn(Optional.of(notification));
    }

    private NotificationOutbox job() {
        NotificationOutbox job = new NotificationOutbox();
        job.setNotificationId(UUID.randomUUID());
        job.setNextAttemptAt(now);
        job.setCreatedAt(now);
        return job;
    }

    private UserDevice device() {
        UserDevice device = new UserDevice();
        device.setUserId(notification.getUserId());
        device.setToken("token-" + UUID.randomUUID());
        device.setPlatform(UserDevice.Platform.ANDROID);
        device.setLastSeenAt(now.minusSeconds(3600));
        return device;
    }

    @Test
    void deliveredToAtLeastOneDeviceIsSent() {
        UserDevice good = device();
        UserDevice stale = device();
        when(devices.findByUserIdAndActiveTrue(notification.getUserId())).thenReturn(List.of(good, stale));
        when(sender.send(any(), any())).thenReturn(PushSender.Result.delivered());
        when(sender.send(org.mockito.ArgumentMatchers.eq(stale.getToken()), any()))
                .thenReturn(new PushSender.Result(PushSender.Outcome.INVALID_TOKEN, "UNREGISTERED"));
        NotificationOutbox job = job();
        dispatcher.process(job);
        assertThat(job.getStatus()).isEqualTo(NotificationOutbox.Status.SENT);
        assertThat(stale.isActive()).isFalse();
        assertThat(stale.getDeactivatedReason()).isEqualTo("INVALID_TOKEN:UNREGISTERED");
        assertThat(good.isActive()).isTrue();
    }

    @Test
    void noDevicesIsSkipped() {
        when(devices.findByUserIdAndActiveTrue(any())).thenReturn(List.of());
        NotificationOutbox job = job();
        dispatcher.process(job);
        assertThat(job.getStatus()).isEqualTo(NotificationOutbox.Status.SKIPPED);
    }

    @Test
    void retryableFailuresBackOffThenFail() {
        when(devices.findByUserIdAndActiveTrue(any())).thenReturn(List.of(device()));
        when(sender.send(anyString(), any())).thenReturn(new PushSender.Result(PushSender.Outcome.RETRYABLE_FAILURE, "UNAVAILABLE"));
        NotificationOutbox job = job();
        dispatcher.process(job);
        assertThat(job.getStatus()).isEqualTo(NotificationOutbox.Status.PENDING);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getNextAttemptAt()).isAfter(now);
        dispatcher.process(job);
        dispatcher.process(job);
        assertThat(job.getStatus()).isEqualTo(NotificationOutbox.Status.FAILED);
        assertThat(job.getLastError()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void payloadMatchesTheFlutterPushHandler() {
        PushSender.PushMessage message = NotificationDispatcher.toMessage(notification);
        assertThat(message.data()).containsEntry("type", "VISITOR_APPROVAL_REQUEST")
                .containsEntry("referenceId", notification.getReferenceId().toString())
                .containsEntry("referenceType", "VISITOR")
                .containsKey("notificationId");
        assertThat(message.highPriority()).isTrue();
    }
}
