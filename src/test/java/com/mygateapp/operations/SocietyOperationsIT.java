package com.mygateapp.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mygateapp.support.IntegrationTest;
import com.mygateapp.support.World;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Phase D: deliveries, domestic staff, complaints and notices. */
class SocietyOperationsIT extends IntegrationTest {

    World world;

    @BeforeEach
    void setUp() throws Exception {
        world = worlds().build("Operations Society");
    }

    // ------------------------------------------------------------- deliveries

    @Test
    void foodDeliveryNeedsApprovalAndFollowsTheStatusFlow() throws Exception {
        JsonNode created = sendOk("POST", "/api/v1/deliveries", Map.of("category", "FOOD", "company", "QuickBite",
                "agentName", "Arjun", "agentMobile", "9000011111", "flatId", world.flat101(), "packageCount", 1), world.guardToken());
        String id = created.get("id").asString();
        assertThat(created.get("status").asString()).isEqualTo("PENDING");
        assertThat(created.get("requiresApproval").asBoolean()).isTrue();

        send("POST", "/api/v1/deliveries/" + id + "/entry", null, world.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DELIVERY_NOT_APPROVED"));
        getWith("/api/v1/notifications", world.resident101Token())
                .andExpect(jsonPath("$.content[0].type").value("DELIVERY_ARRIVAL"))
                .andExpect(jsonPath("$.content[0].title").value("Food delivery from QuickBite needs approval"));
        send("POST", "/api/v1/deliveries/" + id + "/approve", null, world.resident102Token()).andExpect(status().isForbidden());

        sendOk("POST", "/api/v1/deliveries/" + id + "/approve", null, world.resident101Token());
        send("POST", "/api/v1/deliveries/" + id + "/reject", null, world.resident101Token())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DELIVERY_ALREADY_DECIDED"));
        send("POST", "/api/v1/deliveries/" + id + "/exit", null, world.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DELIVERY_NOT_INSIDE"));
        assertThat(sendOk("POST", "/api/v1/deliveries/" + id + "/entry", null, world.guardToken()).get("status").asString())
                .isEqualTo("ENTERED");
        send("POST", "/api/v1/deliveries/" + id + "/entry", null, world.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DELIVERY_ALREADY_INSIDE"));
        JsonNode done = sendOk("POST", "/api/v1/deliveries/" + id + "/exit", null, world.guardToken());
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("history").valueStream().map(e -> e.get("status").asString()).toList())
                .containsExactly("PENDING", "APPROVED", "ENTERED", "COMPLETED");
        send("POST", "/api/v1/deliveries/" + id + "/exit", null, world.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DELIVERY_ALREADY_EXITED"));
    }

    @Test
    void courierIsAutoApprovedAndRejectedDeliveriesCannotEnter() throws Exception {
        JsonNode courier = sendOk("POST", "/api/v1/deliveries", Map.of("category", "COURIER", "company", "ParcelGo",
                "agentName", "Sanjay", "flatId", world.flat101()), world.guardToken());
        assertThat(courier.get("status").asString()).isEqualTo("APPROVED");
        assertThat(courier.get("history").get(1).get("title").asString()).isEqualTo("Allowed by society rules");
        getWith("/api/v1/deliveries?scope=ACTIVE", world.guardToken()).andExpect(jsonPath("$.totalElements").value(1));

        String grocery = sendOk("POST", "/api/v1/deliveries", Map.of("category", "GROCERY", "company", "FreshCart",
                "agentName", "Imran", "flatId", world.flat101()), world.guardToken()).get("id").asString();
        sendOk("POST", "/api/v1/deliveries/" + grocery + "/reject", null, world.resident101Token());
        send("POST", "/api/v1/deliveries/" + grocery + "/entry", null, world.guardToken())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DELIVERY_REJECTED"));

        String parcel = sendOk("POST", "/api/v1/deliveries", Map.of("category", "OTHER", "company", "ShopKart",
                "agentName", "Vijay", "flatId", world.flat101()), world.guardToken()).get("id").asString();
        assertThat(sendOk("POST", "/api/v1/deliveries/" + parcel + "/leave-at-gate", null, world.resident101Token())
                .get("status").asString()).isEqualTo("LEFT_AT_GATE");
        getWith("/api/v1/notifications", world.guardToken())
                .andExpect(jsonPath("$.content[0].body").value("Keep the parcel at the security desk."));
    }

    @Test
    void societyRulesDecideWhichCategoriesNeedApproval() throws Exception {
        JsonNode settings = sendOk("GET", "/api/v1/societies/" + world.societyId() + "/settings", null, world.adminToken());
        Map<String, Object> update = objectMapper.convertValue(settings, Map.class);
        update.put("deliveryApprovalRules", Map.of("COURIER", true, "FOOD", false));
        sendOk("PUT", "/api/v1/societies/" + world.societyId() + "/settings", update, world.adminToken());
        assertThat(sendOk("POST", "/api/v1/deliveries", Map.of("category", "COURIER", "company", "ParcelGo",
                "agentName", "Sanjay", "flatId", world.flat101()), world.guardToken()).get("status").asString()).isEqualTo("PENDING");
        assertThat(sendOk("POST", "/api/v1/deliveries", Map.of("category", "FOOD", "company", "QuickBite",
                "agentName", "Arjun", "flatId", world.flat101()), world.guardToken()).get("status").asString()).isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------- domestic staff

    @Test
    void staffAttendanceAndVisibility() throws Exception {
        JsonNode staff = sendOk("POST", "/api/v1/domestic-staff", Map.of("name", "Sunita Devi", "mobile", "9123456780",
                "category", "MAID", "flatIds", List.of(world.flat101()), "passNumber", "GR-S-1021"), world.adminToken());
        String id = staff.get("id").asString();
        send("POST", "/api/v1/domestic-staff", Map.of("name", "Duplicate", "mobile", "9123456780", "category", "COOK"),
                world.adminToken()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STAFF_MOBILE_TAKEN"));

        // Resident of A-101 sees her, resident of A-102 does not.
        getWith("/api/v1/domestic-staff", world.resident101Token()).andExpect(jsonPath("$.totalElements").value(1));
        getWith("/api/v1/domestic-staff", world.resident102Token()).andExpect(jsonPath("$.totalElements").value(0));
        getWith("/api/v1/domestic-staff/" + id, world.resident102Token()).andExpect(status().isForbidden());

        JsonNode in = sendOk("POST", "/api/v1/domestic-staff/" + id + "/check-in", null, world.guardToken());
        assertThat(in.get("inside").asBoolean()).isTrue();
        send("POST", "/api/v1/domestic-staff/" + id + "/check-in", null, world.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STAFF_ALREADY_INSIDE"));
        getWith("/api/v1/notifications", world.resident101Token())
                .andExpect(jsonPath("$.content[0].type").value("DOMESTIC_HELP_ARRIVAL"))
                .andExpect(jsonPath("$.content[0].title").value("Sunita Devi has arrived"));
        getWith("/api/v1/domestic-staff?presence=INSIDE", world.guardToken()).andExpect(jsonPath("$.totalElements").value(1));

        sendOk("POST", "/api/v1/domestic-staff/" + id + "/check-out", null, world.guardToken());
        send("POST", "/api/v1/domestic-staff/" + id + "/check-out", null, world.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STAFF_NOT_INSIDE"));

        getWith("/api/v1/domestic-staff/attendance?staffId=" + id, world.resident101Token())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].staffName").value("Sunita Devi"))
                .andExpect(jsonPath("$.content[0].gate.name").value("Main Gate"))
                .andExpect(jsonPath("$.content[0].recordedBy").value("Ramesh Yadav"))
                .andExpect(jsonPath("$.content[0].checkOutAt").isNotEmpty());
        getWith("/api/v1/domestic-staff/" + id + "/attendance", world.resident102Token()).andExpect(status().isForbidden());
    }

    @Test
    void guardWithoutStaffPermissionAndInactiveStaffAreBlocked() throws Exception {
        String id = sendOk("POST", "/api/v1/domestic-staff", Map.of("name", "Raju Kumar", "mobile", "9123456781",
                "category", "DRIVER", "flatIds", List.of(world.flat101())), world.adminToken()).get("id").asString();
        sendOk("PATCH", "/api/v1/domestic-staff/" + id + "/status", Map.of("status", "INACTIVE"), world.adminToken());
        send("POST", "/api/v1/domestic-staff/" + id + "/check-in", null, world.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STAFF_INACTIVE"));
        sendOk("PATCH", "/api/v1/domestic-staff/" + id + "/status", Map.of("status", "ACTIVE"), world.adminToken());
        send("PUT", "/api/v1/guards/" + world.guardId(), Map.of("permissions", List.of("VISITOR_ENTRY")), world.adminToken());
        send("POST", "/api/v1/domestic-staff/" + id + "/check-in", null, world.guardToken())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUARD_PERMISSION_DENIED"));
    }

    // -------------------------------------------------------------- complaints

    @Test
    void complaintLifecycleWithHistoryCommentsAndNotifications() throws Exception {
        JsonNode created = sendOk("POST", "/api/v1/complaints", Map.of("category", "LIFT", "title", "Lift stuck on 2nd floor",
                "description", "The lift in tower A stops between floors.", "flatId", world.flat101()), world.resident101Token());
        String id = created.get("id").asString();
        assertThat(created.get("ticketNumber").asString()).isEqualTo("CMP-1001");
        assertThat(created.get("status").asString()).isEqualTo("OPEN");
        String second = sendOk("POST", "/api/v1/complaints", Map.of("category", "PLUMBING", "title", "Leaking tap",
                "description", "Kitchen tap leaks all night long.", "flatId", world.flat101()), world.resident101Token())
                .get("ticketNumber").asString();
        assertThat(second).isEqualTo("CMP-1002");

        send("POST", "/api/v1/complaints", Map.of("category", "LIFT", "title", "Hi", "description", "short",
                "flatId", world.flat101()), world.resident101Token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        getWith("/api/v1/complaints/" + id, world.resident102Token()).andExpect(status().isForbidden());
        send("PATCH", "/api/v1/complaints/" + id + "/status", Map.of("status", "IN_PROGRESS"), world.resident101Token())
                .andExpect(status().isForbidden());

        sendOk("PATCH", "/api/v1/complaints/" + id + "/status", Map.of("status", "IN_PROGRESS",
                "remarks", "Technician assigned"), world.adminToken());
        getWith("/api/v1/notifications", world.resident101Token())
                .andExpect(jsonPath("$.content[0].type").value("COMPLAINT_UPDATE"))
                .andExpect(jsonPath("$.content[0].title").value("Complaint CMP-1001 is in progress"));
        send("PATCH", "/api/v1/complaints/" + id + "/status", Map.of("status", "OPEN"), world.adminToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        // Residents cannot edit once work has started.
        send("PUT", "/api/v1/complaints/" + id, Map.of("title", "Changed title"), world.resident101Token())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMPLAINT_NOT_EDITABLE"));

        sendOk("POST", "/api/v1/complaints/" + id + "/comments", Map.of("body", "Parts ordered", "internal", true), world.adminToken());
        sendOk("POST", "/api/v1/complaints/" + id + "/comments", Map.of("body", "Will be fixed tomorrow"), world.adminToken());
        sendOk("PATCH", "/api/v1/complaints/" + id + "/status", Map.of("status", "RESOLVED"), world.adminToken());
        sendOk("PATCH", "/api/v1/complaints/" + id + "/status", Map.of("status", "CLOSED"), world.resident101Token());

        JsonNode resident = sendOk("GET", "/api/v1/complaints/" + id, null, world.resident101Token());
        assertThat(resident.get("status").asString()).isEqualTo("CLOSED");
        assertThat(resident.get("adminRemarks").asString()).isEqualTo("Technician assigned");
        assertThat(resident.get("comments").size()).isEqualTo(1);
        assertThat(resident.get("history").valueStream().map(h -> h.get("status").asString()).toList())
                .containsExactly("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");
        assertThat(sendOk("GET", "/api/v1/complaints/" + id, null, world.adminToken()).get("comments").size()).isEqualTo(2);
        getWith("/api/v1/complaints?status=CLOSED", world.resident101Token()).andExpect(jsonPath("$.totalElements").value(1));
    }

    // ----------------------------------------------------------------- notices

    @Test
    void noticesArePublishedToTheirAudienceAndTrackReadState() throws Exception {
        JsonNode draft = sendOk("POST", "/api/v1/notices", Map.of("title", "Water supply interruption",
                "body", "Tank cleaning on Saturday.\nPlease store water.", "category", "MAINTENANCE", "important", true),
                world.adminToken());
        String id = draft.get("id").asString();
        assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
        getWith("/api/v1/notices", world.resident101Token()).andExpect(jsonPath("$.totalElements").value(0));
        getWith("/api/v1/notices/" + id, world.resident101Token()).andExpect(status().isNotFound());

        sendOk("POST", "/api/v1/notices/" + id + "/publish", null, world.adminToken());
        send("POST", "/api/v1/notices/" + id + "/publish", null, world.adminToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTICE_ALREADY_PUBLISHED"));
        getWith("/api/v1/notifications", world.resident102Token())
                .andExpect(jsonPath("$.content[0].type").value("ANNOUNCEMENT"))
                .andExpect(jsonPath("$.content[0].title").value("Important: Water supply interruption"))
                .andExpect(jsonPath("$.content[0].body").value("Tank cleaning on Saturday."));

        getWith("/api/v1/notices?unread=true", world.resident101Token())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].read").value(false))
                .andExpect(jsonPath("$.content[0].audience").value("All residents"));
        send("POST", "/api/v1/notices/" + id + "/read", null, world.resident101Token()).andExpect(status().isNoContent());
        send("POST", "/api/v1/notices/" + id + "/read", null, world.resident101Token()).andExpect(status().isNoContent());
        getWith("/api/v1/notices?unread=true", world.resident101Token()).andExpect(jsonPath("$.totalElements").value(0));
        getWith("/api/v1/notices/" + id, world.resident101Token()).andExpect(jsonPath("$.read").value(true));
        // Residents' audience notice is not shown to guards.
        getWith("/api/v1/notices/" + id, world.guardToken()).andExpect(status().isNotFound());
        send("POST", "/api/v1/notices", Map.of("title", "x", "body", "y", "category", "GENERAL"), world.resident101Token())
                .andExpect(status().isForbidden());

        // Tower notices only reach residents of that tower.
        JsonNode otherTower = sendOk("POST", "/api/v1/towers", Map.of("code", "B", "name", "Tower B"), world.adminToken());
        sendOk("POST", "/api/v1/notices", Map.of("title", "Tower B lift maintenance", "body", "Lift off for 2 hours",
                "category", "MAINTENANCE", "audience", "TOWERS", "towerIds", List.of(otherTower.get("id").asString()),
                "publish", true), world.adminToken());
        getWith("/api/v1/notices", world.resident101Token()).andExpect(jsonPath("$.totalElements").value(1));

        sendOk("DELETE", "/api/v1/notices/" + id, null, world.adminToken());
        getWith("/api/v1/notices/" + id, world.resident101Token()).andExpect(status().isNotFound());
    }
}
