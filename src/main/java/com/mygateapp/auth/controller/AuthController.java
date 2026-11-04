package com.mygateapp.auth.controller;

import com.mygateapp.auth.dto.AuthDtos;
import com.mygateapp.auth.service.AuthService;
import com.mygateapp.security.AccessService;
import com.mygateapp.users.dto.UserMeResponse;
import com.mygateapp.users.service.UserMeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Mobile number + OTP login, token refresh and logout")
public class AuthController {

    private final AuthService authService;
    private final UserMeService userMeService;
    private final AccessService access;

    public AuthController(AuthService authService, UserMeService userMeService, AccessService access) {
        this.authService = authService;
        this.userMeService = userMeService;
        this.access = access;
    }

    @PostMapping("/request-otp")
    @SecurityRequirements
    @Operation(summary = "Send an OTP to a registered mobile number",
            description = "404 USER_NOT_REGISTERED, 403 USER_INACTIVE / NO_ACTIVE_MEMBERSHIP, 429 OTP_RATE_LIMITED")
    public AuthDtos.OtpChallengeResponse requestOtp(@Valid @RequestBody AuthDtos.RequestOtpRequest request,
                                                    HttpServletRequest http) {
        return authService.requestOtp(request, http.getRemoteAddr());
    }

    @PostMapping("/verify-otp")
    @SecurityRequirements
    @Operation(summary = "Verify the OTP and receive an access + refresh token pair",
            description = "400 OTP_INVALID (with attempts left), OTP_EXPIRED, OTP_LOCKED")
    public AuthDtos.TokenResponse verifyOtp(@Valid @RequestBody AuthDtos.VerifyOtpRequest request, HttpServletRequest http) {
        return authService.verifyOtp(request, http.getRemoteAddr(), http.getHeader(HttpHeaders.USER_AGENT));
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Exchange a refresh token for a new token pair (the refresh token is rotated)")
    public AuthDtos.TokenResponse refresh(@Valid @RequestBody AuthDtos.RefreshRequest request, HttpServletRequest http) {
        return authService.refresh(request, http.getRemoteAddr(), http.getHeader(HttpHeaders.USER_AGENT));
    }

    @PostMapping("/logout")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the session of the given refresh token (idempotent)")
    public void logout(@Valid @RequestBody(required = false) AuthDtos.LogoutRequest request) {
        authService.logout(request);
    }

    @GetMapping("/me")
    @Operation(summary = "Current user (alias of GET /api/v1/users/me)")
    public UserMeResponse me() {
        return userMeService.build(access.currentUserId());
    }
}
