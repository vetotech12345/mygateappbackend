package com.mygateapp.parking.service;

import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.parking.entity.SocietyParkingSettings;
import com.mygateapp.parking.repository.ParkingSettingsRepository;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentMethod;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Loads a society's parking settings (creating the defaults row on first use) and interprets its lists. */
@Component
public class ParkingSettingsStore {

    private final ParkingSettingsRepository repository;

    public ParkingSettingsStore(ParkingSettingsRepository repository) {
        this.repository = repository;
    }

    /**
     * The society's settings, or the defaults when no row exists yet. Never writes, so it is safe inside read-only
     * transactions; the row is created by {@link #settingsForUpdate}.
     */
    @Transactional(readOnly = true)
    public SocietyParkingSettings settingsFor(UUID societyId) {
        return repository.findById(societyId).orElseGet(() -> defaults(societyId));
    }

    /** Managed settings row for an update (created with defaults on first change). */
    @Transactional
    public SocietyParkingSettings settingsForUpdate(UUID societyId) {
        return repository.findById(societyId).orElseGet(() -> repository.saveAndFlush(defaults(societyId)));
    }

    private static SocietyParkingSettings defaults(UUID societyId) {
        SocietyParkingSettings settings = new SocietyParkingSettings();
        settings.setSocietyId(societyId);
        settings.setRules(new ArrayList<>(List.of("Park only in your allotted slot.",
                "Visitor vehicles need a valid visitor parking pass.")));
        return settings;
    }

    public static Set<VehicleType> allowedTypes(SocietyParkingSettings settings) {
        Set<VehicleType> types = EnumSet.noneOf(VehicleType.class);
        settings.getAllowedVehicleTypes().forEach(t -> types.add(VehicleType.valueOf(t)));
        return types.isEmpty() ? EnumSet.allOf(VehicleType.class) : types;
    }

    public static Set<PaymentMethod> acceptedMethods(SocietyParkingSettings settings) {
        Set<PaymentMethod> methods = EnumSet.noneOf(PaymentMethod.class);
        settings.getAcceptedPaymentMethods().forEach(m -> methods.add(PaymentMethod.valueOf(m)));
        return methods.isEmpty() ? EnumSet.copyOf(PaymentMethod.MANUAL) : methods;
    }

    public static void requireAllowedType(SocietyParkingSettings settings, VehicleType type, String field) {
        if (!allowedTypes(settings).contains(type)) {
            throw ApiException.field(ErrorCode.VEHICLE_TYPE_NOT_ALLOWED, field,
                    ErrorCode.VEHICLE_TYPE_NOT_ALLOWED.defaultMessage());
        }
    }
}
