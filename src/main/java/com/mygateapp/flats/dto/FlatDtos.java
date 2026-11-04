package com.mygateapp.flats.dto;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.flats.entity.Flat;
import com.mygateapp.flats.entity.Tower;
import com.mygateapp.flats.entity.Wing;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class FlatDtos {

    private FlatDtos() {
    }

    public record CreateTowerRequest(
            @Schema(description = "Defaults to the caller's society") UUID societyId,
            @Schema(example = "A") @NotBlank(message = "Tower code is required")
            @Pattern(regexp = "^[A-Za-z0-9-]{1,10}$", message = "Code must be 1-10 letters, digits or hyphens") String code,
            @Schema(example = "Tower A") @NotBlank(message = "Tower name is required") @Size(max = 60) String name,
            @Min(0) @Max(200) Integer floorCount) {
    }

    public record UpdateTowerRequest(
            @NotBlank(message = "Tower code is required")
            @Pattern(regexp = "^[A-Za-z0-9-]{1,10}$", message = "Code must be 1-10 letters, digits or hyphens") String code,
            @NotBlank(message = "Tower name is required") @Size(max = 60) String name,
            @Min(0) @Max(200) Integer floorCount,
            ActiveStatus status) {
    }

    public record TowerResponse(UUID id, UUID societyId, String code, String name, Integer floorCount,
                                ActiveStatus status, List<WingResponse> wings, Instant createdAt) {

        public static TowerResponse of(Tower tower, List<Wing> wings) {
            return new TowerResponse(tower.getId(), tower.getSocietyId(), tower.getCode(), tower.getName(),
                    tower.getFloorCount(), tower.getStatus(), wings.stream().map(WingResponse::of).toList(),
                    tower.getCreatedAt());
        }
    }

    public record CreateWingRequest(@NotBlank(message = "Wing name is required") @Size(max = 40) String name) {
    }

    public record WingResponse(UUID id, UUID towerId, String name) {

        public static WingResponse of(Wing wing) {
            return new WingResponse(wing.getId(), wing.getTowerId(), wing.getName());
        }
    }

    public record CreateFlatRequest(
            @NotNull(message = "towerId is required") UUID towerId,
            UUID wingId,
            @NotNull(message = "Floor is required") @Min(-5) @Max(200) Integer floorNumber,
            @Schema(example = "1204") @NotBlank(message = "Flat number is required")
            @Pattern(regexp = "^[A-Za-z0-9/-]{1,20}$", message = "Flat number may contain letters, digits, / and -") String flatNumber,
            @Size(max = 20) String flatType,
            @Positive Integer areaSqft) {
    }

    public record UpdateFlatRequest(
            UUID wingId,
            @NotNull(message = "Floor is required") @Min(-5) @Max(200) Integer floorNumber,
            @NotBlank(message = "Flat number is required")
            @Pattern(regexp = "^[A-Za-z0-9/-]{1,20}$", message = "Flat number may contain letters, digits, / and -") String flatNumber,
            @Size(max = 20) String flatType,
            @Positive Integer areaSqft) {
    }

    @Schema(description = "Creates flats floor by floor: floor 12, unit 4 -> \"1204\"; ground floor -> \"G04\"; basement 1 -> \"B104\". "
            + "Existing numbers are skipped.")
    public record BulkCreateFlatsRequest(
            @NotNull(message = "towerId is required") UUID towerId,
            UUID wingId,
            @NotNull @Min(-5) @Max(200) Integer fromFloor,
            @NotNull @Min(-5) @Max(200) Integer toFloor,
            @NotNull @Min(1) @Max(50) Integer unitsPerFloor,
            @Size(max = 20) String flatType) {
    }

    public record BulkCreateFlatsResponse(int created, int skipped, List<String> skippedNumbers) {
    }

    public record OccupantView(UUID membershipId, UUID userId, String name, String occupancy, String status, boolean primary) {
    }

    public record FlatResponse(
            UUID id, UUID societyId, UUID towerId, String towerCode, String towerName, UUID wingId, String wingName,
            int floorNumber, String flatNumber, String label, String flatType, Integer areaSqft, ActiveStatus status,
            List<OccupantView> occupants, Instant createdAt) {

        public static FlatResponse of(Flat flat, List<OccupantView> occupants) {
            return new FlatResponse(flat.getId(), flat.getSocietyId(), flat.getTower().getId(), flat.getTower().getCode(),
                    flat.getTower().getName(), flat.getWing() == null ? null : flat.getWing().getId(),
                    flat.getWing() == null ? null : flat.getWing().getName(), flat.getFloorNumber(), flat.getFlatNumber(),
                    flat.toRef().label(), flat.getFlatType(), flat.getAreaSqft(), flat.getStatus(), occupants,
                    flat.getCreatedAt());
        }
    }

    public record OccupancyRecord(UUID membershipId, UUID userId, String name, String occupancy, String status,
                                  LocalDate startDate, LocalDate endDate, String endReason, Instant createdAt) {
    }
}
