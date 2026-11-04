package com.mygateapp.users.controller;

import com.mygateapp.security.AccessService;
import com.mygateapp.users.dto.UserDtos;
import com.mygateapp.users.dto.UserMeResponse;
import com.mygateapp.users.service.UserMeService;
import com.mygateapp.users.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Current user profile and account status")
public class UserController {

    private final UserMeService userMeService;
    private final UserService userService;
    private final AccessService access;

    public UserController(UserMeService userMeService, UserService userService, AccessService access) {
        this.userMeService = userMeService;
        this.userService = userService;
        this.access = access;
    }

    @GetMapping("/me")
    @Operation(summary = "Current user with role, society, flats (resident) or gate and permissions (guard)")
    public UserMeResponse me() {
        return userMeService.build(access.currentUserId());
    }

    @PatchMapping("/me")
    @Operation(summary = "Update own name and email. Society, flats and role cannot be changed here.")
    public UserMeResponse updateMe(@Valid @RequestBody UserDtos.UpdateProfileRequest request) {
        return userService.updateProfile(request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Activate or deactivate a user account (platform admin). Deactivation ends all sessions.")
    public UserMeResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody UserDtos.UpdateUserStatusRequest request) {
        return userService.updateStatus(id, request);
    }
}
