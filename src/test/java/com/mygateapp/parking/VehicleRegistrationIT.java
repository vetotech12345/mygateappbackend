package com.mygateapp.parking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mygateapp.support.World;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Vehicle registration, validation, duplicates, limits, the approval workflow and resident scoping. */
class VehicleRegistrationIT extends ParkingTestSupport {

    World w;

    @BeforeEach
    void setUp() throws Exception {
        w = worlds().build("Vehicle Society");
    }

    @Test
    void residentRegistersAndAdminApproves() throws Exception {
        JsonNode created = sendOk("POST", "/api/v1/vehicles", Map.of("registrationNumber", "mh 12-ab 1234",
                "vehicleType", "CAR", "flatId", w.flat101(), "make", "Hyundai", "model", "Creta", "color", "White"),
                w.resident101Token());
        assertThat(text(created, "registrationNumber")).isEqualTo("MH12AB1234");
        assertThat(text(created, "registration.status")).isEqualTo("PENDING");
        assertThat(text(created, "category")).isEqualTo("RESIDENT");
        assertThat(text(created, "flat.label")).isEqualTo("A-101");
        assertThat(text(created, "society.id")).isEqualTo(w.societyId().toString());
        assertThat(text(created, "ownerName")).isEqualTo("Aarav Mehta");
        String id = created.get("id").asString();

        JsonNode list = sendOk("GET", "/api/v1/vehicles", null, w.resident101Token());
        assertThat(list.get("totalElements").asLong()).isEqualTo(1);

        JsonNode approved = sendOk("POST", "/api/v1/vehicles/" + id + "/approve", Map.of(), w.adminToken());
        assertThat(text(approved, "registration.status")).isEqualTo("APPROVED");
        assertThat(text(approved, "registration.reviewedAt")).isNotNull();

        // The resident sees "Society office" as reviewer and gets a PARKING_UPDATE notification.
        JsonNode mine = sendOk("GET", "/api/v1/vehicles/" + id, null, w.resident101Token());
        assertThat(text(mine, "registration.reviewedBy")).isEqualTo("Society office");
        JsonNode notifications = sendOk("GET", "/api/v1/notifications", null, w.resident101Token());
        assertThat(notifications.get("content").valueStream().map(n -> n.get("type").asString()))
                .contains("PARKING_UPDATE");
    }

