package com.mygateapp.parking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mygateapp.support.World;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Society parking settings, areas, slots, allocations (conflicts, concurrency), availability and isolation. */
class ParkingInventoryIT extends ParkingTestSupport {

    World w;

    @BeforeEach
    void setUp() throws Exception {
        w = worlds().build("Inventory Society");
    }

    @Test
    void settingsAreSocietySpecificAndAdminOnly() throws Exception {
        World other = worlds().build("Other Society");
        JsonNode defaults = sendOk("GET", "/api/v1/societies/" + w.societyId() + "/parking/settings", null, w.adminToken());
        assertThat(defaults.get("paidParkingEnabled").asBoolean()).isFalse();
        assertThat(defaults.get("onlinePaymentEnabled").asBoolean()).isFalse();

        JsonNode updated = putSettings(w, Map.of("paidParkingEnabled", true, "maxVisitorParkingHours", 6,
                "allowedVehicleTypes", List.of("CAR", "BIKE"), "rules", List.of("No honking")));
        assertThat(updated.get("paidParkingEnabled").asBoolean()).isTrue();
        assertThat(updated.get("maxVisitorParkingHours").asInt()).isEqualTo(6);

        JsonNode config = sendOk("GET", "/api/v1/parking/config", null, w.resident101Token());
        assertThat(config.get("paidParkingEnabled").asBoolean()).isTrue();
        assertThat(config.get("maxVisitorParkingHours").asInt()).isEqualTo(6);
        assertThat(config.get("rules").get(0).asString()).isEqualTo("No honking");
        assertThat(config.get("vehicleNumberFormat").asString()).isEqualTo("INDIA");
        assertThat(sendOk("GET", "/api/v1/parking/config", null, w.guardToken()).get("paidParkingEnabled").asBoolean()).isTrue();

        // The other society keeps its own defaults.
        assertThat(sendOk("GET", "/api/v1/parking/config", null, other.resident101Token())
                .get("paidParkingEnabled").asBoolean()).isFalse();

        // Residents and guards cannot read or change admin settings; other societies' admins cannot see them.
        send("GET", "/api/v1/societies/" + w.societyId() + "/parking/settings", null, w.resident101Token())
                .andExpect(status().isForbidden());
        Map<String, Object> body = defaultSettings();
        send("PUT", "/api/v1/societies/" + w.societyId() + "/parking/settings", body, w.guardToken())
                .andExpect(status().isForbidden());
        send("PUT", "/api/v1/societies/" + w.societyId() + "/parking/settings", body, other.adminToken())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SOCIETY_NOT_FOUND"));
        // Online payment methods cannot be "accepted" without a provider.
        Map<String, Object> online = new HashMap<>(body);
        online.put("acceptedPaymentMethods", List.of("ONLINE"));
        send("PUT", "/api/v1/societies/" + w.societyId() + "/parking/settings", online, w.adminToken())
                .andExpect(status().isBadRequest());
    }

    @Test
    void mySocietyIsAvailableToResidentsAndGuards() throws Exception {
        JsonNode resident = sendOk("GET", "/api/v1/societies/my", null, w.resident101Token());
        assertThat(text(resident, "id")).isEqualTo(w.societyId().toString());
        assertThat(text(resident, "name")).isEqualTo("Inventory Society");
        assertThat(resident.get("towers").get(0).get("code").asString()).isEqualTo("A");
        assertThat(text(sendOk("GET", "/api/v1/societies/my", null, w.guardToken()), "id")).isEqualTo(w.societyId().toString());
        // The admin-only society endpoint is unchanged.
        send("GET", "/api/v1/societies/" + w.societyId(), null, w.resident101Token()).andExpect(status().isForbidden());
    }

