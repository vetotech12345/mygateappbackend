package com.mygateapp.residents.dto;

import com.mygateapp.common.dto.Refs;
import com.mygateapp.residents.entity.FlatMembership;
import com.mygateapp.users.dto.UserDtos;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ResidentDtos {

    private ResidentDtos() {
    }

    @Schema(description = "Admins create ACTIVE residents. A flat OWNER may request a co-resident or tenant, which starts "
            + "as PENDING until an admin approves it.")
    public record CreateResidentRequest(
            @Schema(example = "Priya Mehta") @NotBlank(message = "Name is required")
            @Size(min = 2, max = 60, message = "Name must be 2-60 characters")
            @Pattern(regexp = UserDtos.NAME_PATTERN, message = "Name can only contain letters") String name,
            @Schema(example = "9876543211") @NotBlank(message = "Mobile number is required") String mobile,
            String countryCode,
            @Email(message = "Enter a valid email address") @Size(max = 254) String email,
            @NotNull(message = "flatId is required") UUID flatId,
            @NotNull(message = "occupancy is required") FlatMembership.Occupancy occupancy,
            Boolean primary,
            LocalDate startDate) {
    }

    public record UpdateResidentRequest(
            @Size(min = 2, max = 60, message = "Name must be 2-60 characters")
            @Pattern(regexp = UserDtos.NAME_PATTERN, message = "Name can only contain letters") String name,
            @Email(message = "Enter a valid email address") @Size(max = 254) String email,
            FlatMembership.Occupancy occupancy,
            Boolean primary) {
    }

    public record DecisionRequest(@Size(max = 200) String reason) {
    }

    public record EndResidencyRequest(LocalDate endDate, @Size(max = 200) String reason) {
    }

    public record TransferRequest(@NotNull(message = "flatId is required") UUID flatId,
                                  FlatMembership.Occupancy occupancy,
                                  LocalDate startDate,
                                  @Size(max = 200) String reason) {
    }

    public record FamilyMemberResponse(UUID id, UUID flatId, String name, String relation, String mobile) {
    }

    public record AddFamilyMemberRequest(
            @Schema(description = "Defaults to the resident's primary flat") UUID flatId,
            @NotBlank(message = "Name is required") @Size(min = 2, max = 60)
            @Pattern(regexp = UserDtos.NAME_PATTERN, message = "Name can only contain letters") String name,
            @NotBlank(message = "Relation is required") @Size(max = 30) String relation,
            String mobile) {
    }

    /** A resident = one person's membership of one flat ({@code id} is the membership id). */
    public record ResidentResponse(
            UUID id, UUID userId, String name, String mobile, String email, Refs.FlatRef flat,
            FlatMembership.Occupancy occupancy, FlatMembership.Status status, boolean primary,
            LocalDate startDate, LocalDate endDate, String endReason, Instant approvedAt, Instant createdAt,
            List<FamilyMemberResponse> familyMembers) {
    }

    public record MyResidentProfile(UUID userId, String name, String mobile, String email,
                                    List<ResidentResponse> residencies) {
    }
}
