package com.mygateapp.parkingpayment.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.common.Strings;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.parking.dto.ParkingRefs.Money;
import com.mygateapp.parking.entity.SocietyParkingSettings;
import com.mygateapp.parking.service.ParkingAccess;
import com.mygateapp.parking.service.ParkingInventoryService;
import com.mygateapp.parking.service.ParkingRefLoader;
import com.mygateapp.parking.service.ParkingSettingsStore;
import com.mygateapp.parkingpayment.dto.PaymentDtos.CreateRateRequest;
import com.mygateapp.parkingpayment.dto.PaymentDtos.RateResponse;
import com.mygateapp.parkingpayment.dto.PaymentDtos.UpdateRateRequest;
import com.mygateapp.parkingpayment.entity.ParkingRatePlan;
import com.mygateapp.parkingpayment.enums.PaymentEnums.BillingUnit;
import com.mygateapp.parkingpayment.enums.PaymentEnums.RateAppliesTo;
import com.mygateapp.parkingpayment.repository.RatePlanRepository;
import com.mygateapp.security.Permission;
import com.mygateapp.vehicle.enums.VehicleEnums.VehicleType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Society rate plans. Sessions keep a JSON snapshot of the plan they were started with. */
@Service
public class RatePlanService {

    private final RatePlanRepository rates;
    private final ParkingInventoryService inventory;
    private final ParkingSettingsStore settingsStore;
    private final ParkingAccess access;
    private final ParkingRefLoader refLoader;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RatePlanService(RatePlanRepository rates, ParkingInventoryService inventory, ParkingSettingsStore settingsStore,
                           ParkingAccess access, ParkingRefLoader refLoader, AuditService audit, ObjectMapper objectMapper,
                           Clock clock) {
        this.rates = rates;
        this.inventory = inventory;
        this.settingsStore = settingsStore;
        this.access = access;
        this.refLoader = refLoader;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RateResponse> list(VehicleType vehicleType, BillingUnit unit, RateAppliesTo appliesTo,
                                   boolean includeInactive) {
        UUID societyId = access.requireViewer();
        boolean admin = access.core().hasPermission(societyId, Permission.PARKING_RATE_MANAGE);
        Instant now = clock.instant();
        return rates.search(societyId, includeInactive && admin, unit, appliesTo).stream()
                .filter(r -> includeInactive && admin || r.isEffectiveAt(now))
                .filter(r -> vehicleType == null || vehicleType.fits(r.getVehicleType()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RateResponse get(UUID id) {
        UUID societyId = access.requireViewer();
        return toResponse(require(id, societyId));
    }

    @Transactional
    public RateResponse create(CreateRateRequest request) {
        UUID societyId = access.requireAdmin(Permission.PARKING_RATE_MANAGE);
        SocietyParkingSettings settings = settingsStore.settingsFor(societyId);
        String code = request.code().trim().toUpperCase();
        if (rates.existsBySocietyIdAndCodeIgnoreCase(societyId, code)) {
            throw ApiException.field(ErrorCode.RATE_CODE_TAKEN, "code", ErrorCode.RATE_CODE_TAKEN.defaultMessage());
        }
        if (request.areaId() != null) {
            inventory.requireArea(request.areaId(), societyId);
        }
        ParkingRatePlan plan = new ParkingRatePlan();
        plan.setSocietyId(societyId);
        plan.setCode(code);
        plan.setName(request.name().trim());
        plan.setDescription(Strings.trimToNull(request.description()));
        plan.setAreaId(request.areaId());
        plan.setVehicleType(request.vehicleType());
        plan.setAppliesTo(request.appliesTo());
        plan.setBillingUnit(request.billingUnit());
        plan.setBaseRate(PricingCalculator.money(request.amount()));
        plan.setCurrency(settings.getCurrency());
        plan.setFreeMinutes(request.freeMinutes() == null ? 0 : request.freeMinutes());
        plan.setGracePeriodMinutes(request.gracePeriodMinutes() == null ? 0 : request.gracePeriodMinutes());
        plan.setDailyCap(request.maxPerDay() == null ? null : PricingCalculator.money(request.maxPerDay()));
        plan.setOverstayHourlyRate(request.overstayHourlyRate() == null ? null : PricingCalculator.money(request.overstayHourlyRate()));
        plan.setTaxPercent(request.taxPercent() == null ? BigDecimal.ZERO.setScale(2) : request.taxPercent().setScale(2));
        plan.setTaxLabel(Strings.trimToNull(request.taxLabel()));
        plan.setEffectiveFrom(request.effectiveFrom() == null ? clock.instant() : request.effectiveFrom());
        plan.setEffectiveUntil(request.effectiveUntil());
        validate(plan);
        plan.setCreatedBy(access.currentUserId());
        try {
            rates.saveAndFlush(plan);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.field(ErrorCode.RATE_CODE_TAKEN, "code", ErrorCode.RATE_CODE_TAKEN.defaultMessage());
        }
        audit.record(societyId, "PARKING_RATE_CREATED", "PARKING_RATE", plan.getId(), "Rate " + code + " created",
                snapshotDetails(plan));
        return toResponse(plan);
    }

    /** Pricing changes apply to sessions that start after the change; running sessions keep their snapshot. */
    @Transactional
    public RateResponse update(UUID id, UpdateRateRequest request) {
        UUID societyId = access.requireAdmin(Permission.PARKING_RATE_MANAGE);
        ParkingRatePlan plan = require(id, societyId);
        Map<String, Object> before = snapshotDetails(plan);
        if (request.name() != null) {
            plan.setName(request.name().trim());
        }
        if (request.description() != null) {
            plan.setDescription(Strings.trimToNull(request.description()));
        }
        if (request.amount() != null) {
            plan.setBaseRate(PricingCalculator.money(request.amount()));
        }
        if (request.freeMinutes() != null) {
            plan.setFreeMinutes(request.freeMinutes());
        }
        if (request.gracePeriodMinutes() != null) {
            plan.setGracePeriodMinutes(request.gracePeriodMinutes());
        }
        if (Boolean.TRUE.equals(request.clearMaxPerDay())) {
            plan.setDailyCap(null);
        } else if (request.maxPerDay() != null) {
            plan.setDailyCap(PricingCalculator.money(request.maxPerDay()));
        }
        if (request.overstayHourlyRate() != null) {
            plan.setOverstayHourlyRate(PricingCalculator.money(request.overstayHourlyRate()));
        }
        if (request.taxPercent() != null) {
            plan.setTaxPercent(request.taxPercent().setScale(2));
        }
        if (request.taxLabel() != null) {
            plan.setTaxLabel(Strings.trimToNull(request.taxLabel()));
        }
        if (request.effectiveUntil() != null) {
            plan.setEffectiveUntil(request.effectiveUntil());
        }
        if (request.active() != null) {
            plan.setActive(request.active());
        }
        validate(plan);
        rates.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("before", before);
        details.put("after", snapshotDetails(plan));
        audit.record(societyId, "PARKING_RATE_UPDATED", "PARKING_RATE", id, "Rate " + plan.getCode() + " updated", details);
        return toResponse(plan);
    }

    // =============================================================== internal

    public ParkingRatePlan require(UUID id, UUID societyId) {
        ParkingRatePlan plan = rates.findById(id).orElseThrow(() -> new ApiException(ErrorCode.RATE_NOT_FOUND));
        access.ensureSameSociety(plan.getSocietyId(), societyId, ErrorCode.RATE_NOT_FOUND);
        return plan;
    }

    /** The plan exists in the society, is effective now, fits the vehicle and the audience. */
    public ParkingRatePlan requireApplicable(UUID id, UUID societyId, VehicleType vehicleType, RateAppliesTo audience,
                                             BillingUnit requiredUnit) {
        ParkingRatePlan plan = require(id, societyId);
        if (!plan.isEffectiveAt(clock.instant())) {
            throw new ApiException(ErrorCode.RATE_NOT_APPLICABLE, "This rate is not active.");
        }
        if (vehicleType != null && !vehicleType.fits(plan.getVehicleType())) {
            throw new ApiException(ErrorCode.RATE_NOT_APPLICABLE, "This rate is for a different vehicle type.");
        }
        if (audience != null && plan.getAppliesTo() != RateAppliesTo.ALL && plan.getAppliesTo() != audience) {
            throw new ApiException(ErrorCode.RATE_NOT_APPLICABLE, "This rate is for " + plan.getAppliesTo().name().toLowerCase() + " parking.");
        }
        if (requiredUnit != null && plan.getBillingUnit() != requiredUnit) {
            throw new ApiException(ErrorCode.RATE_NOT_APPLICABLE, "Choose a " + requiredUnit.name().toLowerCase() + " rate.");
        }
        return plan;
    }

    /** Effective rates for an audience and vehicle type (offered at the gate). */
    public List<ParkingRatePlan> applicable(UUID societyId, VehicleType vehicleType, RateAppliesTo audience) {
        Instant now = clock.instant();
        return rates.search(societyId, false, null, null).stream()
                .filter(r -> r.isEffectiveAt(now) && r.getBillingUnit() != BillingUnit.MONTHLY)
                .filter(r -> vehicleType == null || vehicleType.fits(r.getVehicleType()))
                .filter(r -> r.getAppliesTo() == RateAppliesTo.ALL || r.getAppliesTo() == audience)
                .toList();
    }

    public RateResponse toResponse(ParkingRatePlan r) {
        return new RateResponse(r.getId(), r.getCode(), r.getName(), r.getDescription(), r.getBillingUnit(),
                r.getVehicleType(), r.getAppliesTo(), Money.of(r.getBaseRate(), r.getCurrency()), r.getFreeMinutes(),
                r.getGracePeriodMinutes(), Money.of(r.getDailyCap(), r.getCurrency()),
                Money.of(r.getOverstayHourlyRate(), r.getCurrency()), r.getTaxPercent().toPlainString(), r.getTaxLabel(),
                r.getAreaId() == null ? null : refLoader.refs().areaRefOf(r.getAreaId()), r.getEffectiveFrom(),
                r.getEffectiveUntil(), r.isActive());
    }

    /** JSON snapshot stored on a session at entry. */
    public String snapshot(ParkingRatePlan plan) {
        return objectMapper.writeValueAsString(toResponse(plan));
    }

    public RateResponse fromSnapshot(String json) {
        return json == null ? null : objectMapper.readValue(json, RateResponse.class);
    }

    public static PricingCalculator.Terms terms(RateResponse rate) {
        return new PricingCalculator.Terms(rate.billingUnit(), new BigDecimal(rate.amount().amount()),
                rate.amount().currency(), rate.freeMinutes(), rate.gracePeriodMinutes(),
                rate.maxPerDay() == null ? null : new BigDecimal(rate.maxPerDay().amount()),
                rate.overstayHourlyRate() == null ? null : new BigDecimal(rate.overstayHourlyRate().amount()),
                rate.taxPercent() == null ? BigDecimal.ZERO : new BigDecimal(rate.taxPercent()), rate.taxLabel());
    }

    private static void validate(ParkingRatePlan plan) {
        if (plan.getEffectiveUntil() != null && !plan.getEffectiveUntil().isAfter(plan.getEffectiveFrom())) {
            throw ApiException.field("effectiveUntil", "The end must be after the start.");
        }
        if (plan.getDailyCap() != null && plan.getBillingUnit() != BillingUnit.HOURLY) {
            throw ApiException.field("maxPerDay", "A daily cap applies to hourly rates only.");
        }
    }

    private static Map<String, Object> snapshotDetails(ParkingRatePlan r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("amount", r.getBaseRate().toPlainString());
        map.put("unit", r.getBillingUnit().name());
        map.put("freeMinutes", r.getFreeMinutes());
        map.put("graceMinutes", r.getGracePeriodMinutes());
        map.put("dailyCap", r.getDailyCap() == null ? null : r.getDailyCap().toPlainString());
        map.put("taxPercent", r.getTaxPercent().toPlainString());
        map.put("active", r.isActive());
        return map;
    }
}
