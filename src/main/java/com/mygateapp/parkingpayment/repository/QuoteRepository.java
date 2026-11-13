package com.mygateapp.parkingpayment.repository;

import com.mygateapp.parkingpayment.entity.ParkingQuote;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuoteRepository extends JpaRepository<ParkingQuote, UUID> {

    java.util.Optional<ParkingQuote> findFirstByPermitIdOrderByCalculatedAtDesc(UUID permitId);

    /** Deletes old quotes that no payment references (estimates and superseded exit charges). */
    @Modifying
    @Query(value = """
            DELETE FROM parking_quotes q
            WHERE q.calculated_at < CAST(:before AS timestamptz)
              AND NOT EXISTS (SELECT 1 FROM parking_payments p WHERE p.quote_id = q.id)
            """, nativeQuery = true)
    int deleteUnusedBefore(@Param("before") Instant before);
}
