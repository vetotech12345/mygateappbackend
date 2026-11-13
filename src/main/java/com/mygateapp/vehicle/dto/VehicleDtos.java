package com.mygateapp.vehicle.dto;

import com.mygateapp.common.dto.Refs.FlatRef;
import com.mygateapp.common.dto.Refs.SocietyRef;
import com.mygateapp.parking.dto.ParkingRefs.PermitRef;
import com.mygateapp.parking.dto.ParkingRefs.SlotRef;
import com.mygateapp.vehicle.enums.VehicleEnums.Category;
import com.mygateapp.vehicle.enums.VehicleEnums.NumberFormat;
import com.mygateapp.vehicle.enums.VehicleEnums.RegistrationStatus;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class VehicleDtos {

    private VehicleDtos() {
    }

    @Schema(description = "Residents register RESIDENT vehicles of their own flat. Admins may register any category; "
            + "admin registrations are approved immediately.")
    public record CreateVehicleRequest(
            @Schema(example = "MH 12 AB 1234") @NotBlank(message = "Enter the vehicle number") @Size(max = 20) String registrationNumber,
            @NotNull(message = "vehicleType is required") VehicleType vehicleType,
            @Size(max = 40) String make,
            @Size(max = 40) String model,
            @Size(max = 30) String color,
            @Schema(description = "Required for RESIDENT vehicles") UUID flatId,
            @Schema(description = "Admins only; residents always register RESIDENT") Category category,
            @Schema(description = "Admins: owner / driver label for society, staff or service vehicles") @Size(max = 80) String ownerName,
            @Schema(description = "GENERIC for foreign or temporary plates; defaults to the society format") NumberFormat numberFormat,
            @Schema(description = "Residents: send to the society office now (default true)") Boolean submitForApproval) {
    }

    /** All fields optional. Changing the number or type of an approved vehicle sends it back for approval. */
    public record UpdateVehicleRequest(
            @Size(max = 20) String registrationNumber,
            VehicleType vehicleType,
            @Size(max = 40) String make,
            @Size(max = 40) String model,
            @Size(max = 30) String color,
            Boolean active,
            NumberFormat numberFormat,
            @Size(max = 80) String ownerName) {
    }

    public record ReviewRequest(@Size(max = 200) String remarks) {
    }

    public record RegistrationView(RegistrationStatus status, Instant submittedAt, Instant reviewedAt, String reviewedBy,
                                   String remarks) {
    }

    public record VehicleResponse(
            UUID id, String registrationNumber, VehicleType vehicleType, String make, String model, String color,
            Category category, SocietyRef society, FlatRef flat, String ownerName, boolean active,
            RegistrationView registration, SlotRef assignedSlot, PermitRef activePermit, Instant createdAt,
            Instant updatedAt) {
    }
}
