package com.mygateapp.guards.dto;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.dto.Refs;
import com.mygateapp.guards.entity.GuardAssignment;
import com.mygateapp.guards.entity.SocietyGate;
import com.mygateapp.security.GuardPermission;
import com.mygateapp.users.dto.UserDtos;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class GuardDtos {

    private GuardDtos() {
    }

    public record CreateGuardRequest(
            @Schema(description = "Defaults to the caller's society") UUID societyId,
            @Schema(example = "Ramesh Yadav") @NotBlank(message = "Name is required")
            @Size(min = 2, max = 60, message = "Name must be 2-60 characters")
            @Pattern(regexp = UserDtos.NAME_PATTERN, message = "Name can only contain letters") String name,
            @Schema(example = "9876500001") @NotBlank(message = "Mobile number is required") String mobile,
            String countryCode,
            @Size(max = 30) String employeeCode,
            @Size(max = 100) String agencyName,
            @Schema(description = "Defaults to all gate permissions") Set<GuardPermission> permissions,
            @Schema(description = "Optional initial gate assignment") UUID gateId,
            @Size(max = 40) String shiftName,
            @Schema(type = "string", example = "08:00") LocalTime shiftStart,
            @Schema(type = "string", example = "20:00") LocalTime shiftEnd) {
    }

    public record UpdateGuardRequest(
            @Size(min = 2, max = 60, message = "Name must be 2-60 characters")
            @Pattern(regexp = UserDtos.NAME_PATTERN, message = "Name can only contain letters") String name,
            @Size(max = 30) String employeeCode,
            @Size(max = 100) String agencyName,
            Set<GuardPermission> permissions) {
    }

    public record AssignGateRequest(
            @NotNull(message = "gateId is required") UUID gateId,
            @Size(max = 40) String shiftName,
            @Schema(type = "string", example = "08:00") LocalTime shiftStart,
            @Schema(type = "string", example = "20:00") LocalTime shiftEnd) {
    }

    public record DutyRequest(@NotNull(message = "onDuty is required") Boolean onDuty) {
    }

    public record AssignmentView(UUID id, Refs.GateRef gate, String shiftName, LocalTime shiftStart, LocalTime shiftEnd,
                                 String shiftLabel, boolean active, Instant validFrom, Instant validUntil) {

        public static AssignmentView of(GuardAssignment a) {
            return new AssignmentView(a.getId(), a.getGate().toRef(), a.getShiftName(), a.getShiftStart(), a.getShiftEnd(),
                    a.shiftLabel(), a.isActive(), a.getValidFrom(), a.getValidUntil());
        }
    }

    public record GuardResponse(
            UUID id, UUID societyId, UUID userId, String name, String mobile, String employeeCode, String agencyName,
            ActiveStatus status, boolean onDuty, Instant dutyChangedAt, List<GuardPermission> permissions,
            AssignmentView assignment, Instant createdAt) {
    }

    public record CreateGateRequest(
            @Schema(description = "Defaults to the caller's society") UUID societyId,
            @Schema(example = "Main Gate") @NotBlank(message = "Gate name is required") @Size(max = 60) String name,
            SocietyGate.GateType gateType) {
    }

    public record UpdateGateRequest(@NotBlank(message = "Gate name is required") @Size(max = 60) String name,
                                    SocietyGate.GateType gateType) {
    }

    public record GateResponse(UUID id, UUID societyId, String name, SocietyGate.GateType gateType, ActiveStatus status,
                               Instant createdAt) {

        public static GateResponse of(SocietyGate gate) {
            return new GateResponse(gate.getId(), gate.getSocietyId(), gate.getName(), gate.getGateType(), gate.getStatus(),
                    gate.getCreatedAt());
        }
    }

    /** One action performed by a guard (admin "guard activity log"). */
    public record GuardActivityItem(Instant at, String kind, String title, UUID referenceId, String status) {
    }
}
