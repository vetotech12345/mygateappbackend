package com.mygateapp.society.repository;

import com.mygateapp.society.entity.SocietySettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocietySettingsRepository extends JpaRepository<SocietySettings, UUID> {
}