    @Test
    void numbersAreValidatedAndNormalised() throws Exception {
        send("POST", "/api/v1/vehicles", Map.of("registrationNumber", "MH-12", "vehicleType", "CAR",
                "flatId", w.flat101()), w.resident101Token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VEHICLE_NUMBER"))
                .andExpect(jsonPath("$.errors[0].field").value("registrationNumber"));
        // A foreign plate with the GENERIC format is accepted (still needs approval).
        JsonNode generic = sendOk("POST", "/api/v1/vehicles", Map.of("registrationNumber", "ABC 1234", "vehicleType", "CAR",
                "flatId", w.flat101(), "numberFormat", "GENERIC"), w.resident101Token());
        assertThat(text(generic, "registrationNumber")).isEqualTo("ABC1234");
    }

    @Test
    void duplicateRegistrationIsRejectedAcrossFlats() throws Exception {
        vehicle(w.resident101Token(), w.flat101(), "KA01MN4455");
        send("POST", "/api/v1/vehicles", Map.of("registrationNumber", "KA 01 MN 4455", "vehicleType", "CAR",
                "flatId", w.flat102()), w.resident102Token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VEHICLE_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.errors[0].field").value("registrationNumber"));
    }

    @Test
    void perFlatVehicleLimitIsEnforced() throws Exception {
        putSettings(w, Map.of("maxVehiclesPerFlat", 1));
        vehicle(w.resident101Token(), w.flat101(), "MH12AB0001");
        send("POST", "/api/v1/vehicles", Map.of("registrationNumber", "MH12AB0002", "vehicleType", "CAR",
                "flatId", w.flat101()), w.resident101Token())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VEHICLE_LIMIT_REACHED"));
    }

    @Test
    void residentsOnlyManageTheirOwnFlatsVehicles() throws Exception {
        send("POST", "/api/v1/vehicles", Map.of("registrationNumber", "MH12AB1111", "vehicleType", "CAR",
                "flatId", w.flat102()), w.resident101Token())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_FLAT_RESIDENT"));
        UUID other = vehicle(w.resident102Token(), w.flat102(), "MH12AB2222");
        send("GET", "/api/v1/vehicles/" + other, null, w.resident101Token())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VEHICLE_NOT_FOUND"));
        send("PATCH", "/api/v1/vehicles/" + other, Map.of("color", "Red"), w.resident101Token())
                .andExpect(status().isForbidden());
        assertThat(sendOk("GET", "/api/v1/vehicles", null, w.resident101Token()).get("totalElements").asLong()).isZero();
        // Residents cannot approve; guards cannot register.
        send("POST", "/api/v1/vehicles/" + other + "/approve", Map.of(), w.resident102Token()).andExpect(status().isForbidden());
        send("POST", "/api/v1/vehicles", Map.of("registrationNumber", "MH12AB3333", "vehicleType", "CAR",
                "flatId", w.flat101()), w.guardToken()).andExpect(status().isForbidden());
    }

    @Test
    void draftSubmitRejectAndResubmit() throws Exception {
        JsonNode draft = sendOk("POST", "/api/v1/vehicles", Map.of("registrationNumber", "MH12CD4567", "vehicleType", "BIKE",
                "flatId", w.flat101(), "submitForApproval", false), w.resident101Token());
        String id = draft.get("id").asString();
        assertThat(text(draft, "registration.status")).isEqualTo("NOT_SUBMITTED");
        send("POST", "/api/v1/vehicles/" + id + "/reject", Map.of("remarks", "x"), w.adminToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VEHICLE_NOT_PENDING"));

        assertThat(text(sendOk("POST", "/api/v1/vehicles/" + id + "/submit", Map.of(), w.resident101Token()),
                "registration.status")).isEqualTo("PENDING");
        send("POST", "/api/v1/vehicles/" + id + "/submit", Map.of(), w.resident101Token())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VEHICLE_NOT_SUBMITTABLE"));
        send("POST", "/api/v1/vehicles/" + id + "/reject", Map.of(), w.adminToken()).andExpect(status().isBadRequest());
        JsonNode rejected = sendOk("POST", "/api/v1/vehicles/" + id + "/reject",
                Map.of("remarks", "RC copy does not match"), w.adminToken());
        assertThat(text(rejected, "registration.status")).isEqualTo("REJECTED");
        assertThat(text(rejected, "registration.remarks")).isEqualTo("RC copy does not match");

        sendOk("POST", "/api/v1/vehicles/" + id + "/submit", Map.of(), w.resident101Token());
        assertThat(text(sendOk("POST", "/api/v1/vehicles/" + id + "/approve", Map.of(), w.adminToken()),
                "registration.status")).isEqualTo("APPROVED");
        // Audit trail of the review.
        JsonNode audit = sendOk("GET", "/api/v1/audit-logs?entityId=" + id, null, w.adminToken());
        assertThat(audit.get("content").valueStream().map(a -> a.get("action").asString()))
                .contains("VEHICLE_REGISTERED", "VEHICLE_REJECTED", "VEHICLE_APPROVED");
    }

    @Test
    void changingAnApprovedVehiclesNumberRequiresReapproval() throws Exception {
        UUID area = area(w, "B1", "RESIDENT", "CAR");
        UUID slot = slot(w, area, "B1-001", "CAR");
        UUID vehicle = approvedVehicle(w, w.resident101Token(), w.flat101(), "MH12EF1111");
        UUID allocation = allocate(w, slot, w.flat101(), vehicle);

        JsonNode changed = sendOk("PATCH", "/api/v1/vehicles/" + vehicle, Map.of("registrationNumber", "MH12EF2222"),
                w.resident101Token());
        assertThat(text(changed, "registration.status")).isEqualTo("PENDING");
        JsonNode suspended = sendOk("GET", "/api/v1/parking/allocations/" + allocation, null, w.adminToken());
        assertThat(text(suspended, "status")).isEqualTo("SUSPENDED");
        assertThat(text(suspended, "permit.status")).isEqualTo("SUSPENDED");
        // The gate no longer lets it in while it waits.
        assertThat(text(verify(w.guardToken(), "MH12EF2222"), "decision")).isEqualTo("DENIED");

        sendOk("POST", "/api/v1/vehicles/" + vehicle + "/approve", Map.of(), w.adminToken());
        JsonNode reinstated = sendOk("GET", "/api/v1/parking/allocations/" + allocation, null, w.adminToken());
        assertThat(text(reinstated, "status")).isEqualTo("ACTIVE");
        assertThat(text(reinstated, "permit.status")).isEqualTo("ACTIVE");
        assertThat(text(verify(w.guardToken(), "MH12EF2222"), "decision")).isEqualTo("ALLOWED");
    }

    @Test
    void adminRegistersApprovedSocietyVehicles() throws Exception {
        JsonNode van = sendOk("POST", "/api/v1/vehicles", Map.of("registrationNumber", "MH12SO0001", "vehicleType", "CAR",
                "category", "SOCIETY", "ownerName", "Society maintenance van"), w.adminToken());
        assertThat(text(van, "category")).isEqualTo("SOCIETY");
        assertThat(text(van, "registration.status")).isEqualTo("APPROVED");
        assertThat(van.get("flat").isNull()).isTrue();
        JsonNode filtered = sendOk("GET", "/api/v1/vehicles?category=SOCIETY", null, w.adminToken());
        assertThat(filtered.get("totalElements").asLong()).isEqualTo(1);
        // A resident vehicle registered by an admin must name a flat.
        send("POST", "/api/v1/vehicles", Map.of("registrationNumber", "MH12SO0002", "vehicleType", "CAR",
                "category", "RESIDENT"), w.adminToken()).andExpect(status().isBadRequest());
    }

    @Test
    void deactivatedVehicleFreesItsNumberAndEndsItsSlot() throws Exception {
        UUID area = area(w, "B1", "RESIDENT", "CAR");
        UUID slot = slot(w, area, "B1-001", "CAR");
        UUID vehicle = approvedVehicle(w, w.resident101Token(), w.flat101(), "MH12GH1111");
        UUID allocation = allocate(w, slot, w.flat101(), vehicle);
        JsonNode inactive = sendOk("PATCH", "/api/v1/vehicles/" + vehicle, Map.of("active", false), w.resident101Token());
        assertThat(inactive.get("active").asBoolean()).isFalse();
        assertThat(text(sendOk("GET", "/api/v1/parking/allocations/" + allocation, null, w.adminToken()), "status"))
                .isEqualTo("REVOKED");
        // The number can be registered again (e.g. by the buyer's flat).
        vehicle(w.resident102Token(), w.flat102(), "MH12GH1111");
    }
}
