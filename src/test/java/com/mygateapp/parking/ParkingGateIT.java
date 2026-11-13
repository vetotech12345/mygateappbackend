package com.mygateapp.parking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mygateapp.parking.service.ParkingJobs;
import com.mygateapp.support.World;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Gate verification, entry and exit for resident, society, visitor and emergency vehicles. */
class ParkingGateIT extends ParkingTestSupport {

    @Autowired
    ParkingJobs jobs;

    @Autowired
    com.mygateapp.parking.service.ParkingMaintenanceService maintenance;

    World w;

    @BeforeEach
    void setUp() throws Exception {
        w = worlds().build("Gate Society");
    }

    @Test
    void residentVehicleWithAllottedSlotEntersAndExits() throws Exception {
        UUID b1 = area(w, "B1", "RESIDENT", "CAR");
        UUID slot = slot(w, b1, "B1-042", "CAR");
        UUID vehicle = approvedVehicle(w, w.resident101Token(), w.flat101(), "MH12AB1234");
        allocate(w, slot, w.flat101(), vehicle);

        JsonNode check = verify(w.guardToken(), "mh 12 ab 1234");
        assertThat(text(check, "decision")).isEqualTo("ALLOWED");
        assertThat(text(check, "category")).isEqualTo("RESIDENT");
        assertThat(text(check, "slot.number")).isEqualTo("B1-042");
        assertThat(text(check, "permit.status")).isEqualTo("ACTIVE");
        assertThat(text(check, "summary")).contains("A-101");
        assertThat(check.get("expiresAt").isNull()).isFalse();

        JsonNode session = enter(w.guardToken(), check, "RESIDENT");
        assertThat(text(session, "status")).isEqualTo("ACTIVE");
        assertThat(text(session, "slot.number")).isEqualTo("B1-042");
        assertThat(text(session, "entryGate.name")).isEqualTo("Main Gate");
        assertThat(text(session, "charges.paymentStatus")).isEqualTo("NOT_APPLICABLE");
        assertThat(text(session, "recordedBy")).isEqualTo("Ramesh Yadav");
        String sessionId = session.get("id").asString();

        // Allocated + occupied are now both true.
        JsonNode slotState = sendOk("GET", "/api/v1/parking/slots/" + slot, null, w.guardToken());
        assertThat(text(slotState, "status")).isEqualTo("OCCUPIED");
        assertThat(slotState.get("occupiedBy").asString()).isEqualTo("MH12AB1234");
        JsonNode again = verify(w.guardToken(), "MH12AB1234");
        assertThat(text(again, "decision")).isEqualTo("ALREADY_INSIDE");
        assertThat(text(again, "activeSession.id")).isEqualTo(sessionId);
        assertThat(sendOk("GET", "/api/v1/guards/parking/dashboard", null, w.guardToken()).get("vehiclesInside").asLong())
                .isEqualTo(1);

        JsonNode closed = close(w.guardToken(), sessionId);
        assertThat(text(closed, "status")).isEqualTo("COMPLETED");
        assertThat(text(closed, "exitAt")).isNotNull();
        assertThat(closed.get("history").valueStream().map(h -> h.get("status").asString())).containsExactly("ENTRY", "EXIT");
        send("POST", "/api/v1/parking/sessions/" + sessionId + "/close", Map.of(), w.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_ALREADY_CLOSED"));
        // The slot is still allocated but no longer occupied.
        JsonNode released = sendOk("GET", "/api/v1/parking/slots/" + slot, null, w.guardToken());
        assertThat(released.get("occupied").asBoolean()).isFalse();
        assertThat(released.get("allocated").asBoolean()).isTrue();
        // The resident sees the history.
        JsonNode mine = sendOk("GET", "/api/v1/parking/sessions?scope=MINE", null, w.resident101Token());
        assertThat(mine.get("totalElements").asLong()).isEqualTo(1);
        JsonNode audit = sendOk("GET", "/api/v1/audit-logs?entityId=" + sessionId, null, w.adminToken());
        assertThat(audit.get("content").valueStream().map(a -> a.get("action").asString()))
                .contains("PARKING_ENTRY", "PARKING_EXIT");
    }

    @Test
    void registrationAloneDoesNotAuthoriseEntry() throws Exception {
        approvedVehicle(w, w.resident101Token(), w.flat101(), "MH12AB5555");
        JsonNode denied = verify(w.guardToken(), "MH12AB5555");
        assertThat(text(denied, "decision")).isEqualTo("DENIED");
        assertThat(denied.get("reasons").get(0).asString()).contains("No active parking permit");
        send("POST", "/api/v1/parking/sessions", entryBody(denied, "RESIDENT"), w.guardToken())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ENTRY_NOT_AUTHORIZED"));

        // An explicit society policy lets approved resident vehicles in without a permit.
        putSettings(w, Map.of("residentEntryRequiresPermit", false));
        JsonNode allowed = verify(w.guardToken(), "MH12AB5555");
        assertThat(text(allowed, "decision")).isEqualTo("ALLOWED");
        assertThat(allowed.get("warnings").get(0).asString()).contains("No parking slot");
        // A pending (unapproved) vehicle is still denied.
        vehicle(w.resident102Token(), w.flat102(), "MH12AB6666");
        assertThat(text(verify(w.guardToken(), "MH12AB6666"), "decision")).isEqualTo("DENIED");
        assertThat(text(verify(w.guardToken(), "MH12ZZ9999"), "decision")).isEqualTo("NOT_REGISTERED");
    }

    @Test
    void verificationsAreFreshSingleUseAndPerGuard() throws Exception {
        UUID b1 = area(w, "B1", "RESIDENT", "CAR");
        UUID vehicle = approvedVehicle(w, w.resident101Token(), w.flat101(), "MH12AB7777");
        allocate(w, slot(w, b1, "B1-001", "CAR"), w.flat101(), vehicle);

        // Expired after 5 minutes.
        JsonNode stale = verify(w.guardToken(), "MH12AB7777");
        clock.advance(Duration.ofMinutes(6));
        send("POST", "/api/v1/parking/sessions", entryBody(stale, "RESIDENT"), w.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERIFICATION_EXPIRED"));
        clock.reset();

        // Another guard cannot use someone else's verification.
        String secondGuard = secondGuard();
        JsonNode mine = verify(w.guardToken(), "MH12AB7777");
        send("POST", "/api/v1/parking/sessions", entryBody(mine, "RESIDENT"), secondGuard)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERIFICATION_EXPIRED"));

        // The verification is for one number only, and is used once.
        Map<String, Object> wrongNumber = entryBody(mine, "RESIDENT");
        wrongNumber.put("vehicleNumber", "MH12AB0000");
        send("POST", "/api/v1/parking/sessions", wrongNumber, w.guardToken()).andExpect(status().isConflict());
        JsonNode fresh = verify(w.guardToken(), "MH12AB7777");
        enter(w.guardToken(), fresh, "RESIDENT");
        send("POST", "/api/v1/parking/sessions", entryBody(fresh, "RESIDENT"), w.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERIFICATION_EXPIRED"));
    }

    @Test
    void concurrentEntriesOfOneVehicleCreateOneSession() throws Exception {
        UUID b1 = area(w, "B1", "RESIDENT", "CAR");
        UUID vehicle = approvedVehicle(w, w.resident101Token(), w.flat101(), "MH12AB8888");
        allocate(w, slot(w, b1, "B1-001", "CAR"), w.flat101(), vehicle);
        String secondGuard = secondGuard();
        JsonNode v1 = verify(w.guardToken(), "MH12AB8888");
        JsonNode v2 = verify(secondGuard, "MH12AB8888");
        List<Callable<Integer>> calls = new ArrayList<>();
        calls.add(() -> httpStatus("POST", "/api/v1/parking/sessions", entryBody(v1, "RESIDENT"), w.guardToken()));
        calls.add(() -> httpStatus("POST", "/api/v1/parking/sessions", entryBody(v2, "RESIDENT"), secondGuard));
        List<Integer> statuses = concurrently(calls);
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parking_sessions WHERE vehicle_number = 'MH12AB8888'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void retriedEntryWithTheSameIdempotencyKeyIsReplayed() throws Exception {
        UUID b1 = area(w, "B1", "RESIDENT", "CAR");
        UUID vehicle = approvedVehicle(w, w.resident101Token(), w.flat101(), "MH12AB9999");
        allocate(w, slot(w, b1, "B1-001", "CAR"), w.flat101(), vehicle);
        JsonNode check = verify(w.guardToken(), "MH12AB9999");
        String key = UUID.randomUUID().toString();
        String first = postWithKey("/api/v1/parking/sessions", entryBody(check, "RESIDENT"), w.guardToken(), key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = postWithKey("/api/v1/parking/sessions", entryBody(check, "RESIDENT"), w.guardToken(), key)
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replay).get("id")).isEqualTo(objectMapper.readTree(first).get("id"));
    }

    @Test
    void exitRequiresAnActiveSession() throws Exception {
        send("POST", "/api/v1/parking/sessions/" + UUID.randomUUID() + "/close", Map.of(), w.guardToken())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PARKING_SESSION_NOT_FOUND"));
        // Residents and admins cannot record gate movements.
        send("POST", "/api/v1/parking/verifications", Map.of("vehicleNumber", "MH12AB1234"), w.resident101Token())
                .andExpect(status().isForbidden());
        send("POST", "/api/v1/parking/verifications", Map.of("vehicleNumber", "MH12AB1234"), w.adminToken())
                .andExpect(status().isForbidden());
    }

    @Test
    void societyVehicleWithReservedSlotEnters() throws Exception {
        UUID reservedArea = area(w, "SR", "RESERVED", "CAR");
        UUID reservedSlot = slot(w, reservedArea, "SR-01", "CAR");
        JsonNode van = sendOk("POST", "/api/v1/vehicles", Map.of("registrationNumber", "MH12SO1234", "vehicleType", "CAR",
                "category", "SERVICE", "ownerName", "Lift maintenance"), w.adminToken());
        JsonNode permit = sendOk("POST", "/api/v1/parking/permits", Map.of("type", "SERVICE", "vehicleId", van.get("id").asString(),
                "slotId", reservedSlot), w.adminToken());
        assertThat(text(permit, "permitNumber")).contains("-SRV-");
        assertThat(text(permit, "slot.number")).isEqualTo("SR-01");

        JsonNode check = verify(w.guardToken(), "MH12SO1234");
        assertThat(text(check, "decision")).isEqualTo("ALLOWED");
        assertThat(text(check, "category")).isEqualTo("SERVICE");
        JsonNode session = enter(w.guardToken(), check, "SERVICE");
        assertThat(text(session, "slot.number")).isEqualTo("SR-01");
        // Guard search hides contact data and labels the owner by role.
        JsonNode found = sendOk("GET", "/api/v1/parking/vehicles/search?q=ZZ99&by=VEHICLE", null, w.guardToken());
        assertThat(found.size()).isZero();
        found = sendOk("GET", "/api/v1/parking/vehicles/search?q=MH12SO", null, w.guardToken());
        assertThat(text(found.get(0), "ownerLabel")).isEqualTo("Service vehicle");
        assertThat(found.get(0).get("insideNow").asBoolean()).isTrue();
        // Slot and permit numbers are found as printed (with hyphens) or without them, including long permit numbers.
        for (String slotTerm : List.of("SR-01", "sr01")) {
            found = sendOk("GET", "/api/v1/parking/vehicles/search?q=" + slotTerm + "&by=SLOT", null, w.guardToken());
            assertThat(found.size()).as("slot search %s", slotTerm).isEqualTo(1);
            assertThat(text(found.get(0), "vehicleNumber")).isEqualTo("MH12SO1234");
        }
        String permitNumber = text(permit, "permitNumber");
        for (String permitTerm : List.of(permitNumber, permitNumber.replace("-", ""))) {
            found = sendOk("GET", "/api/v1/parking/vehicles/search?q=" + permitTerm + "&by=PERMIT", null, w.guardToken());
            assertThat(found.size()).as("permit search %s", permitTerm).isEqualTo(1);
            assertThat(text(found.get(0), "vehicleNumber")).isEqualTo("MH12SO1234");
        }
        // Entering as the wrong category is refused.
        sendOk("POST", "/api/v1/parking/sessions/" + session.get("id").asString() + "/close", Map.of(), w.guardToken());
        JsonNode again = verify(w.guardToken(), "MH12SO1234");
        send("POST", "/api/v1/parking/sessions", entryBody(again, "RESIDENT"), w.guardToken())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ENTRY_NOT_AUTHORIZED"));
    }

    @Test
    void visitorParkingIsAutoApprovedUsedOnceAndReleased() throws Exception {
        putSettings(w, Map.of("visitorParkingAutoApprove", true));
        UUID visitorArea = area(w, "V", "VISITOR", "CAR");
        UUID slot = slot(w, visitorArea, "V-01", "CAR");
        Instant arrival = Instant.now().plus(Duration.ofMinutes(10));
        Instant departure = arrival.plus(Duration.ofHours(3));
        JsonNode request = sendOk("POST", "/api/v1/parking/visitor-requests", Map.of("flatId", w.flat101(),
                "visitorName", "Neha Kulkarni", "visitorMobile", "9822012345", "vehicleNumber", "MH14XY4321",
                "vehicleType", "CAR", "expectedArrival", arrival.toString(), "expectedDeparture", departure.toString(),
                "purpose", "Family dinner"), w.resident101Token());
        assertThat(text(request, "type")).isEqualTo("VISITOR_SLOT");
        assertThat(text(request, "status")).isEqualTo("APPROVED");
        assertThat(text(request, "decidedBy")).isEqualTo("Auto-approved");
        assertThat(text(request, "assignedSlot.number")).isEqualTo("V-01");
        assertThat(text(request, "visitor.name")).isEqualTo("Neha Kulkarni");
        assertThat(text(request, "referenceNumber")).startsWith("PR-");

        // The only slot is taken for that window.
        send("POST", "/api/v1/parking/visitor-requests", Map.of("flatId", w.flat102(), "visitorName", "Someone Else",
                "vehicleNumber", "MH14XY0001", "vehicleType", "CAR", "expectedArrival", arrival.toString(),
                "expectedDeparture", departure.toString()), w.resident102Token())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PARKING_UNAVAILABLE"));

        JsonNode check = verify(w.guardToken(), "MH14XY4321");
        assertThat(text(check, "decision")).isEqualTo("ALLOWED");
        assertThat(text(check, "category")).isEqualTo("VISITOR");
        assertThat(text(check, "parkingRequestId")).isEqualTo(request.get("id").asString());
        JsonNode session = enter(w.guardToken(), check, "VISITOR");
        assertThat(text(session, "visitorName")).isEqualTo("Neha Kulkarni");
        assertThat(text(session, "slot.number")).isEqualTo("V-01");
        JsonNode notifications = sendOk("GET", "/api/v1/notifications", null, w.resident101Token());
        assertThat(notifications.get("content").valueStream().map(n -> n.get("title").asString()))
                .contains("Visitor vehicle arrived");
        assertThat(sendOk("GET", "/api/v1/parking/summary", null, w.resident101Token()).get("visitorVehiclesInside").asLong())
                .isEqualTo(1);

        close(w.guardToken(), session.get("id").asString());
        // The temporary allocation is released and the pass cannot be reused.
        assertThat(text(sendOk("GET", "/api/v1/parking/slots/" + slot, null, w.guardToken()), "status")).isEqualTo("AVAILABLE");
        assertThat(text(verify(w.guardToken(), "MH14XY4321"), "decision")).isEqualTo("NOT_REGISTERED");
        assertThat(sendOk("GET", "/api/v1/parking/availability?usage=VISITOR&vehicleType=CAR&from=" + arrival
                + "&until=" + departure, null, w.resident102Token()).get("availableSlots").asInt()).isEqualTo(1);
    }

    @Test
    void visitorParkingReusesTheVisitorApprovalWorkflow() throws Exception {
        putSettings(w, Map.of("visitorParkingAutoApprove", true));
        slot(w, area(w, "V", "VISITOR", "CAR"), "V-01", "CAR");
        JsonNode walkIn = sendOk("POST", "/api/v1/visitors", Map.of("name", "Vikram Iyer", "mobile", "9822011122",
                "type", "GUEST", "flatId", w.flat101(), "purpose", "Family visit", "vehicleNumber", "MH 14 AB 1111"),
                w.guardToken());
        String visitorId = walkIn.get("id").asString();
        Instant now = Instant.now();
        JsonNode request = sendOk("POST", "/api/v1/parking/visitor-requests", Map.of("flatId", w.flat101(),
                "visitorId", visitorId, "vehicleNumber", "MH14AB1111", "vehicleType", "CAR",
                "expectedArrival", now.toString(), "expectedDeparture", now.plus(Duration.ofHours(2)).toString()),
                w.resident101Token());
        assertThat(text(request, "visitor.visitorId")).isEqualTo(visitorId);
        assertThat(text(request, "visitor.name")).isEqualTo("Vikram Iyer");

        // The visitor is still PENDING in the visitor module: parking cannot approve the person.
        JsonNode waiting = verify(w.guardToken(), "MH14AB1111");
        assertThat(text(waiting, "decision")).isEqualTo("NEEDS_VISITOR_APPROVAL");
        assertThat(text(waiting, "visitor.status")).isEqualTo("PENDING");
        send("POST", "/api/v1/parking/sessions", entryBody(waiting, "VISITOR"), w.guardToken())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ENTRY_NOT_AUTHORIZED"));

        sendOk("POST", "/api/v1/visitors/" + visitorId + "/approve", null, w.resident101Token());
        JsonNode allowed = verify(w.guardToken(), "MH14AB1111");
        assertThat(text(allowed, "decision")).isEqualTo("ALLOWED");
        JsonNode session = enter(w.guardToken(), allowed, "VISITOR");
        // One gate event: the visitor's check-in is recorded by the vehicle entry (no duplicate visitor).
        JsonNode visitor = sendOk("GET", "/api/v1/visitors/" + visitorId, null, w.guardToken());
        assertThat(text(visitor, "movementStatus")).isEqualTo("INSIDE");
        close(w.guardToken(), session.get("id").asString());
        assertThat(text(sendOk("GET", "/api/v1/visitors/" + visitorId, null, w.guardToken()), "movementStatus"))
                .isEqualTo("EXITED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visitors WHERE society_id = ?", Integer.class, w.societyId()))
                .isEqualTo(1);
    }

    @Test
    void guardsNeedTheVehicleToggleAndMustBeOnDuty() throws Exception {
        sendOk("PUT", "/api/v1/guards/" + w.guardId(), Map.of("permissions", List.of("VISITOR_ENTRY")), w.adminToken());
        send("POST", "/api/v1/parking/verifications", Map.of("vehicleNumber", "MH12AB1234"), w.guardToken())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUARD_PERMISSION_DENIED"));
        send("GET", "/api/v1/guards/parking/dashboard", null, w.guardToken())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUARD_PERMISSION_DENIED"));

        sendOk("PUT", "/api/v1/guards/" + w.guardId(), Map.of("permissions", List.of("VISITOR_ENTRY", "VEHICLE_ENTRY")),
                w.adminToken());
        JsonNode me = sendOk("GET", "/api/v1/users/me", null, w.guardToken());
        assertThat(me.get("guard").get("permissions").valueStream().map(JsonNode::asString)).contains("VEHICLE_ENTRY");
        sendOk("POST", "/api/v1/guards/me/duty", Map.of("onDuty", false), w.guardToken());
        send("POST", "/api/v1/parking/verifications", Map.of("vehicleNumber", "MH12AB1234"), w.guardToken())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUARD_OFF_DUTY"));
        // Reads do not require duty.
        sendOk("GET", "/api/v1/guards/parking/dashboard", null, w.guardToken());
    }

    @Test
    void emergencyOverrideIsExplicitAndAudited() throws Exception {
        JsonNode unknown = verify(w.guardToken(), "MH12AMB108");
        assertThat(text(unknown, "decision")).isEqualTo("NOT_REGISTERED");
        assertThat(unknown.get("emergencyOverrideAllowed").asBoolean()).isFalse();
        Map<String, Object> body = entryBody(unknown, "EMERGENCY");
        body.put("vehicleType", "OTHER");
        body.put("overrideReason", "Ambulance for A-101");
        send("POST", "/api/v1/parking/sessions", body, w.guardToken())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ENTRY_NOT_AUTHORIZED"));

        putSettings(w, Map.of("emergencyOverrideAllowed", true));
        JsonNode again = verify(w.guardToken(), "MH12AMB108");
        Map<String, Object> noReason = entryBody(again, "EMERGENCY");
        noReason.put("vehicleType", "OTHER");
        send("POST", "/api/v1/parking/sessions", noReason, w.guardToken()).andExpect(status().isForbidden());
        JsonNode third = verify(w.guardToken(), "MH12AMB108");
        Map<String, Object> withReason = entryBody(third, "EMERGENCY");
        withReason.put("vehicleType", "OTHER");
        withReason.put("overrideReason", "Ambulance for A-101");
        JsonNode session = sendOk("POST", "/api/v1/parking/sessions", withReason, w.guardToken());
        assertThat(session.get("emergencyOverride").asBoolean()).isTrue();
        assertThat(text(session, "overrideReason")).isEqualTo("Ambulance for A-101");
        assertThat(session.get("history").get(0).get("status").asString()).isEqualTo("ENTRY_OVERRIDE");
        JsonNode audit = sendOk("GET", "/api/v1/audit-logs?action=PARKING_ENTRY_OVERRIDE", null, w.adminToken());
        assertThat(audit.get("totalElements").asLong()).isEqualTo(1);
        JsonNode adminNotifications = sendOk("GET", "/api/v1/notifications", null, w.adminToken());
        assertThat(adminNotifications.get("content").valueStream().map(n -> n.get("title").asString()))
                .contains("Emergency entry recorded");
    }

    @Test
    void overstayIsDetectedAndReported() throws Exception {
        putSettings(w, Map.of("visitorParkingAutoApprove", true, "freeGraceMinutes", 10));
        slot(w, area(w, "V", "VISITOR", "CAR"), "V-01", "CAR");
        Instant now = Instant.now();
        sendOk("POST", "/api/v1/parking/visitor-requests", Map.of("flatId", w.flat101(), "visitorName", "Late Guest",
                "vehicleNumber", "MH14LG0001", "vehicleType", "CAR", "expectedArrival", now.toString(),
                "expectedDeparture", now.plus(Duration.ofHours(1)).toString()), w.resident101Token());
        JsonNode session = enter(w.guardToken(), verify(w.guardToken(), "MH14LG0001"), "VISITOR");
        String id = session.get("id").asString();

        String overstayEvents = "SELECT COUNT(*) FROM parking_movements WHERE session_id = ?::uuid AND event_type = 'OVERSTAY'";
        clock.advance(Duration.ofMinutes(65)); // window ended, but still inside the 10 min grace
        jobs.run(); // expires the visitor allocation window; must not report an overstay yet
        assertThat(jdbc.queryForObject(overstayEvents, Integer.class, id)).isZero();
        assertThat(text(sendOk("GET", "/api/v1/parking/sessions/" + id, null, w.guardToken()), "status")).isEqualTo("ACTIVE");

        clock.advance(Duration.ofMinutes(10));
        assertThat(jobs.run()).isGreaterThanOrEqualTo(1);
        assertThat(text(sendOk("GET", "/api/v1/parking/sessions/" + id, null, w.guardToken()), "status")).isEqualTo("OVERSTAY");
        assertThat(sendOk("GET", "/api/v1/parking/sessions?scope=OVERSTAY", null, w.guardToken()).get("totalElements").asLong())
                .isEqualTo(1);
        assertThat(sendOk("GET", "/api/v1/guards/parking/dashboard", null, w.guardToken()).get("overstayCount").asLong())
                .isEqualTo(1);
        JsonNode summary = sendOk("GET", "/api/v1/parking/summary", null, w.resident101Token());
        assertThat(summary.get("alerts").valueStream().map(a -> a.get("type").asString())).contains("OVERSTAY");
        // Reported once only.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parking_movements WHERE session_id = ?::uuid AND event_type = 'OVERSTAY'",
                Integer.class, id)).isEqualTo(1);
        jobs.run();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parking_movements WHERE session_id = ?::uuid AND event_type = 'OVERSTAY'",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void retentionRemovesVisitorDataAndOldTransientRows() throws Exception {
        putSettings(w, Map.of("visitorParkingAutoApprove", true));
        slot(w, area(w, "V", "VISITOR", "CAR"), "V-01", "CAR");
        Instant now = Instant.now();
        JsonNode request = sendOk("POST", "/api/v1/parking/visitor-requests", Map.of("flatId", w.flat101(),
                "visitorName", "Private Person", "visitorMobile", "9822099999", "vehicleNumber", "MH14RT0001",
                "vehicleType", "CAR", "expectedArrival", now.toString(),
                "expectedDeparture", now.plus(Duration.ofHours(2)).toString()), w.resident101Token());
        String requestId = request.get("id").asString();
        sendOk("POST", "/api/v1/parking/requests/" + requestId + "/cancel", null, w.resident101Token());
        verify(w.guardToken(), "MH12UNUSED1"); // an unused verification

        // Nothing is removed while the data is recent.
        maintenance.retention(clock.instant());
        assertThat(jdbc.queryForObject("SELECT visitor_name FROM parking_requests WHERE id = ?::uuid", String.class, requestId))
                .isEqualTo("Private Person");

        clock.advance(Duration.ofDays(366)); // default visitor_retention_days = 365
        int[] result = maintenance.retention(clock.instant());
        assertThat(result[0]).isGreaterThanOrEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT visitor_name, visitor_mobile, anonymized_at FROM parking_requests WHERE id = ?::uuid", requestId);
        assertThat(row.get("visitor_name")).isEqualTo("Visitor (redacted)");
        assertThat(row.get("visitor_mobile")).isNull();
        assertThat(row.get("anonymized_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parking_verifications WHERE vehicle_number = 'MH12UNUSED1'",
                Integer.class)).isZero();
        // The scheduled entry point runs the same step without failing.
        jobs.retention();
    }

