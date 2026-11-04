package com.mygateapp.visitors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mygateapp.maintenance.MaintenanceJobs;
import com.mygateapp.support.IntegrationTest;
import com.mygateapp.support.MutableClock;
import com.mygateapp.support.World;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** Phase C: the complete visitor lifecycle, its business rules and concurrency protection. */
class VisitorWorkflowIT extends IntegrationTest {

    @Autowired
    MutableClock clock;

    @Autowired
    MaintenanceJobs maintenance;

    World world;

    @BeforeEach
    void setUp() throws Exception {
        world = worlds().build("Visitor Society");
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private JsonNode walkIn(String name) throws Exception {
        return sendOk("POST", "/api/v1/visitors", Map.of("name", name, "mobile", "9822011122", "type", "GUEST",
                "flatId", world.flat101(), "purpose", "Family visit"), world.guardToken());
    }

    @Test
    void walkInIsApprovedEntersAndExits() throws Exception {
        JsonNode created = walkIn("Vikram Iyer");
        String id = created.get("id").asString();
        assertThat(created.get("status").asString()).isEqualTo("PENDING");
        assertThat(created.get("gate").get("name").asString()).isEqualTo("Main Gate");
        assertThat(created.get("createdBy").asString()).isEqualTo("Ramesh Yadav");

        // Only the flat's resident sees it.
        getWith("/api/v1/visitors?scope=PENDING", world.resident101Token())
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.totalElements").value(1));
        getWith("/api/v1/visitors?scope=PENDING", world.resident102Token())
                .andExpect(jsonPath("$.totalElements").value(0));
        getWith("/api/v1/visitors/" + id, world.resident102Token()).andExpect(status().isForbidden());

        // The resident got an approval request notification.
        getWith("/api/v1/notifications", world.resident101Token())
                .andExpect(jsonPath("$.content[0].type").value("VISITOR_APPROVAL_REQUEST"))
                .andExpect(jsonPath("$.content[0].referenceId").value(id))
                .andExpect(jsonPath("$.content[0].title").value("Vikram Iyer is at the Main Gate"));

        sendOk("POST", "/api/v1/visitors/" + id + "/approve", null, world.resident101Token());
        getWith("/api/v1/guards/pending-visitors", world.guardToken()).andExpect(jsonPath("$.totalElements").value(0));
        getWith("/api/v1/visitors?scope=EXPECTED", world.guardToken()).andExpect(jsonPath("$.content[0].id").value(id));
        getWith("/api/v1/notifications", world.guardToken())
                .andExpect(jsonPath("$.content[0].type").value("VISITOR_APPROVED"));

        JsonNode entered = sendOk("POST", "/api/v1/visitors/" + id + "/entry", null, world.guardToken());
        assertThat(entered.get("status").asString()).isEqualTo("CHECKED_IN");
        assertThat(entered.get("approvalStatus").asString()).isEqualTo("APPROVED");
        assertThat(entered.get("movementStatus").asString()).isEqualTo("INSIDE");
        getWith("/api/v1/guards/active-visitors", world.guardToken()).andExpect(jsonPath("$.content[0].id").value(id));

        JsonNode exited = sendOk("POST", "/api/v1/visitors/" + id + "/exit", null, world.guardToken());
        assertThat(exited.get("status").asString()).isEqualTo("CHECKED_OUT");
        assertThat(Instant.parse(exited.get("exitAt").asString())).isAfterOrEqualTo(Instant.parse(exited.get("entryAt").asString()));

        JsonNode history = exited.get("history");
        assertThat(history.valueStream().map(e -> e.get("status").asString()).toList())
                .containsExactly("PENDING", "APPROVED", "CHECKED_IN", "CHECKED_OUT");
        assertThat(history.get(1).get("actor").asString()).isEqualTo("Aarav Mehta");
    }

    @Test
    void rejectedVisitorCanNeverEnter() throws Exception {
        String id = walkIn("Unknown Person").get("id").asString();
        sendOk("POST", "/api/v1/visitors/" + id + "/reject", Map.of("reason", "Not expecting anyone"), world.resident101Token());
        send("POST", "/api/v1/visitors/" + id + "/entry", null, world.guardToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VISITOR_REJECTED"));
        send("POST", "/api/v1/visitors/" + id + "/approve", null, world.resident101Token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISITOR_ALREADY_DECIDED"));
        getWith("/api/v1/visitors/" + id, world.guardToken())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Not expecting anyone"));
    }

    @Test
    void gateMovementRulesAreEnforced() throws Exception {
        String id = walkIn("Rule Tester").get("id").asString();
        send("POST", "/api/v1/visitors/" + id + "/entry", null, world.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISITOR_PENDING_APPROVAL"));
        sendOk("POST", "/api/v1/visitors/" + id + "/approve", null, world.resident101Token());
        send("POST", "/api/v1/visitors/" + id + "/exit", null, world.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISITOR_NOT_INSIDE"));
        sendOk("POST", "/api/v1/visitors/" + id + "/entry", null, world.guardToken());
        send("POST", "/api/v1/visitors/" + id + "/entry", null, world.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISITOR_ALREADY_INSIDE"));
        sendOk("POST", "/api/v1/visitors/" + id + "/exit", null, world.guardToken());
        send("POST", "/api/v1/visitors/" + id + "/exit", null, world.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISITOR_ALREADY_EXITED"));
        send("POST", "/api/v1/visitors/" + id + "/entry", null, world.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VISIT_CLOSED"));
    }

    @Test
    void concurrentDuplicateEntryRecordsExactlyOnce() throws Exception {
        String id = walkIn("Race Condition").get("id").asString();
        sendOk("POST", "/api/v1/visitors/" + id + "/approve", null, world.resident101Token());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(() -> send("POST", "/api/v1/visitors/" + id + "/entry", null, world.guardToken())
                        .andReturn().getResponse().getStatus());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
            assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(3);
        } finally {
            pool.shutdownNow();
        }
        JsonNode visitor = sendOk("GET", "/api/v1/visitors/" + id, null, world.guardToken());
        assertThat(visitor.get("history").valueStream().filter(e -> "CHECKED_IN".equals(e.get("status").asString())).count())
                .isEqualTo(1);
    }

    @Test
    void residentsOnlyDecideForTheirOwnFlat() throws Exception {
        String id = walkIn("Wrong Flat").get("id").asString();
        send("POST", "/api/v1/visitors/" + id + "/approve", null, world.resident102Token())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_FLAT_RESIDENT"));
        send("POST", "/api/v1/visitors/" + id + "/approve", null, world.guardToken()).andExpect(status().isForbidden());
    }

    @Test
    void guardMustBeOnDutyWithPermissionAndValidFlat() throws Exception {
        sendOk("POST", "/api/v1/guards/me/duty", Map.of("onDuty", false), world.guardToken());
        send("POST", "/api/v1/visitors", Map.of("name", "Off Duty", "mobile", "9822011122", "type", "GUEST",
                "flatId", world.flat101(), "purpose", "Visit"), world.guardToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GUARD_OFF_DUTY"))
                .andExpect(jsonPath("$.message").value("Start your shift before recording gate entries."));
        sendOk("POST", "/api/v1/guards/me/duty", Map.of("onDuty", true), world.guardToken());

        send("PUT", "/api/v1/guards/" + world.guardId(), Map.of("permissions", List.of("DELIVERY_ENTRY")), world.adminToken())
                .andExpect(status().isOk());
        send("POST", "/api/v1/visitors", Map.of("name", "No Permission", "mobile", "9822011122", "type", "GUEST",
                "flatId", world.flat101(), "purpose", "Visit"), world.guardToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GUARD_PERMISSION_DENIED"));
        send("PUT", "/api/v1/guards/" + world.guardId(),
                Map.of("permissions", List.of("VISITOR_ENTRY", "DELIVERY_ENTRY", "STAFF_ATTENDANCE")), world.adminToken());

        World other = worlds().build("Elsewhere");
        send("POST", "/api/v1/visitors", Map.of("name", "Foreign Flat", "mobile", "9822011122", "type", "GUEST",
                "flatId", other.flat101(), "purpose", "Visit"), world.guardToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FLAT"))
                .andExpect(jsonPath("$.errors[0].field").value("flatId"));
    }

    @Test
    void validationErrorsAreReportedPerField() throws Exception {
        send("POST", "/api/v1/visitors", Map.of("name", "X", "mobile", "123", "type", "GUEST", "purpose", ""), world.guardToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'flatId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'purpose')]").exists());
        send("POST", "/api/v1/visitors", Map.of("name", "Valid Name", "mobile", "123", "type", "GUEST",
                "flatId", world.flat101(), "purpose", "Visit"), world.guardToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("mobile"));
        send("POST", "/api/v1/visitors", Map.of("name", "Valid Name", "mobile", "9822011122", "type", "ALIEN",
                "flatId", world.flat101(), "purpose", "Visit"), world.guardToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("type"));
    }

    @Test
    void invitationFlowWithPassCode() throws Exception {
        Instant now = clock.instant();
        JsonNode invite = sendOk("POST", "/api/v1/visitors/invites", Map.of("name", "Meera Nair", "mobile", "9811122233",
                "type", "FAMILY", "flatId", world.flat101(), "purpose", "Dinner",
                "validFrom", now.minusSeconds(60).toString(), "validUntil", now.plus(Duration.ofHours(4)).toString(),
                "guestCount", 3), world.resident101Token());
        String id = invite.get("id").asString();
        String passCode = invite.get("passCode").asString();
        assertThat(invite.get("status").asString()).isEqualTo("PRE_APPROVED");
        assertThat(passCode).matches("\\d{6}");

        // Guards see that it is an invite, but not the code itself.
        getWith("/api/v1/visitors?scope=EXPECTED", world.guardToken())
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].passCode").value("******"));
        getWith("/api/v1/visitor-invitations", world.resident101Token())
                .andExpect(jsonPath("$.content[0].passCode").value(passCode));

        send("POST", "/api/v1/visitors/verify-pass", Map.of("passCode", "000000"), world.guardToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
        sendOk("POST", "/api/v1/visitors/verify-pass", Map.of("passCode", passCode), world.guardToken());
        sendOk("POST", "/api/v1/visitors/" + id + "/entry", null, world.guardToken());
        send("POST", "/api/v1/visitors/verify-pass", Map.of("passCode", passCode), world.guardToken())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITE_ALREADY_USED"));
        getWith("/api/v1/notifications", world.resident101Token())
                .andExpect(jsonPath("$.content[0].type").value("VISITOR_CHECKED_IN"));
    }

    @Test
    void invitationWindowsAreValidated() throws Exception {
        Instant now = clock.instant();
        Map<String, Object> base = new HashMap<>(Map.of("name", "Window Test", "mobile", "9811122233", "type", "GUEST",
                "flatId", world.flat101(), "purpose", "Visit"));
        base.put("validFrom", now.plus(Duration.ofHours(2)).toString());
        base.put("validUntil", now.plus(Duration.ofHours(1)).toString());
        send("POST", "/api/v1/visitors/invites", base, world.resident101Token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The end time must be after the start time."));
        base.put("validFrom", now.minus(Duration.ofHours(3)).toString());
        base.put("validUntil", now.minus(Duration.ofHours(1)).toString());
        send("POST", "/api/v1/visitors/invites", base, world.resident101Token())
                .andExpect(jsonPath("$.message").value("The invite window has already ended."));
        base.put("validFrom", now.toString());
        base.put("validUntil", now.plus(Duration.ofDays(8)).toString());
        send("POST", "/api/v1/visitors/invites", base, world.resident101Token())
                .andExpect(jsonPath("$.message").value("Invites can be valid for at most 168 hours."));
        // Residents can only invite to their own flat.
        base.put("validUntil", now.plus(Duration.ofHours(1)).toString());
        base.put("flatId", world.flat102());
        send("POST", "/api/v1/visitors/invites", base, world.resident101Token())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_FLAT_RESIDENT"));
    }

    @Test
    void futureInviteIsNotValidYetAndExpiredInviteIsRejected() throws Exception {
        Instant now = clock.instant();
        JsonNode invite = sendOk("POST", "/api/v1/visitor-invitations", Map.of("name", "Later Guest", "mobile", "9811122233",
                "type", "GUEST", "flatId", world.flat101(), "purpose", "Visit",
                "validFrom", now.plus(Duration.ofHours(1)).toString(), "validUntil", now.plus(Duration.ofHours(3)).toString()),
                world.resident101Token());
        String id = invite.get("id").asString();
        send("POST", "/api/v1/visitors/verify-pass", Map.of("passCode", invite.get("passCode").asString()), world.guardToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITE_NOT_VALID_NOW"));
        send("POST", "/api/v1/visitors/" + id + "/entry", null, world.guardToken())
                .andExpect(jsonPath("$.code").value("INVITE_NOT_VALID_NOW"));

        clock.advance(Duration.ofHours(4));
        send("POST", "/api/v1/visitors/" + id + "/entry", null, world.guardToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITE_EXPIRED"));
        assertThat(maintenance.expireVisitors()).isGreaterThanOrEqualTo(1);
        getWith("/api/v1/visitors/" + id, world.resident101Token())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.history[1].title").value("Invite expired"));
    }

    @Test
    void cancellationRules() throws Exception {
        Instant now = clock.instant();
        JsonNode invite = sendOk("POST", "/api/v1/visitors/invites", Map.of("name", "Cancel Me", "mobile", "9811122233",
                "type", "GUEST", "flatId", world.flat101(), "purpose", "Visit",
                "validFrom", now.toString(), "validUntil", now.plus(Duration.ofHours(1)).toString()), world.resident101Token());
        sendOk("POST", "/api/v1/visitors/" + invite.get("id").asString() + "/cancel", null, world.resident101Token());
        send("POST", "/api/v1/visitors/verify-pass", Map.of("passCode", invite.get("passCode").asString()), world.guardToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITE_CANCELLED"));

        String walkInId = walkIn("Cancel Walk In").get("id").asString();
        send("POST", "/api/v1/visitors/" + walkInId + "/cancel", null, world.resident101Token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISITOR_NOT_CANCELLABLE"));
        sendOk("POST", "/api/v1/visitors/" + walkInId + "/cancel", null, world.guardToken());
        getWith("/api/v1/visitors/" + walkInId, world.resident101Token()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void photoUploadIsServedThroughASignedUrl() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        MockMultipartFile data = new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
                json(Map.of("name", "Photo Guest", "mobile", "9822011122", "type", "SERVICE", "flatId", world.flat101(),
                        "purpose", "Plumbing")).getBytes(StandardCharsets.UTF_8));
        MockMultipartFile photo = new MockMultipartFile("photo", "visitor.png", "application/octet-stream", png);
        JsonNode created = body(mvc.perform(multipart("/api/v1/visitors").file(data).file(photo)
                        .header("Authorization", "Bearer " + world.guardToken()))
                .andExpect(status().isCreated())
                .andReturn());
        URI url = URI.create(created.get("photoUrl").asString());
        mvc.perform(get(url.getPath() + "?" + url.getQuery()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get(url.getPath() + "?" + url.getQuery().replaceAll("signature=[0-9a-f]", "signature=0")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FILE_LINK_INVALID"));

        MockMultipartFile script = new MockMultipartFile("photo", "evil.png", "image/png",
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/visitors").file(data).file(script).header("Authorization", "Bearer " + world.guardToken()))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void idempotencyKeyPreventsDuplicateRegistration() throws Exception {
        String body = json(Map.of("name", "Retry Guest", "mobile", "9822011122", "type", "GUEST",
                "flatId", world.flat101(), "purpose", "Visit"));
        var first = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/visitors")
                        .header("Authorization", "Bearer " + world.guardToken())
                        .header("Idempotency-Key", "visitor-retry-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        var second = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/visitors")
                        .header("Authorization", "Bearer " + world.guardToken())
                        .header("Idempotency-Key", "visitor-retry-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();
        assertThat(body(second).get("id").asString()).isEqualTo(body(first).get("id").asString());
        getWith("/api/v1/visitors?q=Retry", world.guardToken()).andExpect(jsonPath("$.totalElements").value(1));
    }
}
