package com.mygateapp.common.dto;

import com.mygateapp.common.ActiveStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of the {@code PATCH .../status} endpoints for towers, flats, gates, guards and domestic staff. */
public record StatusRequest(@NotNull(message = "status is required") ActiveStatus status,
                            @Size(max = 200) String reason) {
}
