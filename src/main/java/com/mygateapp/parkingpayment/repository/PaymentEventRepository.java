package com.mygateapp.parkingpayment.repository;

import com.mygateapp.parkingpayment.entity.ParkingPaymentEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<ParkingPaymentEvent, UUID> {

    List<ParkingPaymentEvent> findByPaymentIdOrderByOccurredAtAsc(UUID paymentId);
}
