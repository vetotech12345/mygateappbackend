package com.mygateapp.parkingpayment.repository;

import com.mygateapp.parkingpayment.entity.ParkingRatePlan;
import com.mygateapp.parkingpayment.enums.PaymentEnums.BillingUnit;
import com.mygateapp.parkingpayment.enums.PaymentEnums.RateAppliesTo;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RatePlanRepository extends JpaRepository<ParkingRatePlan, UUID> {

    @Query("""
            SELECT r FROM ParkingRatePlan r
            WHERE r.societyId = :societyId
              AND (:includeInactive = true OR r.active = true)
              AND (:unit IS NULL OR r.billingUnit = :unit)
              AND (:appliesTo IS NULL OR r.appliesTo = :appliesTo)
            ORDER BY r.appliesTo, r.billingUnit, r.name
            """)
    List<ParkingRatePlan> search(@Param("societyId") UUID societyId, @Param("includeInactive") boolean includeInactive,
                                 @Param("unit") BillingUnit unit, @Param("appliesTo") RateAppliesTo appliesTo);

    boolean existsBySocietyIdAndCodeIgnoreCase(UUID societyId, String code);
}
