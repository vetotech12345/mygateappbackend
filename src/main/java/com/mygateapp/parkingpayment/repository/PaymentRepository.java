package com.mygateapp.parkingpayment.repository;

import com.mygateapp.parkingpayment.entity.ParkingPayment;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentPurpose;
import com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<ParkingPayment, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ParkingPayment p WHERE p.id = :id")
    Optional<ParkingPayment> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT p FROM ParkingPayment p
            WHERE p.societyId = :societyId
              AND (:restrict = false OR p.flatId IN :flatIds)
              AND (:status IS NULL OR p.status = :status)
              AND (:purpose IS NULL OR p.purpose = :purpose)
              AND (:sessionId IS NULL OR p.sessionId = :sessionId)
              AND (:permitId IS NULL OR p.permitId = :permitId)
              AND p.createdAt >= :from AND p.createdAt < :to
            """)
    Page<ParkingPayment> search(@Param("societyId") UUID societyId, @Param("restrict") boolean restrict,
                                @Param("flatIds") Collection<UUID> flatIds, @Param("status") PaymentStatus status,
                                @Param("purpose") PaymentPurpose purpose, @Param("sessionId") UUID sessionId,
                                @Param("permitId") UUID permitId, @Param("from") Instant from,
                                @Param("to") Instant to, Pageable pageable);

    List<ParkingPayment> findBySessionIdAndStatus(UUID sessionId, PaymentStatus status);

    List<ParkingPayment> findBySessionIdOrderByCreatedAtDesc(UUID sessionId);

    boolean existsBySessionIdAndStatus(UUID sessionId, PaymentStatus status);

    List<ParkingPayment> findByPermitIdAndStatus(UUID permitId, PaymentStatus status);

    @Query("""
            SELECT p FROM ParkingPayment p
            WHERE p.flatId IN :flatIds AND p.status = com.mygateapp.parkingpayment.enums.PaymentEnums.PaymentStatus.PENDING
            """)
    List<ParkingPayment> findPendingForFlats(@Param("flatIds") Collection<UUID> flatIds);
}
