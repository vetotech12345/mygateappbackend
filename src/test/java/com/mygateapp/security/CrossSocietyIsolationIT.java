package com.mygateapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.mygateapp.support.IntegrationTest;
import com.mygateapp.support.World;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tenant isolation: an authenticated user of society B (admin, guard or resident) must not read or change any record
 * of society A by using its ids. Reads return 404 (existence is not revealed) and writes are refused.
 */
class CrossSocietyIsolationIT extends IntegrationTest {

    World a;
    World b;
    String visitorId;
    String inviteId;
    String deliveryId;
    String staffId;
    String complaintId;
    String noticeId;
    String residentId;

    @BeforeEach
    void twoSocieties() throws Exception {
        a = worlds().build("Society A");
        b = worlds().build("Society B");
        visitorId = sendOk("POST", "/api/v1/visitors", Map.of("name", "Secret Guest", "mobile", "9822011122", "type", "GUEST",
                "flatId", a.flat101(), "purpose", "Visit"), a.guardToken()).get("id").asString();
        Instant now = Instant.now();
        inviteId = sendOk("POST", "/api/v1/visitors/invites", Map.of("name", "Invited Guest", "mobile", "9811122233",
                "type", "GUEST", "flatId", a.flat101(), "purpose", "Visit", "validFrom", now.toString(),
                "validUntil", now.plus(Duration.ofHours(2)).toString()), a.resident101Token()).get("id").asString();
        deliveryId = sendOk("POST", "/api/v1/deliveries", Map.of("category", "FOOD", "company", "QuickBite",
                "agentName", "Arjun", "flatId", a.flat101()), a.guardToken()).get("id").asString();
        staffId = sendOk("POST", "/api/v1/domestic-staff", Map.of("name", "Sunita Devi", "mobile", "9123456780",
                "category", "MAID", "flatIds", List.of(a.flat101())), a.adminToken()).get("id").asString();
        complaintId = sendOk("POST", "/api/v1/complaints", Map.of("category", "LIFT", "title", "Lift not working",
                "description", "The lift has been stuck since morning.", "flatId", a.flat101()), a.resident101Token())
                .get("id").asString();
        noticeId = sendOk("POST", "/api/v1/notices", Map.of("title", "Private notice", "body", "Only for A", "category",
                "GENERAL", "audience", "ALL", "publish", true), a.adminToken()).get("id").asString();
        residentId = sendOk("GET", "/api/v1/residents?q=Aarav", null, a.adminToken()).get("content").get(0).get("id").asString();
    }

    @Test
    void readsByIdReturnNotFoundForEveryRoleOfAnotherSociety() throws Exception {
        List<String> urls = List.of(
                "/api/v1/visitors/" + visitorId,
                "/api/v1/visitors/" + inviteId,
                "/api/v1/deliveries/" + deliveryId,
                "/api/v1/domestic-staff/" + staffId,
                "/api/v1/domestic-staff/" + staffId + "/attendance",
                "/api/v1/complaints/" + complaintId,
                "/api/v1/notices/" + noticeId,
                "/api/v1/residents/" + residentId,
                "/api/v1/flats/" + a.flat101(),
                "/api/v1/guards/" + a.guardId(),
                "/api/v1/societies/" + a.societyId());
        for (String token : List.of(b.adminToken(), b.guardToken(), b.resident101Token())) {
            for (String url : urls) {
                int status = send("GET", url, null, token).andReturn().getResponse().getStatus();
                assertThat(status).as("GET %s", url).isIn(403, 404);
            }
        }
    }

