package com.mygateapp.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mygateapp.support.IntegrationTest;
import com.mygateapp.support.World;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Phase E: resident/guard dashboards and feeds, admin dashboard, reports and the audit log. */
class DashboardsAndReportsIT extends IntegrationTest {

    World world;
    String insideVisitorId;

    @BeforeEach
    void activity() throws Exception {
        world = worlds().build("Dashboard Society");
        // A-101: one pending visitor, one visitor inside, one pending food delivery, staff inside, one open complaint.
        sendOk("POST", "/api/v1/visitors", Map.of("name", "Waiting Guest", "mobile", "9822011122", "type", "GUEST",
                "flatId", world.flat101(), "purpose", "Visit"), world.guardToken());
        insideVisitorId = sendOk("POST", "/api/v1/visitors", Map.of("name", "Inside Guest", "mobile", "9822011123",
                "type", "SERVICE", "flatId", world.flat101(), "purpose", "Repair"), world.guardToken()).get("id").asString();
        sendOk("POST", "/api/v1/visitors/" + insideVisitorId + "/approve", null, world.resident101Token());
        sendOk("POST", "/api/v1/visitors/" + insideVisitorId + "/entry", null, world.guardToken());
        sendOk("POST", "/api/v1/deliveries", Map.of("category", "FOOD", "company", "QuickBite", "agentName", "Arjun",
                "flatId", world.flat101()), world.guardToken());
        String staff = sendOk("POST", "/api/v1/domestic-staff", Map.of("name", "Sunita Devi", "mobile", "9123456780",
                "category", "MAID", "flatIds", List.of(world.flat101())), world.adminToken()).get("id").asString();
        sendOk("POST", "/api/v1/domestic-staff/" + staff + "/check-in", null, world.guardToken());
        sendOk("POST", "/api/v1/complaints", Map.of("category", "LIFT", "title", "Lift not working",
                "description", "The lift has been stuck since morning.", "flatId", world.flat101()), world.resident101Token());
        sendOk("POST", "/api/v1/notices", Map.of("title", "AGM on Sunday", "body", "Clubhouse at 10 AM", "category", "GENERAL",
                "publish", true), world.adminToken());
        // A-102 activity must not leak into A-101's dashboard.
        sendOk("POST", "/api/v1/visitors", Map.of("name", "Neighbour Guest", "mobile", "9822011124", "type", "GUEST",
                "flatId", world.flat102(), "purpose", "Visit"), world.guardToken());
    }

