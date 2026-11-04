package com.mygateapp.notifications.repository;

import com.mygateapp.notifications.entity.UserDevice;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceRepository extends JpaRepository<UserDevice, UUID> {

    Optional<UserDevice> findByToken(String token);

    List<UserDevice> findByUserIdAndActiveTrue(UUID userId);

    Optional<UserDevice> findByIdAndUserId(UUID id, UUID userId);

    Optional<UserDevice> findByTokenAndUserId(String token, UUID userId);

    @Modifying
    @Query("""
            UPDATE UserDevice d SET d.active = false, d.deactivatedReason = :reason, d.updatedAt = :now
            WHERE d.userId = :userId AND d.active = true
            """)
    int deactivateAllForUser(@Param("userId") UUID userId, @Param("reason") String reason, @Param("now") Instant now);
}
