package com.mygateapp.parking.repository;

import com.mygateapp.parking.entity.SocietyParkingSettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingSettingsRepository extends JpaRepository<SocietyParkingSettings, UUID> {
}
