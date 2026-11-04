package com.mygateapp.auth;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mygateapp.security.Role;
import com.mygateapp.society.entity.Society;
import com.mygateapp.society.entity.SocietyMembership;
import com.mygateapp.support.IntegrationTest;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.enums.UserStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Uses stricter limits than the shared test profile (separate Spring context). */
@TestPropertySource(properties = {
        "app.otp.max-requests-per-mobile-per-hour=3",
        "app.otp.resend-cooldown=PT0S"
})
class OtpRateLimitIT extends IntegrationTest {

    @Test
    void otpRequestsArelimitedPerMobile() throws Exception {
        Society society = data.society("Rate Limit Society");
        String mobile = randomMobile();
        User user = data.user(mobile, "Ravi Kumar", UserStatus.ACTIVE);
        data.membership(user.getId(), society.getId(), Role.GUARD, SocietyMembership.Status.ACTIVE);

        for (int i = 0; i < 3; i++) {
            postJson("/api/v1/auth/request-otp", Map.of("mobile", mobile), null).andExpect(status().isOk());
        }
        postJson("/api/v1/auth/request-otp", Map.of("mobile", mobile), null)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("OTP_RATE_LIMITED"));
    }

    @Test
    void unknownNumbersCountTowardsTheLimitToo() throws Exception {
        String unknown = randomMobile();
        for (int i = 0; i < 3; i++) {
            postJson("/api/v1/auth/request-otp", Map.of("mobile", unknown), null).andExpect(status().isNotFound());
        }
        postJson("/api/v1/auth/request-otp", Map.of("mobile", unknown), null).andExpect(status().isTooManyRequests());
    }
}