    @Test
    void otherSocietiesSeeNothingAtTheirGate() throws Exception {
        World other = worlds().build("Neighbour Society");
        UUID b1 = area(w, "B1", "RESIDENT", "CAR");
        UUID vehicle = approvedVehicle(w, w.resident101Token(), w.flat101(), "MH12IS0001");
        allocate(w, slot(w, b1, "B1-001", "CAR"), w.flat101(), vehicle);
        JsonNode session = enter(w.guardToken(), verify(w.guardToken(), "MH12IS0001"), "RESIDENT");
        String id = session.get("id").asString();

        JsonNode foreign = verify(other.guardToken(), "MH12IS0001");
        assertThat(text(foreign, "decision")).isEqualTo("NOT_REGISTERED");
        assertThat(foreign.get("vehicle").isNull()).isTrue();
        send("POST", "/api/v1/parking/sessions/" + id + "/close", Map.of(), other.guardToken())
                .andExpect(status().isNotFound());
        send("GET", "/api/v1/parking/sessions/" + id, null, other.resident101Token()).andExpect(status().isNotFound());
        send("GET", "/api/v1/parking/sessions/" + id, null, w.resident102Token()).andExpect(status().isNotFound());
        assertThat(sendOk("GET", "/api/v1/parking/sessions", null, other.adminToken()).get("totalElements").asLong()).isZero();
        assertThat(sendOk("GET", "/api/v1/parking/vehicles/search?q=MH12IS", null, other.guardToken()).size()).isZero();
    }

    /** A second on-duty guard at the same gate, with all permissions. */
    private String secondGuard() throws Exception {
        String mobile = randomMobile();
        sendOk("POST", "/api/v1/guards", Map.of("name", "Suresh Patil", "mobile", mobile, "gateId", w.gateId(),
                "shiftName", "Day", "shiftStart", "08:00", "shiftEnd", "20:00"), w.adminToken());
        String token = worlds().login(mobile);
        sendOk("POST", "/api/v1/guards/me/duty", Map.of("onDuty", true), token);
        return token;
    }
}
