package com.mygateapp.parkingpayment.repository;

import com.mygateapp.parkingpayment.entity.ParkingReceipt;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptRepository extends JpaRepository<ParkingReceipt, UUID> {

    Optional<ParkingReceipt> findByPaymentId(UUID paymentId);

    List<ParkingReceipt> findByPaymentIdIn(Collection<UUID> paymentIds);
}
