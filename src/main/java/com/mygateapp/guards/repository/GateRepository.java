package com.mygateapp.guards.repository;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.guards.entity.SocietyGate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GateRepository extends JpaRepository<SocietyGate, UUID> {

    List<SocietyGate> findBySocietyIdOrderByName(UUID societyId);

    boolean existsBySocietyIdAndNameIgnoreCase(UUID societyId, String name);

    long countBySocietyIdAndStatus(UUID societyId, ActiveStatus status);
}
