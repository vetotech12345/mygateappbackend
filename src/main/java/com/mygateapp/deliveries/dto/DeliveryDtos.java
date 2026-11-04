package com.mygateapp.deliveries.dto;

import com.mygateapp.common.dto.Refs;
import com.mygateapp.common.gate.MovementStatus;
import com.mygateapp.deliveries.entity.Delivery;
import com.mygateapp.deliveries.enums.DeliveryCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DeliveryDtos {

    private DeliveryDtos() {
    }

    @Schema(description = "Guard registers a delivery agent. Whether resident approval is needed comes from the society's "
            + "category rules (see /config/lookups).")
    public record CreateDeliveryRequest(
            @NotNull(message = "Category is required") DeliveryCategory category,
            @Schema(example = "QuickBite") @NotBlank(message = "Company is required") @Size(max = 60) String company,
            @Schema(example = "Arjun") @NotBlank(message = "Agent name is required") @Size(min = 2, max = 60) String agentName,
            String agentMobile,
            @NotNull(message = "Select a destination flat") UUID flatId,
            @Min(1) @Max(20) Integer packageCount,
            @Size(max = 200, message = "Notes must be under 200 characters") String notes) {
    }

    /** Matches the Flutter {@code DeliveryDto} plus the precise approval / movement fields. */
    public record DeliveryResponse(
            UUID id,
            DeliveryCategory category,
            String company,
            String agentName,
            String agentMobile,
            Refs.FlatRef flat,
            Refs.GateRef gate,
            Delivery.DisplayStatus status,
            Delivery.ApprovalStatus approvalStatus,
            MovementStatus movementStatus,
            Instant requestedAt,
            boolean requiresApproval,
            Instant decidedAt,
            String decidedBy,
            Instant entryAt,
            Instant exitAt,
            int packageCount,
            String notes,
            List<Refs.TimelineEvent> history) {
    }
}
