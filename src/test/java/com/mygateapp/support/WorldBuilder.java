package com.mygateapp.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mygateapp.users.entity.User;
import com.mygateapp.users.enums.UserStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Builds a {@link World} through the HTTP API, exactly as the React admin panel would. */
public final class WorldBuilder {

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final TestData data;

    public WorldBuilder(MockMvc mvc, ObjectMapper json, TestData data) {
        this.mvc = mvc;
        this.json = json;
        this.data = data;
    }

    public String superAdminToken() throws Exception {
        String mobile = IntegrationTest.randomMobile();
        User user = data.user(mobile, "Platform Admin", UserStatus.ACTIVE);
        data.grantSuperAdmin(user.getId());
        return login(mobile);
    }

    public World build(String societyName) throws Exception {
        String superToken = superAdminToken();
        String code = "W" + Integer.toString(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE), 36).toUpperCase();
        UUID societyId = id(call("POST", "/api/v1/societies", superToken, Map.of(
                "code", code, "name", societyName, "addressLine1", "1 MG Road", "city", "Pune",
                "state", "Maharashtra", "pinCode", "411001"), 201));

        String adminMobile = IntegrationTest.randomMobile();
        call("POST", "/api/v1/societies/" + societyId + "/admins", superToken,
                Map.of("name", "Society Admin", "mobile", adminMobile), 201);
        String adminToken = login(adminMobile);

        UUID towerId = id(call("POST", "/api/v1/towers", adminToken, Map.of("code", "A", "name", "Tower A", "floorCount", 2), 201));
        call("POST", "/api/v1/flats/bulk", adminToken,
                Map.of("towerId", towerId, "fromFloor", 1, "toFloor", 2, "unitsPerFloor", 4), 201);
        JsonNode flats = call("GET", "/api/v1/flats?size=50", adminToken, null, 200).get("content");
        Map<String, UUID> flatByNumber = new HashMap<>();
        flats.forEach(f -> flatByNumber.put(f.get("flatNumber").asString(), UUID.fromString(f.get("id").asString())));

        UUID gateId = id(call("POST", "/api/v1/gates", adminToken, Map.of("name", "Main Gate", "gateType", "MAIN"), 201));

        String guardMobile = IntegrationTest.randomMobile();
        UUID guardId = id(call("POST", "/api/v1/guards", adminToken, Map.of(
                "name", "Ramesh Yadav", "mobile", guardMobile, "gateId", gateId, "shiftName", "Day shift",
                "shiftStart", "08:00", "shiftEnd", "20:00"), 201));
        String guardToken = login(guardMobile);
        call("POST", "/api/v1/guards/me/duty", guardToken, Map.of("onDuty", true), 200);

        String r101 = IntegrationTest.randomMobile();
        call("POST", "/api/v1/residents", adminToken, Map.of("name", "Aarav Mehta", "mobile", r101,
                "flatId", flatByNumber.get("101"), "occupancy", "OWNER"), 201);
        JsonNode r101Login = loginBody(r101);

        String r102 = IntegrationTest.randomMobile();
        call("POST", "/api/v1/residents", adminToken, Map.of("name", "Neha Sharma", "mobile", r102,
                "flatId", flatByNumber.get("102"), "occupancy", "OWNER"), 201);

        return new World(societyId, adminToken, towerId, gateId, flatByNumber.get("101"), flatByNumber.get("102"),
                flatByNumber.get("201"), guardId, guardMobile, guardToken, r101,
                r101Login.get("accessToken").asString(), UUID.fromString(r101Login.get("user").get("id").asString()),
                r102, login(r102));
    }

    public String login(String mobile) throws Exception {
        return loginBody(mobile).get("accessToken").asString();
    }

    public JsonNode loginBody(String mobile) throws Exception {
        call("POST", "/api/v1/auth/request-otp", null, Map.of("mobile", mobile), 200);
        return call("POST", "/api/v1/auth/verify-otp", null, Map.of("mobile", mobile, "otp", IntegrationTest.OTP), 200);
    }

    public JsonNode call(String method, String url, String token, Object body, int expectedStatus) throws Exception {
        var request = switch (method) {
            case "GET" -> get(url);
            case "POST" -> post(url);
            default -> throw new IllegalArgumentException(method);
        };
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        MvcResult result = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isEmpty() ? json.createObjectNode() : json.readTree(content);
    }

    private static UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asString());
    }

    public static List<String> ids(JsonNode page) {
        return page.get("content").valueStream().map(n -> n.get("id").asString()).toList();
    }
}
