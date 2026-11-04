package com.mygateapp.auth.dto;

import com.mygateapp.users.dto.UserMeResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RequestOtpRequest(
            @Schema(example = "9876543210") @NotBlank(message = "Enter a mobile number") String mobile,
            @Schema(example = "+91") String countryCode) {
    }

    public record OtpChallengeResponse(String requestId, long resendAfterSeconds, long expiresInSeconds) {
    }

    public record VerifyOtpRequest(
            @Schema(example = "9876543210") @NotBlank(message = "Enter a mobile number") String mobile,
            @Schema(example = "123456") @NotBlank(message = "Enter the 6-digit code")
            @Pattern(regexp = "^\\d{4,8}$", message = "The code must be 6 digits") String otp,
            @Size(max = 64) String requestId,
            @Schema(example = "+91") String countryCode) {
    }

    public record RefreshRequest(@NotBlank(message = "refreshToken is required") @Size(max = 200) String refreshToken) {
    }

    public record LogoutRequest(@Size(max = 200) String refreshToken) {
    }

    /** Same shape for verify-otp and refresh (Flutter {@code AuthTokens.fromJson}). */
    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            long refreshExpiresIn,
            UserMeResponse user) {
    }
}