    @Test
    void areasAndSlotsAreValidated() throws Exception {
        UUID b1 = area(w, "B1", "RESIDENT", "CAR");
        send("POST", "/api/v1/parking/areas", Map.of("code", "b1", "name", "Another", "usage", "RESIDENT",
                "vehicleTypes", List.of("CAR")), w.adminToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PARKING_AREA_TAKEN"));
        slot(w, b1, "B1-001", "CAR");
        send("POST", "/api/v1/parking/slots", Map.of("areaId", b1, "number", "b1-001", "vehicleType", "CAR"), w.adminToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PARKING_SLOT_TAKEN"));
        send("POST", "/api/v1/parking/slots", Map.of("areaId", b1, "number", "B1-099", "vehicleType", "BIKE"), w.adminToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_NOT_SUITABLE"));
        JsonNode bulk = sendOk("POST", "/api/v1/parking/slots/bulk", Map.of("areaId", b1, "prefix", "B1-", "from", 2,
                "to", 6, "padding", 3, "vehicleType", "CAR"), w.adminToken());
        assertThat(bulk.size()).isEqualTo(5);
        assertThat(bulk.get(0).get("number").asString()).isEqualTo("B1-002");

        JsonNode page = sendOk("GET", "/api/v1/parking/slots?areaId=" + b1, null, w.guardToken());
        assertThat(page.get("totalElements").asLong()).isEqualTo(6);
        assertThat(page.get("content").get(0).get("status").asString()).isEqualTo("AVAILABLE");
        assertThat(page.get("content").get(0).get("vehicleTypes").get(0).asString()).isEqualTo("CAR");
        send("GET", "/api/v1/parking/slots", null, w.resident101Token()).andExpect(status().isForbidden());
        send("POST", "/api/v1/parking/areas", Map.of("code", "X", "name", "X", "usage", "RESIDENT",
                "vehicleTypes", List.of("CAR")), w.guardToken()).andExpect(status().isForbidden());

        JsonNode area = sendOk("GET", "/api/v1/parking/areas/" + b1, null, w.resident101Token());
        assertThat(area.get("totalSlots").asInt()).isEqualTo(6);
        assertThat(area.get("availableSlots").asInt()).isEqualTo(6);
    }

    @Test
    void slotStateKeepsAllocationOccupancyReservationAndServiceSeparate() throws Exception {
        UUID b1 = area(w, "B1", "RESIDENT", "CAR");
        UUID allocated = slot(w, b1, "B1-001", "CAR");
        UUID broken = slot(w, b1, "B1-002", "CAR");
        UUID reserved = slot(w, b1, "B1-003", "CAR", "RESERVED");
        UUID vehicle = approvedVehicle(w, w.resident101Token(), w.flat101(), "MH12AA0001");
        allocate(w, allocated, w.flat101(), vehicle);
        sendOk("PATCH", "/api/v1/parking/slots/" + broken, Map.of("operationalStatus", "OUT_OF_SERVICE",
                "outOfServiceReason", "Water leak"), w.adminToken());

        JsonNode a = sendOk("GET", "/api/v1/parking/slots/" + allocated, null, w.adminToken());
        assertThat(a.get("allocated").asBoolean()).isTrue();
        assertThat(a.get("occupied").asBoolean()).isFalse(); // allocated is not occupied
        assertThat(text(a, "status")).isEqualTo("RESERVED");
        assertThat(text(a, "currentAllocation.flat.label")).isEqualTo("A-101");
        JsonNode b = sendOk("GET", "/api/v1/parking/slots/" + broken, null, w.adminToken());
        assertThat(text(b, "status")).isEqualTo("BLOCKED");
        assertThat(text(b, "outOfServiceReason")).isEqualTo("Water leak");
        JsonNode c = sendOk("GET", "/api/v1/parking/slots/" + reserved, null, w.adminToken());
        assertThat(c.get("reserved").asBoolean()).isTrue();
        assertThat(c.get("allocated").asBoolean()).isFalse();

        // Out-of-service slots cannot be allocated.
        UUID other = approvedVehicle(w, w.resident102Token(), w.flat102(), "MH12AA0002");
        send("POST", "/api/v1/parking/allocations", Map.of("slotId", broken, "allocationType", "PERMANENT",
                "flatId", w.flat102(), "vehicleId", other), w.adminToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_OUT_OF_SERVICE"));
        // Usage is checked: a RESERVED slot is not a resident slot.
        send("POST", "/api/v1/parking/allocations", Map.of("slotId", reserved, "allocationType", "PERMANENT",
                "flatId", w.flat102(), "vehicleId", other), w.adminToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_NOT_SUITABLE"));
    }

    @Test
    void overlappingAllocationsAreRejected() throws Exception {
        UUID b1 = area(w, "B1", "RESIDENT", "CAR");
        UUID s1 = slot(w, b1, "B1-001", "CAR");
        UUID v1 = approvedVehicle(w, w.resident101Token(), w.flat101(), "MH12BB0001");
        UUID v2 = approvedVehicle(w, w.resident102Token(), w.flat102(), "MH12BB0002");
        allocate(w, s1, w.flat101(), v1);
        send("POST", "/api/v1/parking/allocations", Map.of("slotId", s1, "allocationType", "PERMANENT",
                "flatId", w.flat102(), "vehicleId", v2), w.adminToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALLOCATION_CONFLICT"));

        // Temporary windows on another slot: back-to-back is fine, overlapping is not.
        UUID s2 = slot(w, b1, "B1-002", "CAR");
        Instant start = Instant.now().plus(Duration.ofDays(1));
        sendOk("POST", "/api/v1/parking/allocations", Map.of("slotId", s2, "allocationType", "TEMPORARY",
                "flatId", w.flat102(), "validFrom", start.toString(),
                "validUntil", start.plus(Duration.ofDays(2)).toString()), w.adminToken());
        sendOk("POST", "/api/v1/parking/allocations", Map.of("slotId", s2, "allocationType", "TEMPORARY",
                "flatId", w.flat101(), "validFrom", start.plus(Duration.ofDays(2)).toString(),
                "validUntil", start.plus(Duration.ofDays(3)).toString()), w.adminToken());
        send("POST", "/api/v1/parking/allocations", Map.of("slotId", s2, "allocationType", "TEMPORARY",
                "flatId", w.flat101(), "validFrom", start.plus(Duration.ofDays(1)).toString(),
                "validUntil", start.plus(Duration.ofDays(4)).toString()), w.adminToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALLOCATION_CONFLICT"));
        // A vehicle cannot hold two slots at once either.
        UUID s3 = slot(w, b1, "B1-003", "CAR");
        send("POST", "/api/v1/parking/allocations", Map.of("slotId", s3, "allocationType", "PERMANENT",
                "flatId", w.flat101(), "vehicleId", v1), w.adminToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALLOCATION_CONFLICT"));
    }

    @Test
    void concurrentAllocationOfOneSlotSucceedsOnce() throws Exception {
        UUID b1 = area(w, "B1", "RESIDENT", "CAR");
        UUID slot = slot(w, b1, "B1-001", "CAR");
        List<UUID> flats = List.of(w.flat101(), w.flat102(), w.flat201());
        List<Callable<Integer>> calls = new ArrayList<>();
        for (UUID flat : flats) {
            calls.add(() -> httpStatus("POST", "/api/v1/parking/allocations", Map.of("slotId", slot,
                    "allocationType", "PERMANENT", "flatId", flat), w.adminToken()));
        }
        List<Integer> statuses = concurrently(calls);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parking_allocations WHERE slot_id = ? AND status = 'ACTIVE'",
                Integer.class, slot)).isEqualTo(1);
    }

    @Test
    void exclusionConstraintGuardsOverlapsInTheDatabase() {
        // Even a direct insert that bypasses the service is rejected by PostgreSQL.
        UUID b1;
        try {
            b1 = area(w, "B1", "RESIDENT", "CAR");
            UUID slot = slot(w, b1, "B1-001", "CAR");
            allocate(w, slot, w.flat101(), null);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO parking_allocations (id, society_id, slot_id, flat_id, allocation_type, status, valid_from,
                                                     created_at, updated_at)
                    VALUES (gen_random_uuid(), ?, ?, ?, 'PERMANENT', 'ACTIVE', now(), now(), now())
                    """, w.societyId(), slot, w.flat102()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                    .hasMessageContaining("ex_parking_allocations_overlap");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void transferAndCancelAllocations() throws Exception {
        UUID b1 = area(w, "B1", "RESIDENT", "CAR");
        UUID s1 = slot(w, b1, "B1-001", "CAR");
        UUID s2 = slot(w, b1, "B1-002", "CAR");
        UUID v1 = approvedVehicle(w, w.resident101Token(), w.flat101(), "MH12CC0001");
        UUID allocation = allocate(w, s1, w.flat101(), v1);

        JsonNode moved = sendOk("POST", "/api/v1/parking/allocations/" + allocation + "/transfer",
                Map.of("slotId", s2, "reason", "Pillar in B1-001"), w.adminToken());
        assertThat(text(moved, "slot.number")).isEqualTo("B1-002");
        assertThat(text(moved, "permit.status")).isEqualTo("ACTIVE");
        JsonNode old = sendOk("GET", "/api/v1/parking/allocations/" + allocation, null, w.adminToken());
        assertThat(text(old, "status")).isEqualTo("REVOKED");
        assertThat(text(sendOk("GET", "/api/v1/parking/slots/" + s1, null, w.adminToken()), "status")).isEqualTo("AVAILABLE");

        // Resident sees the new slot.
        JsonNode mine = sendOk("GET", "/api/v1/parking/allocations/mine", null, w.resident101Token());
        assertThat(mine.valueStream().filter(a -> a.get("status").asString().equals("ACTIVE"))
                .map(a -> a.get("slot").get("number").asString())).containsExactly("B1-002");
        JsonNode permits = sendOk("GET", "/api/v1/parking/permits/mine", null, w.resident101Token());
        assertThat(permits.valueStream().anyMatch(p -> p.get("status").asString().equals("ACTIVE")
                && p.get("vehicleNumber").asString().equals("MH12CC0001"))).isTrue();

        String newId = moved.get("id").asString();
        JsonNode cancelled = sendOk("PATCH", "/api/v1/parking/allocations/" + newId,
                Map.of("status", "REVOKED", "reason", "Moved out"), w.adminToken());
        assertThat(text(cancelled, "status")).isEqualTo("REVOKED");
        assertThat(text(cancelled, "permit.status")).isEqualTo("REVOKED");
        // Residents cannot allocate.
        send("POST", "/api/v1/parking/allocations", Map.of("slotId", s1, "allocationType", "PERMANENT",
                "flatId", w.flat101()), w.resident101Token()).andExpect(status().isForbidden());
    }

    @Test
    void availabilityCountsFreeSlotsForTheWindow() throws Exception {
        UUID visitorArea = area(w, "V", "VISITOR", "CAR", "BIKE");
        UUID v1 = slot(w, visitorArea, "V-01", "CAR");
        slot(w, visitorArea, "V-02", "CAR");
        slot(w, visitorArea, "V-03", "BIKE");
        Instant from = Instant.now().plus(Duration.ofHours(2));
        Instant until = from.plus(Duration.ofHours(3));
        String url = "/api/v1/parking/availability?usage=VISITOR&vehicleType=CAR&from=" + from + "&until=" + until;
        JsonNode before = sendOk("GET", url, null, w.resident101Token());
        assertThat(before.get("totalSlots").asInt()).isEqualTo(2);
        assertThat(before.get("availableSlots").asInt()).isEqualTo(2);

        sendOk("POST", "/api/v1/parking/allocations", Map.of("slotId", v1, "allocationType", "VISITOR",
                "flatId", w.flat101(), "validFrom", from.minus(Duration.ofHours(1)).toString(),
                "validUntil", from.plus(Duration.ofHours(1)).toString()), w.adminToken());
        assertThat(sendOk("GET", url, null, w.resident101Token()).get("availableSlots").asInt()).isEqualTo(1);
        String later = "/api/v1/parking/availability?usage=VISITOR&vehicleType=CAR&from=" + from.plus(Duration.ofHours(1))
                + "&until=" + until;
        assertThat(sendOk("GET", later, null, w.resident101Token()).get("availableSlots").asInt()).isEqualTo(2);
        JsonNode bikes = sendOk("GET", "/api/v1/parking/availability?usage=VISITOR&vehicleType=SCOOTER&from=" + from
                + "&until=" + until, null, w.guardToken());
        assertThat(bikes.get("availableSlots").asInt()).isEqualTo(1);
        send("GET", "/api/v1/parking/availability?from=" + until + "&until=" + from, null, w.resident101Token())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARKING_WINDOW"));
    }

    @Test
    void otherSocietiesCannotSeeOrChangeInventory() throws Exception {
        World other = worlds().build("Intruder Society");
        UUID b1 = area(w, "B1", "RESIDENT", "CAR");
        UUID s1 = slot(w, b1, "B1-001", "CAR");
        UUID v1 = approvedVehicle(w, w.resident101Token(), w.flat101(), "MH12DD0001");
        UUID allocation = allocate(w, s1, w.flat101(), v1);

        send("GET", "/api/v1/parking/areas/" + b1, null, other.adminToken())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PARKING_AREA_NOT_FOUND"));
        send("PATCH", "/api/v1/parking/slots/" + s1, Map.of("operationalStatus", "OUT_OF_SERVICE"), other.adminToken())
                .andExpect(status().isNotFound());
        send("GET", "/api/v1/parking/allocations/" + allocation, null, other.adminToken()).andExpect(status().isNotFound());
        send("GET", "/api/v1/vehicles/" + v1, null, other.adminToken()).andExpect(status().isNotFound());
        send("POST", "/api/v1/vehicles/" + v1 + "/suspend", Map.of("remarks", "x"), other.adminToken())
                .andExpect(status().isNotFound());
        // Allocating another society's slot to one's own flat is impossible.
        send("POST", "/api/v1/parking/allocations", Map.of("slotId", s1, "allocationType", "PERMANENT",
                "flatId", other.flat101()), other.adminToken()).andExpect(status().isNotFound());
        assertThat(sendOk("GET", "/api/v1/parking/areas", null, other.adminToken()).size()).isZero();
        assertThat(sendOk("GET", "/api/v1/vehicles", null, other.adminToken()).get("totalElements").asLong()).isZero();
        // A forged X-Society-Id is refused.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/parking/areas")
                        .header("Authorization", "Bearer " + other.adminToken())
                        .header("X-Society-Id", w.societyId().toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SOCIETY_ACCESS_DENIED"));
    }
}
