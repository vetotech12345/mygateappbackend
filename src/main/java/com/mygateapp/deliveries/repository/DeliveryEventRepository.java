package com.mygateapp.deliveries.repository;

import com.mygateapp.deliveries.entity.DeliveryEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryEventRepository extends JpaRepository<DeliveryEvent, UUID> {

    List<DeliveryEvent> findByDeliveryIdOrderByOccurredAtAsc(UUID deliveryId);
}
