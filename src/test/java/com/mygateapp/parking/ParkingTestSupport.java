package com.mygateapp.parking;

import com.mygateapp.support.IntegrationTest;
import com.mygateapp.support.MutableClock;
import com.mygateapp.support.World;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/** API helpers for parking integration tests. Everything is created through the public HTTP API. */
public abstract class ParkingTestSupport extends IntegrationTest {

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcTemplate jdbc;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // -------------------------------------------------------------- settings

    protected static Map<String, Object> defaultSettings() {
        Map<String, Object> s = new HashMap<>();
        s.put("visitorParkingEnabled", true);
        s.put("visitorParkingAutoApprove", false);
        s.put("maxVisitorParkingHours", 12);
        s.put("paidParkingEnabled", false);
        s.put("externalPaidParkingAllowed", false);
        s.put("freeGraceMinutes", 15);
        s.put("maxVehiclesPerFlat", 4);
        s.put("maxTemporaryPermitDays", 30);
        s.put("residentEntryRequiresPermit", true);
        s.put("paymentRequiredBeforeExit", true);
        s.put("emergencyOverrideAllowed", false);
        s.put("vehicleNumberFormat", "INDIA");
        s.put("rules", List.of("Park only in your allotted slot."));
        return s;
    }

    protected JsonNode putSettings(World w, Map<String, Object> overrides) throws Exception {
        Map<String, Object> body = defaultSettings();
        body.putAll(overrides);
        return sendOk("PUT", "/api/v1/societies/" + w.societyId() + "/parking/settings", body, w.adminToken());
    }

    // ------------------------------------------------------------- inventory

    protected UUID area(World w, String code, String usage, String... types) throws Exception {
        return id(sendOk("POST", "/api/v1/parking/areas", Map.of("code", code, "name", "Area " + code, "level", code,
                "usage", usage, "vehicleTypes", List.of(types)), w.adminToken()));
    }

    protected UUID slot(World w, UUID areaId, String number, String type) throws Exception {
        return id(sendOk("POST", "/api/v1/parking/slots", Map.of("areaId", areaId, "number", number, "vehicleType", type),
                w.adminToken()));
    }

    protected UUID slot(World w, UUID areaId, String number, String type, String usage) throws Exception {
        return id(sendOk("POST", "/api/v1/parking/slots", Map.of("areaId", areaId, "number", number, "vehicleType", type,
                "usage", usage), w.adminToken()));
    }

    // -------------------------------------------------------------- vehicles

    protected UUID vehicle(String residentToken, UUID flatId, String number) throws Exception {
        return id(sendOk("POST", "/api/v1/vehicles", Map.of("registrationNumber", number, "vehicleType", "CAR",
                "flatId", flatId, "make", "Hyundai", "model", "Creta", "color", "White"), residentToken));
    }

    protected UUID approvedVehicle(World w, String residentToken, UUID flatId, String number) throws Exception {
        UUID id = vehicle(residentToken, flatId, number);
        sendOk("POST", "/api/v1/vehicles/" + id + "/approve", Map.of(), w.adminToken());
        return id;
    }

    protected UUID allocate(World w, UUID slotId, UUID flatId, UUID vehicleId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("slotId", slotId);
        body.put("allocationType", "PERMANENT");
        body.put("flatId", flatId);
        if (vehicleId != null) {
            body.put("vehicleId", vehicleId);
        }
        return id(sendOk("POST", "/api/v1/parking/allocations", body, w.adminToken()));
    }

    // ------------------------------------------------------------------ gate

    protected JsonNode verify(String guardToken, String number) throws Exception {
        return sendOk("POST", "/api/v1/parking/verifications", Map.of("vehicleNumber", number), guardToken);
    }

    protected Map<String, Object> entryBody(JsonNode verification, String entryType) {
        Map<String, Object> body = new HashMap<>();
        body.put("verificationId", verification.get("verificationId").asString());
        body.put("vehicleNumber", verification.get("vehicleNumber").asString());
        body.put("entryType", entryType);
        return body;
    }

    protected JsonNode enter(String guardToken, JsonNode verification, String entryType) throws Exception {
        return sendOk("POST", "/api/v1/parking/sessions", entryBody(verification, entryType), guardToken);
    }

    protected JsonNode close(String guardToken, String sessionId) throws Exception {
        return sendOk("POST", "/api/v1/parking/sessions/" + sessionId + "/close", Map.of(), guardToken);
    }

    protected org.springframework.test.web.servlet.ResultActions postWithKey(String url, Object body, String token,
                                                                             String key) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON).content(json(body))
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key));
    }

    // --------------------------------------------------------------- helpers

    protected static UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asString());
    }

    protected static String text(JsonNode node, String path) {
        JsonNode current = node;
        for (String part : path.split("\\.")) {
            current = current == null ? null : current.get(part);
        }
        return current == null || current.isNull() ? null : current.asString();
    }

    /** Runs the calls concurrently and returns their HTTP status codes. */
    protected static List<Integer> concurrently(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Integer>> futures = pool.invokeAll(calls);
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    protected int httpStatus(String method, String url, Object body, String token) throws Exception {
        return send(method, url, body, token).andReturn().getResponse().getStatus();
    }
}
