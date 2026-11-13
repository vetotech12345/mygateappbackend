package com.mygateapp.parking.dto;

import com.mygateapp.parking.enums.ParkingEnums.PermitStatus;
import com.mygateapp.parking.enums.ParkingEnums.PermitType;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Small fragments embedded in vehicle, parking and payment responses (Flutter "shared fragments"). */
public final class ParkingRefs {

    private ParkingRefs() {
    }

    public record AreaRef(UUID id, String name, String level) {
    }

    /** {@code vehicleTypes} = the vehicle types the slot accepts. */
    public record SlotRef(UUID id, String number, AreaRef area, List<VehicleType> vehicleTypes) {
    }

    public record VehicleRef(UUID id, String registrationNumber, VehicleType vehicleType, String make, String model,
                             String color) {
    }

    public record PermitRef(UUID id, String permitNumber, PermitType type, PermitStatus status, Instant validUntil) {
    }

    /** Money on the wire: a decimal string with two places and an ISO 4217 currency, never a float. */
    @Schema(description = "Monetary amount", example = "{\"amount\":\"120.00\",\"currency\":\"INR\"}")
    public record Money(@Schema(example = "120.00") String amount, @Schema(example = "INR") String currency) {

        public static Money of(BigDecimal amount, String currency) {
            return amount == null ? null : new Money(scale(amount).toPlainString(), currency);
        }

        public static BigDecimal scale(BigDecimal amount) {
            return amount.setScale(2, RoundingMode.HALF_UP);
        }
    }
}
