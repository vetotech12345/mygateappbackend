package com.mygateapp.guards.repository;

import com.mygateapp.guards.entity.GuardDutyLog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuardDutyLogRepository extends JpaRepository<GuardDutyLog, UUID> {

    Optional<GuardDutyLog> findByGuardIdAndEndedAtIsNull(UUID guardId);

    List<GuardDutyLog> findTop20ByGuardIdOrderByStartedAtDesc(UUID guardId);
}
