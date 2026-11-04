package com.mygateapp.society.repository;

import com.mygateapp.security.Role;
import com.mygateapp.society.entity.SocietyMembership;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocietyMembershipRepository extends JpaRepository<SocietyMembership, UUID> {

    Optional<SocietyMembership> findBySocietyIdAndUserIdAndRole(UUID societyId, UUID userId, Role role);

    List<SocietyMembership> findByUserIdOrderByCreatedAt(UUID userId);

    List<SocietyMembership> findBySocietyIdAndRoleAndStatus(UUID societyId, Role role, SocietyMembership.Status status);

    List<SocietyMembership> findBySocietyIdAndRole(UUID societyId, Role role);

    boolean existsByUserIdAndStatusIn(UUID userId, List<SocietyMembership.Status> statuses);

    long countBySocietyIdAndRoleAndStatus(UUID societyId, Role role, SocietyMembership.Status status);

    long countByRoleAndStatus(Role role, SocietyMembership.Status status);
}
