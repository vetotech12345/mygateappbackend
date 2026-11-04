package com.mygateapp.users.dto;

import com.mygateapp.common.dto.Refs;
import com.mygateapp.security.GuardPermission;
import com.mygateapp.security.Role;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /users/me}. Shape matches the Flutter {@code AppUserDto}: {@code role} is the role for the active society
 * context and the only role the mobile app uses; {@code roles} and {@code memberships} serve the admin dashboard.
 */
public record UserMeResponse(
        UUID id,
        String name,
        String mobile,
        String countryCode,
        String email,
        String role,
        List<String> roles,
        String status,
        boolean profileComplete,
        String avatarUrl,
        UUID activeSocietyId,
        ResidentBlock resident,
        GuardBlock guard,
        List<MembershipView> memberships) {

    public record ResidentBlock(
            Refs.SocietyRef society,
            List<Refs.FlatRef> flats,
            UUID primaryFlatId,
            String occupancy,
            List<FamilyMemberView> familyMembers) {
    }

    public record FamilyMemberView(UUID id, String name, String relation, String mobile) {
    }

    public record GuardBlock(
            UUID id,
            Refs.SocietyRef society,
            Refs.GateRef gate,
            String shift,
            boolean onDuty,
            List<GuardPermission> permissions) {
    }

    public record MembershipView(UUID societyId, String societyName, Role role, String status) {
    }
}
