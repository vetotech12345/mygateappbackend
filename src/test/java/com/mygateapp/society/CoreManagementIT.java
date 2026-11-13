package com.mygateapp.society;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mygateapp.support.IntegrationTest;
import com.mygateapp.support.World;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Phase B: societies, structure, residents, guards and gates through the admin API. */
class CoreManagementIT extends IntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    World world;

    @BeforeEach
    void setUp() throws Exception {
        world = worlds().build("Greenwood Residency");
    }

    @Test
    void residentProfileMatchesTheFlutterContract() throws Exception {
        getWith("/api/v1/users/me", world.resident101Token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("RESIDENT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.profileComplete").value(true))
                .andExpect(jsonPath("$.resident.society.name").value("Greenwood Residency"))
                .andExpect(jsonPath("$.resident.flats[0].tower").value("A"))
                .andExpect(jsonPath("$.resident.flats[0].number").value("101"))
                .andExpect(jsonPath("$.resident.flats[0].label").value("A-101"))
                .andExpect(jsonPath("$.resident.primaryFlatId").value(world.flat101().toString()))
                .andExpect(jsonPath("$.resident.occupancy").value("OWNER"))
                .andExpect(jsonPath("$.guard").isEmpty());
    }

    @Test
    void guardProfileHasGateShiftAndPermissions() throws Exception {
        getWith("/api/v1/users/me", world.guardToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("GUARD"))
                .andExpect(jsonPath("$.guard.gate.name").value("Main Gate"))
                .andExpect(jsonPath("$.guard.shift").value("Day shift · 08:00 – 20:00"))
                .andExpect(jsonPath("$.guard.onDuty").value(true))
                // New guards default to all gate toggles; V9 added VEHICLE_ENTRY to the original three.
                .andExpect(jsonPath("$.guard.permissions.length()").value(4));
        // The stored wall-clock time must not depend on the JVM's time zone (the container runs in UTC).
        assertThat(jdbc.queryForObject("SELECT shift_start::text || '-' || shift_end::text FROM guard_assignments "
                + "WHERE guard_id = ? AND active", String.class, world.guardId())).isEqualTo("08:00:00-20:00:00");

        JsonNode offDuty = sendOk("POST", "/api/v1/guards/me/duty", Map.of("onDuty", false), world.guardToken());
        assertThat(offDuty.get("guard").get("onDuty").asBoolean()).isFalse();
        getWith("/api/v1/guards/" + world.guardId() + "/activity", world.adminToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].kind").value("DUTY"))
                .andExpect(jsonPath("$.content[0].title").value("Ended duty"));
    }

    @Test
    void societyCodesAreUniqueAndOnlyPlatformAdminsCreateSocieties() throws Exception {
        String superToken = worlds().superAdminToken();
        Map<String, Object> body = Map.of("code", "DUP-" + System.nanoTime() % 100000, "name", "Dup", "addressLine1", "x",
                "city", "Pune", "state", "MH", "pinCode", "411001");
        send("POST", "/api/v1/societies", body, superToken).andExpect(status().isCreated());
        send("POST", "/api/v1/societies", body, superToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOCIETY_CODE_TAKEN"));
        send("POST", "/api/v1/societies", body, world.adminToken()).andExpect(status().isForbidden());
        send("POST", "/api/v1/societies", body, world.resident101Token()).andExpect(status().isForbidden());
    }

    @Test
    void flatNumbersAreUniqueWithinATowerAndBulkCreateSkipsExisting() throws Exception {
        send("POST", "/api/v1/flats", Map.of("towerId", world.towerId(), "floorNumber", 1, "flatNumber", "101"), world.adminToken())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLAT_NUMBER_TAKEN"));
        JsonNode bulk = sendOk("POST", "/api/v1/flats/bulk",
                Map.of("towerId", world.towerId(), "fromFloor", 2, "toFloor", 3, "unitsPerFloor", 4), world.adminToken());
        assertThat(bulk.get("created").asInt()).isEqualTo(4);
        assertThat(bulk.get("skipped").asInt()).isEqualTo(4);

        // A wing gets its own number space.
        JsonNode wing = sendOk("POST", "/api/v1/towers/" + world.towerId() + "/wings", Map.of("name", "East"), world.adminToken());
        send("POST", "/api/v1/flats", Map.of("towerId", world.towerId(), "wingId", wing.get("id").asString(),
                "floorNumber", 1, "flatNumber", "101"), world.adminToken())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("A-East-101"));
    }

    @Test
    void ownerRequestsTenantAdminApprovesAndEndsTheMembership() throws Exception {
        String tenantMobile = randomMobile();
        JsonNode pending = sendOk("POST", "/api/v1/residents", Map.of("name", "Kabir Singh", "mobile", tenantMobile,
                "flatId", world.flat101(), "occupancy", "TENANT"), world.resident101Token());
        assertThat(pending.get("status").asString()).isEqualTo("PENDING");

        // Pending tenant can sign in but has no flat yet (Flutter shows "no flat linked").
        String tenantToken = worlds().login(tenantMobile);
        getWith("/api/v1/users/me", tenantToken)
                .andExpect(jsonPath("$.role").value("RESIDENT"))
                .andExpect(jsonPath("$.resident.flats.length()").value(0));

        // Tenants cannot add residents; residents cannot approve.
        send("POST", "/api/v1/residents/" + pending.get("id").asString() + "/approve", null, world.resident101Token())
                .andExpect(status().isForbidden());

        sendOk("POST", "/api/v1/residents/" + pending.get("id").asString() + "/approve", null, world.adminToken());
        getWith("/api/v1/users/me", tenantToken)
                .andExpect(jsonPath("$.resident.flats[0].label").value("A-101"))
                .andExpect(jsonPath("$.resident.occupancy").value("TENANT"));
        send("POST", "/api/v1/residents", Map.of("name", "Someone Else", "mobile", randomMobile(),
                "flatId", world.flat101(), "occupancy", "TENANT"), tenantToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_FLAT_OWNER"));

        sendOk("POST", "/api/v1/residents/" + pending.get("id").asString() + "/end", Map.of("reason", "Lease ended"),
                world.adminToken());
        getWith("/api/v1/flats/" + world.flat101() + "/occupancy-history", world.adminToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Kabir Singh')].status").value("ENDED"));
        // Ended tenant has no active resident role any more.
        send("GET", "/api/v1/visitors", null, tenantToken).andExpect(status().isForbidden());
    }

    @Test
    void familyMembersAreManagedByTheResident() throws Exception {
        JsonNode added = sendOk("POST", "/api/v1/residents/me/family-members",
                Map.of("name", "Priya Mehta", "relation", "Spouse", "mobile", "9876543211"), world.resident101Token());
        getWith("/api/v1/users/me", world.resident101Token())
                .andExpect(jsonPath("$.resident.familyMembers[0].name").value("Priya Mehta"))
                .andExpect(jsonPath("$.resident.familyMembers[0].relation").value("Spouse"));
        // Another resident cannot delete it.
        send("DELETE", "/api/v1/residents/me/family-members/" + added.get("id").asString(), null, world.resident102Token())
                .andExpect(status().isNotFound());
        send("DELETE", "/api/v1/residents/me/family-members/" + added.get("id").asString(), null, world.resident101Token())
                .andExpect(status().isNoContent());
    }

    @Test
    void guardsSeeSocietyFlatsAndLookups() throws Exception {
        getWith("/api/v1/society/flats", world.guardToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].label").value("A-101"));
        getWith("/api/v1/config/lookups", world.guardToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryCategories[?(@.code == 'FOOD')].requiresApproval").value(true))
                .andExpect(jsonPath("$.deliveryCategories[?(@.code == 'COURIER')].requiresApproval").value(false))
                .andExpect(jsonPath("$.maxInviteValidityHours").value(168));
    }

    @Test
    void societyAdminsCannotTouchAnotherSociety() throws Exception {
        World other = worlds().build("Other Society");
        // Reads by id are reported as not found.
        send("GET", "/api/v1/flats/" + world.flat101(), null, other.adminToken()).andExpect(status().isNotFound());
        send("GET", "/api/v1/societies/" + world.societyId(), null, other.adminToken()).andExpect(status().isNotFound());
        send("GET", "/api/v1/guards/" + world.guardId(), null, other.adminToken()).andExpect(status().isNotFound());
        // Writes that name the other society are denied.
        send("POST", "/api/v1/towers", Map.of("societyId", world.societyId(), "code", "Z", "name", "Tower Z"), other.adminToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SOCIETY_ACCESS_DENIED"));
        send("POST", "/api/v1/residents", Map.of("name", "Intruder", "mobile", randomMobile(), "flatId", world.flat101(),
                "occupancy", "OWNER"), other.adminToken())
                .andExpect(status().isNotFound());
        send("GET", "/api/v1/residents?societyId=" + world.societyId(), null, other.adminToken())
                .andExpect(status().isForbidden());
    }

    @Test
    void deactivatedSocietyLocksOutItsMembers() throws Exception {
        String superToken = worlds().superAdminToken();
        sendOk("PATCH", "/api/v1/societies/" + world.societyId() + "/status", Map.of("status", "INACTIVE"), superToken);
        send("GET", "/api/v1/society/flats", null, world.guardToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_MEMBERSHIP"));
        getWith("/api/v1/audit-logs?societyId=" + world.societyId(), superToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("SOCIETY_STATUS_CHANGED"));
    }
}
