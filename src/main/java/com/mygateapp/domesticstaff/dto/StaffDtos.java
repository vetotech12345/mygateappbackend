package com.mygateapp.domesticstaff.dto;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.dto.Refs;
import com.mygateapp.domesticstaff.entity.DomesticStaff;
import com.mygateapp.users.dto.UserDtos;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class StaffDtos {

    private StaffDtos() {
    }

    public record StaffRequest(
            @Schema(description = "Defaults to the caller's society (create only)") UUID societyId,
            @Schema(example = "Sunita Devi") @NotBlank(message = "Name is required")
            @Size(min = 2, max = 60, message = "Name must be 2-60 characters")
            @Pattern(regexp = UserDtos.NAME_PATTERN, message = "Name can only contain letters") String name,
            @NotBlank(message = "Mobile number is required") String mobile,
            @NotNull(message = "Category is required") DomesticStaff.Category category,
            @Size(max = 50) List<@NotNull UUID> flatIds,
            @Size(max = 30) String passNumber,
            LocalDate workingSince) {
    }

    /** Matches the Flutter {@code DomesticStaffDto}. */
    public record StaffResponse(
            UUID id,
            String name,
            String mobile,
            DomesticStaff.Category category,
            String photoUrl,
            List<Refs.FlatRef> assignedFlats,
            boolean inside,
            Instant lastCheckInAt,
            Instant lastCheckOutAt,
            LocalDate workingSince,
            String passNumber,
            ActiveStatus status) {
    }

    /** Matches the Flutter {@code AttendanceRecord}. */
    public record AttendanceResponse(
            UUID id,
            UUID staffId,
            String staffName,
            DomesticStaff.Category category,
            Instant checkInAt,
            Instant checkOutAt,
            Refs.GateRef gate,
            String recordedBy,
            String checkOutRecordedBy) {
    }
}
