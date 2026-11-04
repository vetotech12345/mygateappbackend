package com.mygateapp.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Base class for full-stack tests: real HTTP layer (MockMvc), security, PostgreSQL and Redis. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestData.class})
public abstract class IntegrationTest {

    public static final String OTP = "123456";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected TestData data;

    /** Random valid Indian mobile number (unique per test run with overwhelming probability). */
    public static String randomMobile() {
        return "9" + String.format("%09d", ThreadLocalRandom.current().nextLong(1_000_000_000L));
    }

    protected String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected ResultActions postJson(String url, Object body, String token) throws Exception {
        MockHttpServletRequestBuilder request = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "" : json(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request);
    }

    protected ResultActions getWith(String url, String token) throws Exception {
        MockHttpServletRequestBuilder request = get(url);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request);
    }

    /** Full OTP login through the public API. Returns the token response. */
    protected JsonNode login(String mobile) throws Exception {
        postJson("/api/v1/auth/request-otp", Map.of("mobile", mobile, "countryCode", "+91"), null)
                .andExpect(status().isOk());
        MvcResult result = postJson("/api/v1/auth/verify-otp", Map.of("mobile", mobile, "otp", OTP), null)
                .andExpect(status().isOk())
                .andReturn();
        return body(result);
    }

    protected String accessToken(String mobile) throws Exception {
        return login(mobile).get("accessToken").asString();
    }

    protected WorldBuilder worlds() {
        return new WorldBuilder(mvc, objectMapper, data);
    }

    protected ResultActions send(String method, String url, Object body, String token) throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(url);
            case "POST" -> post(url);
            case "PUT" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url);
            case "PATCH" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url);
            case "DELETE" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url);
            default -> throw new IllegalArgumentException(method);
        };
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json(body));
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request);
    }

    protected JsonNode sendOk(String method, String url, Object body, String token) throws Exception {
        MvcResult result = send(method, url, body, token)
                .andExpect(r -> {
                    int code = r.getResponse().getStatus();
                    if (code < 200 || code >= 300) {
                        throw new AssertionError("Expected 2xx for " + method + " " + url + " but got " + code + ": "
                                + r.getResponse().getContentAsString());
                    }
                })
                .andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isEmpty() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
    }
}
