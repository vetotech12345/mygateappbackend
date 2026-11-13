package com.mygateapp.parking.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.parking.dto.ParkingDtos.Capacity;
import com.mygateapp.parking.dto.ParkingDtos.ParkingConfigResponse;
import com.mygateapp.parking.dto.ParkingDtos.ParkingSettingsRequest;
import com.mygateapp.parking.dto.ParkingDtos.ParkingSettingsResponse;
import com.mygateapp.parking.entity.ParkingSlot;
import com.mygateapp.parking.entity.SocietyParkingSettings;
import com.mygateapp.parking.enums.ParkingEnums.ParkingUsage;
import com.mygateapp.parking.repository.ParkingSlotRepository;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentMethod;
import com.mygateapp.parkingpayment.service.OnlinePaymentGateway;
import com.mygateapp.security.Permission;
import com.mygateapp.society.service.SocietyService;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Society parking settings (admins) and the filtered parking configuration (residents and guards). */
@Service
public class ParkingSettingsService {

    private final ParkingSettingsStore store;
    private final ParkingSlotRepository slots;
    private final ParkingInventoryService inventory;
    private final SocietyService societies;
    private final ParkingAccess access;
    private final OnlinePaymentGateway onlinePayments;
    private final AuditService audit;

    public ParkingSettingsService(ParkingSettingsStore store, ParkingSlotRepository slots, ParkingInventoryService inventory,
                                  SocietyService societies, ParkingAccess access, OnlinePaymentGateway onlinePayments,
                                  AuditService audit) {
        this.store = store;
        this.slots = slots;
        this.inventory = inventory;
        this.societies = societies;
        this.access = access;
        this.onlinePayments = onlinePayments;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ParkingSettingsResponse get(UUID societyId) {
        societies.requireVisible(societyId);
        access.core().require(societyId, Permission.SOCIETY_VIEW);
        return toResponse(store.settingsFor(societyId));
    }

    @Transactional
    public ParkingSettingsResponse update(UUID societyId, ParkingSettingsRequest request) {
        societies.requireVisible(societyId);
        access.core().require(societyId, Permission.PARKING_SETTINGS_MANAGE);
        SocietyParkingSettings s = store.settingsForUpdate(societyId);
        Map<String, Object> before = snapshot(s);
        s.setVisitorParkingEnabled(request.visitorParkingEnabled());
        s.setVisitorParkingAutoApprove(request.visitorParkingAutoApprove());
        s.setMaxVisitorParkingHours(request.maxVisitorParkingHours());
        s.setPaidParkingEnabled(request.paidParkingEnabled());
        s.setExternalPaidParkingAllowed(request.externalPaidParkingAllowed());
        s.setFreeGraceMinutes(request.freeGraceMinutes());
        s.setMaxVehiclesPerFlat(request.maxVehiclesPerFlat());
        s.setMaxTemporaryPermitDays(request.maxTemporaryPermitDays());
        s.setResidentEntryRequiresPermit(request.residentEntryRequiresPermit());
        s.setPaymentRequiredBeforeExit(request.paymentRequiredBeforeExit());
        s.setEmergencyOverrideAllowed(request.emergencyOverrideAllowed());
        s.setVehicleNumberFormat(request.vehicleNumberFormat());
        s.setAllowedVehicleTypes(request.allowedVehicleTypes() == null ? List.of()
                : request.allowedVehicleTypes().stream().filter(Objects::nonNull).sorted().map(Enum::name).toList());
        if (request.acceptedPaymentMethods() != null) {
            if (request.acceptedPaymentMethods().stream().anyMatch(m -> !PaymentMethod.MANUAL.contains(m))) {
                throw com.mygateapp.exception.ApiException.field(ErrorCode.PAYMENT_METHOD_NOT_ACCEPTED,
                        "acceptedPaymentMethods", "Only manual methods can be accepted: " + PaymentMethod.MANUAL);
            }
            s.setAcceptedPaymentMethods(request.acceptedPaymentMethods().stream().sorted().map(Enum::name).toList());
        }
        if (request.currency() != null) {
            s.setCurrency(request.currency());
        }
        if (request.rules() != null) {
            s.setRules(request.rules().stream().map(String::trim).filter(r -> !r.isEmpty()).distinct().toList());
        }
        s.setUpdatedBy(access.currentUserId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("before", before);
        details.put("after", snapshot(s));
        audit.record(societyId, "PARKING_SETTINGS_UPDATED", "SOCIETY", societyId, "Parking settings updated", details);
        return toResponse(s);
    }

    /** What residents and guards need: switches, limits, rules and areas with live counts. */
    @Transactional(readOnly = true)
    public ParkingConfigResponse config() {
        UUID societyId = access.requireViewer();
        SocietyParkingSettings s = store.settingsFor(societyId);
        return new ParkingConfigResponse(s.isVisitorParkingEnabled(), s.isPaidParkingEnabled(), onlinePayments.isAvailable(),
                s.isVisitorParkingAutoApprove(), s.getMaxVisitorParkingHours(), s.getMaxVehiclesPerFlat(),
                s.getMaxTemporaryPermitDays(), s.getVehicleNumberFormat(),
                ParkingSettingsStore.allowedTypes(s).stream().sorted().toList(),
                ParkingSettingsStore.acceptedMethods(s).stream().sorted().toList(), s.getCurrency(), s.getRules(),
                inventory.areaResponses(societyId, false));
    }

    private ParkingSettingsResponse toResponse(SocietyParkingSettings s) {
        List<ParkingSlot> all = slots.findBySocietyId(s.getSocietyId());
        Capacity capacity = new Capacity(all.size(), count(all, ParkingUsage.RESIDENT), count(all, ParkingUsage.VISITOR),
                count(all, ParkingUsage.PAID), count(all, ParkingUsage.RESERVED) + count(all, ParkingUsage.SOCIETY)
                + count(all, ParkingUsage.STAFF) + count(all, ParkingUsage.SERVICE) + count(all, ParkingUsage.EMERGENCY),
                (int) all.stream().filter(slot -> !slot.isInService()).count());
        List<VehicleType> types = s.getAllowedVehicleTypes().stream().map(VehicleType::valueOf).toList();
        return new ParkingSettingsResponse(s.getSocietyId(), s.isVisitorParkingEnabled(), s.isVisitorParkingAutoApprove(),
                s.getMaxVisitorParkingHours(), s.isPaidParkingEnabled(), s.isExternalPaidParkingAllowed(),
                s.getFreeGraceMinutes(), s.getMaxVehiclesPerFlat(), s.getMaxTemporaryPermitDays(),
                s.isResidentEntryRequiresPermit(), s.isPaymentRequiredBeforeExit(), s.isEmergencyOverrideAllowed(),
                s.getVehicleNumberFormat(), types, ParkingSettingsStore.acceptedMethods(s).stream().sorted().toList(),
                s.getCurrency(), s.getRules(), onlinePayments.isAvailable(), capacity, s.getUpdatedAt());
    }

    private static int count(List<ParkingSlot> slots, ParkingUsage usage) {
        return (int) slots.stream().filter(s -> s.getUsage() == usage && s.isInService()).count();
    }

    private static Map<String, Object> snapshot(SocietyParkingSettings s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("visitorParkingEnabled", s.isVisitorParkingEnabled());
        map.put("visitorParkingAutoApprove", s.isVisitorParkingAutoApprove());
        map.put("maxVisitorParkingHours", s.getMaxVisitorParkingHours());
        map.put("paidParkingEnabled", s.isPaidParkingEnabled());
        map.put("externalPaidParkingAllowed", s.isExternalPaidParkingAllowed());
        map.put("freeGraceMinutes", s.getFreeGraceMinutes());
        map.put("maxVehiclesPerFlat", s.getMaxVehiclesPerFlat());
        map.put("residentEntryRequiresPermit", s.isResidentEntryRequiresPermit());
        map.put("paymentRequiredBeforeExit", s.isPaymentRequiredBeforeExit());
        map.put("emergencyOverrideAllowed", s.isEmergencyOverrideAllowed());
        map.put("vehicleNumberFormat", s.getVehicleNumberFormat().name());
        return map;
    }
}