    @Test
    void writesOnAnotherSocietysRecordsAreRefused() throws Exception {
        assertDenied("POST", "/api/v1/visitors/" + visitorId + "/approve", null, b.resident101Token());
        assertDenied("POST", "/api/v1/visitors/" + visitorId + "/reject", null, b.resident101Token());
        assertDenied("POST", "/api/v1/visitors/" + visitorId + "/cancel", null, b.guardToken());
        assertDenied("POST", "/api/v1/visitors/" + visitorId + "/entry", null, b.guardToken());
        assertDenied("POST", "/api/v1/visitors/" + inviteId + "/entry", null, b.guardToken());
        assertDenied("POST", "/api/v1/deliveries/" + deliveryId + "/approve", null, b.resident101Token());
        assertDenied("POST", "/api/v1/deliveries/" + deliveryId + "/entry", null, b.guardToken());
        assertDenied("POST", "/api/v1/domestic-staff/" + staffId + "/check-in", null, b.guardToken());
        assertDenied("PUT", "/api/v1/domestic-staff/" + staffId, Map.of("name", "Hacked", "mobile", "9123456780",
                "category", "MAID"), b.adminToken());
        assertDenied("PATCH", "/api/v1/complaints/" + complaintId + "/status", Map.of("status", "CLOSED"), b.adminToken());
        assertDenied("POST", "/api/v1/complaints/" + complaintId + "/comments", Map.of("body", "hello"), b.adminToken());
        assertDenied("DELETE", "/api/v1/notices/" + noticeId, null, b.adminToken());
        assertDenied("POST", "/api/v1/residents/" + residentId + "/end", null, b.adminToken());
        assertDenied("PATCH", "/api/v1/flats/" + a.flat101() + "/status", Map.of("status", "INACTIVE"), b.adminToken());
        assertDenied("PATCH", "/api/v1/guards/" + a.guardId() + "/status", Map.of("status", "INACTIVE"), b.adminToken());
        assertDenied("POST", "/api/v1/complaints", Map.of("category", "LIFT", "title", "Fake complaint",
                "description", "Raised for someone else's flat", "flatId", a.flat101()), b.resident101Token());
        assertDenied("POST", "/api/v1/visitors/invites", Map.of("name", "Fake Invite", "mobile", "9811122233", "type",
                "GUEST", "flatId", a.flat101(), "purpose", "Visit", "validFrom", Instant.now().toString(),
                "validUntil", Instant.now().plusSeconds(3600).toString()), b.resident101Token());
        // A's pass codes cannot be verified at B's gate.
        String passCode = sendOk("GET", "/api/v1/visitors/" + inviteId, null, a.resident101Token()).get("passCode").asString();
        send("POST", "/api/v1/visitors/verify-pass", Map.of("passCode", passCode), b.guardToken())
                .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));

        // Nothing changed in society A.
        JsonNode visitor = sendOk("GET", "/api/v1/visitors/" + visitorId, null, a.resident101Token());
        assertThat(visitor.get("status").asString()).isEqualTo("PENDING");
        assertThat(sendOk("GET", "/api/v1/complaints/" + complaintId, null, a.resident101Token()).get("status").asString())
                .isEqualTo("OPEN");
    }

    @Test
    void listsNeverContainAnotherSocietysRecords() throws Exception {
        for (String url : List.of("/api/v1/visitors", "/api/v1/deliveries", "/api/v1/domestic-staff", "/api/v1/notices",
                "/api/v1/guards/activity")) {
            JsonNode page = sendOk("GET", url, null, b.guardToken());
            assertThat(page.get("content").valueStream().map(n -> n.get("id").asString()).toList())
                    .as(url).doesNotContain(visitorId, inviteId, deliveryId, staffId, noticeId);
        }
        assertThat(sendOk("GET", "/api/v1/complaints", null, b.adminToken()).get("totalElements").asInt()).isZero();
        assertThat(sendOk("GET", "/api/v1/residents", null, b.adminToken()).get("content").valueStream()
                .map(n -> n.get("id").asString()).toList()).doesNotContain(residentId);
        // The X-Society-Id header cannot be used to switch into a foreign society.
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/visitors")
                        .header("Authorization", "Bearer " + b.guardToken())
                        .header("X-Society-Id", a.societyId().toString()))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    private void assertDenied(String method, String url, Object body, String token) throws Exception {
        int status = send(method, url, body, token).andReturn().getResponse().getStatus();
        assertThat(status).as("%s %s", method, url).isIn(400, 403, 404);
    }
}
