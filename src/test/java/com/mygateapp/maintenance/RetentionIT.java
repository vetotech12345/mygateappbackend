package com.mygateapp.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.mygateapp.support.IntegrationTest;
import com.mygateapp.support.MutableClock;
import com.mygateapp.support.World;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Personal-data retention: old visits and deliveries lose personal data but keep their history. */
class RetentionIT extends IntegrationTest {

    @Autowired
    MutableClock clock;

    @Autowired
    MaintenanceJobs maintenance;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void oldVisitsAndDeliveriesAreAnonymised() throws Exception {
        World world = worlds().build("Retention Society");
        String visitorId = sendOk("POST", "/api/v1/visitors", Map.of("name", "Old Visitor", "mobile", "9822011122",
                "type", "GUEST", "flatId", world.flat101(), "purpose", "Visit", "vehicleNumber", "MH12AB1234"),
                world.guardToken()).get("id").asString();
        String deliveryId = sendOk("POST", "/api/v1/deliveries", Map.of("category", "COURIER", "company", "ParcelGo",
                "agentName", "Sanjay Kumar", "agentMobile", "9000011111", "flatId", world.flat101()), world.guardToken())
                .get("id").asString();

        clock.advance(Duration.ofDays(400));
        maintenance.applyRetention();
        clock.reset();

        assertThat(jdbc.queryForMap("SELECT name, mobile, vehicle_number, anonymized_at FROM visitors WHERE id = ?::uuid",
                visitorId))
                .containsEntry("name", "Visitor (redacted)")
                .containsEntry("mobile", null)
                .containsEntry("vehicle_number", null)
                .extractingByKey("anonymized_at").isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visitor_events WHERE visitor_id = ?::uuid", Integer.class, visitorId))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT agent_name, agent_mobile FROM deliveries WHERE id = ?::uuid", deliveryId))
                .containsEntry("agent_name", "Delivery agent")
                .containsEntry("agent_mobile", null);
    }
}