    @Test
    void residentDashboardMatchesTheFlutterContract() throws Exception {
        getWith("/api/v1/residents/dashboard", world.resident101Token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingVisitors.length()").value(1))
                .andExpect(jsonPath("$.pendingVisitors[0].name").value("Waiting Guest"))
                .andExpect(jsonPath("$.pendingDeliveries.length()").value(1))
                .andExpect(jsonPath("$.todayVisitorCount").value(2))
                .andExpect(jsonPath("$.insideNowCount").value(1))
                .andExpect(jsonPath("$.todayDeliveryCount").value(1))
                .andExpect(jsonPath("$.staffInsideCount").value(1))
                .andExpect(jsonPath("$.openComplaintCount").value(1))
                .andExpect(jsonPath("$.unreadNoticeCount").value(1))
                .andExpect(jsonPath("$.latestNotices[0].title").value("AGM on Sunday"))
                .andExpect(jsonPath("$.recentActivity.length()").value(5));
        getWith("/api/v1/residents/activity?kind=COMPLAINT", world.resident101Token())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Complaint raised: Lift not working"));
        JsonNode feed = sendOk("GET", "/api/v1/residents/activity?size=50", null, world.resident101Token());
        assertThat(feed.get("content").valueStream().map(i -> i.get("title").asString()).toList())
                .contains("Inside Guest entered", "Waiting Guest is waiting at the gate", "Sunita Devi checked in",
                        "QuickBite delivery is waiting")
                .noneMatch(t -> t.contains("Neighbour"));
    }

    @Test
    void guardDashboardCountsTheWholeSociety() throws Exception {
        getWith("/api/v1/guards/dashboard", world.guardToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todayVisitorCount").value(3))
                .andExpect(jsonPath("$.pendingApprovalCount").value(2))
                .andExpect(jsonPath("$.approvedWaitingCount").value(0))
                .andExpect(jsonPath("$.insideNowCount").value(1))
                .andExpect(jsonPath("$.deliveriesTodayCount").value(1))
                .andExpect(jsonPath("$.staffInsideCount").value(1))
                .andExpect(jsonPath("$.recentActivity.length()").value(5));
        getWith("/api/v1/guards/activity?kind=VISITOR", world.guardToken()).andExpect(jsonPath("$.totalElements").value(3));
        getWith("/api/v1/guards/dashboard", world.resident101Token()).andExpect(status().isForbidden());
        getWith("/api/v1/residents/dashboard", world.guardToken()).andExpect(status().isForbidden());
    }

    @Test
    void adminDashboardAndReports() throws Exception {
        getWith("/api/v1/admin/dashboard", world.adminToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitorsToday").value(3))
                .andExpect(jsonPath("$.pendingVisitorApprovals").value(2))
                .andExpect(jsonPath("$.visitorsInside").value(1))
                .andExpect(jsonPath("$.pendingDeliveryApprovals").value(1))
                .andExpect(jsonPath("$.openComplaints").value(1))
                .andExpect(jsonPath("$.activeResidents").value(2))
                .andExpect(jsonPath("$.activeGuards").value(1))
                .andExpect(jsonPath("$.guardsOnDuty").value(1))
                .andExpect(jsonPath("$.totalFlats").value(8))
                .andExpect(jsonPath("$.occupiedFlats").value(2));

        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        getWith("/api/v1/reports/visitors?from=" + today + "&to=" + today, world.adminToken())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.entered").value(1))
                .andExpect(jsonPath("$.byType.GUEST").value(2))
                .andExpect(jsonPath("$.byStatus.PENDING").value(2))
                .andExpect(jsonPath("$.daily.length()").value(1));
        getWith("/api/v1/reports/deliveries", world.adminToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.byCategory.FOOD").value(1))
                .andExpect(jsonPath("$.daily.length()").value(7));
        getWith("/api/v1/reports/complaints", world.adminToken())
                .andExpect(jsonPath("$.raised").value(1))
                .andExpect(jsonPath("$.currentlyOpen").value(1));
        getWith("/api/v1/reports/visitors?from=2026-01-01&to=2027-12-31", world.adminToken())
                .andExpect(status().isBadRequest());
        getWith("/api/v1/reports/visitors", world.guardToken()).andExpect(status().isForbidden());
        getWith("/api/v1/admin/dashboard", world.resident101Token()).andExpect(status().isForbidden());
    }

    @Test
    void auditLogIsVisibleToAdminsOnly() throws Exception {
        JsonNode logs = sendOk("GET", "/api/v1/audit-logs?size=100", null, world.adminToken());
        assertThat(logs.get("content").valueStream().map(l -> l.get("action").asString()).toList())
                .contains("GUARD_CREATED", "RESIDENT_CREATED", "FLATS_BULK_CREATED", "GATE_CREATED", "STAFF_CREATED",
                        "NOTICE_PUBLISHED");
        getWith("/api/v1/audit-logs?action=GUARD_CREATED", world.adminToken())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actorName").value("Society Admin"));
        getWith("/api/v1/audit-logs", world.resident101Token()).andExpect(status().isForbidden());
        getWith("/api/v1/audit-logs", world.guardToken()).andExpect(status().isForbidden());
        World other = worlds().build("Other Audit Society");
        getWith("/api/v1/audit-logs?societyId=" + world.societyId(), other.adminToken()).andExpect(status().isForbidden());
    }
}
