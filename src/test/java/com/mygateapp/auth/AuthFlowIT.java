package com.mygateapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mygateapp.security.Role;
import com.mygateapp.society.entity.Society;
import com.mygateapp.society.entity.SocietyMembership;
import com.mygateapp.support.IntegrationTest;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.enums.UserStatus;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

class AuthFlowIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    String mobile;
    User user;

    @BeforeEach
    void registeredResident() {
        Society society = data.society("Auth Test Society");
        mobile = randomMobile();
        user = data.user(mobile, "Asha Rao", UserStatus.ACTIVE);
        data.membership(user.getId(), society.getId(), Role.SOCIETY_ADMIN, SocietyMembership.Status.ACTIVE);
    }

    @Test
    void validOtpIssuesTokensAndProfile() throws Exception {
        postJson("/api/v1/auth/request-otp", Map.of("mobile", mobile, "countryCode", "+91"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.expiresInSeconds").value(300));

        JsonNode tokens = body(postJson("/api/v1/auth/verify-otp", Map.of("mobile", mobile, "otp", OTP), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.mobile").value(mobile))
                .andExpect(jsonPath("$.user.role").value("SOCIETY_ADMIN"))
                .andReturn());

        String access = tokens.get("accessToken").asString();
        getWith("/api/v1/users/me", access).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Asha Rao"));
        getWith("/api/v1/auth/me", access).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.getId().toString()));

        // Only a hash of the refresh token is stored.
        String stored = jdbc.queryForObject("SELECT token_hash FROM refresh_sessions WHERE user_id = ?", String.class, user.getId());
        assertThat(stored).isNotEqualTo(tokens.get("refreshToken").asString()).hasSize(64);
    }

    @Test
    void unregisteredNumberIsRejected() throws Exception {
        postJson("/api/v1/auth/request-otp", Map.of("mobile", randomMobile()), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_REGISTERED"));
    }

    @Test
    void invalidMobileFormatIsAFieldError() throws Exception {
        postJson("/api/v1/auth/request-otp", Map.of("mobile", "12345"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("mobile"));
    }

    @Test
    void inactiveAccountIsBlocked() throws Exception {
        data.setUserStatus(user.getId(), UserStatus.INACTIVE);
        postJson("/api/v1/auth/request-otp", Map.of("mobile", mobile), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_INACTIVE"));
    }

    @Test
    void userWithoutMembershipCannotSignIn() throws Exception {
        String loner = randomMobile();
        data.user(loner, "No Society", UserStatus.ACTIVE);
        postJson("/api/v1/auth/request-otp", Map.of("mobile", loner), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_MEMBERSHIP"));
    }

    @Test
    void wrongOtpReportsAttemptsLeftThenLocks() throws Exception {
        postJson("/api/v1/auth/request-otp", Map.of("mobile", mobile), null).andExpect(status().isOk());
        postJson("/api/v1/auth/verify-otp", Map.of("mobile", mobile, "otp", "000000"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"))
                .andExpect(jsonPath("$.message").value("Incorrect code. 4 attempts left."));
        for (int i = 0; i < 3; i++) {
            postJson("/api/v1/auth/verify-otp", Map.of("mobile", mobile, "otp", "000000"), null)
                    .andExpect(jsonPath("$.code").value("OTP_INVALID"));
        }
        postJson("/api/v1/auth/verify-otp", Map.of("mobile", mobile, "otp", "000000"), null)
                .andExpect(jsonPath("$.code").value("OTP_LOCKED"));
        // Even the correct code no longer works once locked.
        postJson("/api/v1/auth/verify-otp", Map.of("mobile", mobile, "otp", OTP), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTP_LOCKED"));
    }

    @Test
    void otpWithoutRequestOrAlreadyUsedIsExpired() throws Exception {
        postJson("/api/v1/auth/verify-otp", Map.of("mobile", mobile, "otp", OTP), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
        login(mobile);
        // A consumed code cannot be replayed.
        postJson("/api/v1/auth/verify-otp", Map.of("mobile", mobile, "otp", OTP), null)
                .andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
    }

    @Test
    void refreshRotatesAndReuseRevokesTheSession() throws Exception {
        JsonNode first = login(mobile);
        String refresh1 = first.get("refreshToken").asString();

        JsonNode second = body(postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh1), null)
                .andExpect(status().isOk()).andReturn());
        String refresh2 = second.get("refreshToken").asString();
        assertThat(refresh2).isNotEqualTo(refresh1);
        getWith("/api/v1/users/me", second.get("accessToken").asString()).andExpect(status().isOk());

        // Replaying the rotated token is treated as theft: the whole session is revoked.
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh1), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh2), null)
                .andExpect(status().isUnauthorized());
        getWith("/api/v1/users/me", second.get("accessToken").asString())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }

    @Test
    void logoutRevokesRefreshAndAccessTokens() throws Exception {
        JsonNode tokens = login(mobile);
        String access = tokens.get("accessToken").asString();
        // An (expired or valid) bearer header on logout is ignored.
        postJson("/api/v1/auth/logout", Map.of("refreshToken", tokens.get("refreshToken").asString()), "garbage")
                .andExpect(status().isNoContent());
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", tokens.get("refreshToken").asString()), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        getWith("/api/v1/users/me", access).andExpect(status().isUnauthorized());
    }

    @Test
    void deactivatedUserLosesAccessImmediately() throws Exception {
        String access = accessToken(mobile);
        getWith("/api/v1/users/me", access).andExpect(status().isOk());
        data.setUserStatus(user.getId(), UserStatus.INACTIVE);
        getWith("/api/v1/users/me", access)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("This account is inactive. Please contact your society office to reactivate it."));
    }

    @Test
    void profileUpdateValidatesFields() throws Exception {
        String access = accessToken(mobile);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + access)
                        .contentType("application/json")
                        .content(json(Map.of("name", "R2D2", "email", "not-an-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + access)
                        .contentType("application/json")
                        .content(json(Map.of("name", "Asha  Rao-Iyer", "email", "Asha@Example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Asha Rao-Iyer"))
                .andExpect(jsonPath("$.email").value("asha@example.com"));
    }
}
