package com.mygateapp.guards.repository;

import com.mygateapp.guards.entity.GuardAssignment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuardAssignmentRepository extends JpaRepository<GuardAssignment, UUID> {

    Optional<GuardAssignment> findByGuardIdAndActiveTrue(UUID guardId);

    List<GuardAssignment> findByGuardIdInAndActiveTrue(Collection<UUID> guardIds);

    List<GuardAssignment> findByGuardIdOrderByValidFromDesc(UUID guardId);
}
