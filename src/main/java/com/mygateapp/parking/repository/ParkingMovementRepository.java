package com.mygateapp.parking.repository;

import com.mygateapp.parking.entity.ParkingMovement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingMovementRepository extends JpaRepository<ParkingMovement, UUID> {

    List<ParkingMovement> findBySessionIdOrderByOccurredAtAsc(UUID sessionId);
}
