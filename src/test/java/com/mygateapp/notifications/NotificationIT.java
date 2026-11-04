package com.mygateapp.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mygateapp.support.IntegrationTest;
import com.mygateapp.support.World;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/** In-app notifications, devices, preferences and the push outbox (delivered through the log provider in tests). */
class NotificationIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    World world;

    @BeforeEach
    void setUp() throws Exception {
        world = worlds().build("Notification Society");
    }

    private String walkIn() throws Exception {
        return sendOk("POST", "/api/v1/visitors", Map.of("name", "Push Guest", "mobile", "9822011122", "type", "GUEST",
                "flatId", world.flat101(), "purpose", "Visit"), world.guardToken()).get("id").asString();
    }

    @Test
    void pushIsDispatchedToRegisteredDevicesAfterCommit() throws Exception {
        JsonNode device = sendOk("POST", "/api/v1/devices", Map.of("token", "fid-" + UUID.randomUUID(), "platform", "ANDROID"),
                world.resident101Token());
        assertThat(device.has("token")).isFalse();
        walkIn();
        UUID userId = world.resident101UserId();
        eventually(() -> "SENT".equals(jdbc.queryForObject("""
                SELECT o.status FROM notification_outbox o JOIN notifications n ON n.id = o.notification_id
                WHERE n.user_id = ? AND n.type = 'VISITOR_APPROVAL_REQUEST'
                """, String.class, userId)));
    }

    @Test
    void notificationsWithoutDevicesAreKeptInAppAndSkippedForPush() throws Exception {
        walkIn();
        eventually(() -> "SKIPPED".equals(jdbc.queryForObject("""
                SELECT o.status FROM notification_outbox o JOIN notifications n ON n.id = o.notification_id
                WHERE n.user_id = ? AND n.type = 'VISITOR_APPROVAL_REQUEST'
                """, String.class, world.resident101UserId())));
        getWith("/api/v1/notifications/unread-count", world.resident101Token()).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void readStateUnreadCountAndOwnership() throws Exception {
        walkIn();
        walkIn();
        getWith("/api/v1/notifications/unread-count", world.resident101Token()).andExpect(jsonPath("$.count").value(2));
        String id = sendOk("GET", "/api/v1/notifications", null, world.resident101Token()).get("content").get(0).get("id").asString();
        // Another user cannot mark it.
        send("POST", "/api/v1/notifications/" + id + "/read", null, world.resident102Token()).andExpect(status().isNotFound());
        send("PATCH", "/api/v1/notifications/" + id + "/read", null, world.resident101Token()).andExpect(status().isNoContent());
        getWith("/api/v1/notifications/unread-count", world.resident101Token()).andExpect(jsonPath("$.count").value(1));
        send("POST", "/api/v1/notifications/read-all", null, world.resident101Token()).andExpect(status().isNoContent());
        getWith("/api/v1/notifications/unread-count", world.resident101Token()).andExpect(jsonPath("$.count").value(0));
        getWith("/api/v1/notifications", world.resident101Token()).andExpect(jsonPath("$.content[0].read").value(true));
    }

    @Test
    void preferencesDisablePushButApprovalRequestsAreMandatory() throws Exception {
        JsonNode prefs = sendOk("PUT", "/api/v1/users/me/notification-preferences",
                Map.of("VISITOR_APPROVAL_REQUEST", false, "ANNOUNCEMENT", false), world.resident101Token());
        assertThat(prefs.get("VISITOR_APPROVAL_REQUEST").asBoolean()).isTrue();
        assertThat(prefs.get("ANNOUNCEMENT").asBoolean()).isFalse();
        getWith("/api/v1/users/me/notification-preferences", world.resident101Token())
                .andExpect(jsonPath("$.ANNOUNCEMENT").value(false))
                .andExpect(jsonPath("$.DELIVERY_ARRIVAL").value(true));

        sendOk("POST", "/api/v1/devices", Map.of("token", "fid-" + UUID.randomUUID(), "platform", "IOS"), world.resident101Token());
        sendOk("POST", "/api/v1/notices", Map.of("title", "Diwali party", "body", "Join us", "category", "EVENT",
                "publish", true), world.adminToken());
        eventually(() -> "SKIPPED".equals(jdbc.queryForObject("""
                SELECT o.status FROM notification_outbox o JOIN notifications n ON n.id = o.notification_id
                WHERE n.user_id = ? AND n.type = 'ANNOUNCEMENT'
                """, String.class, world.resident101UserId())));
        // The in-app notification still exists.
        getWith("/api/v1/notifications", world.resident101Token()).andExpect(jsonPath("$.content[0].type").value("ANNOUNCEMENT"));
    }

    @Test
    void devicesCanBeRemovedByIdOrTokenOnlyByTheirOwner() throws Exception {
        String token = "fid-" + UUID.randomUUID();
        String id = sendOk("POST", "/api/v1/devices", Map.of("token", token, "platform", "ANDROID"), world.resident101Token())
                .get("id").asString();
        send("DELETE", "/api/v1/devices/" + id, null, world.resident102Token()).andExpect(status().isNotFound());
        send("DELETE", "/api/v1/devices/" + token, null, world.resident101Token()).andExpect(status().isNoContent());
        send("DELETE", "/api/v1/devices/" + id, null, world.resident101Token()).andExpect(status().isNotFound());
        // A token re-registered by another account moves to that account.
        sendOk("POST", "/api/v1/devices", Map.of("token", token, "platform", "ANDROID"), world.resident101Token());
        sendOk("POST", "/api/v1/devices", Map.of("token", token, "platform", "ANDROID"), world.resident102Token());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_devices WHERE token = ?", Integer.class, token)).isEqualTo(1);
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
                // not written yet
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Condition not met within 10 seconds");
    }
}
