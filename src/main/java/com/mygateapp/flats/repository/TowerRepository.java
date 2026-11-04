package com.mygateapp.flats.repository;

import com.mygateapp.flats.entity.Tower;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TowerRepository extends JpaRepository<Tower, UUID> {

    List<Tower> findBySocietyIdOrderByCode(UUID societyId);

    boolean existsBySocietyIdAndCode(UUID societyId, String code);

    long countBySocietyId(UUID societyId);
}
