package com.mygateapp.flats.repository;

import com.mygateapp.flats.entity.Wing;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WingRepository extends JpaRepository<Wing, UUID> {

    List<Wing> findByTowerIdOrderByName(UUID towerId);

    boolean existsByTowerIdAndName(UUID towerId, String name);
}
