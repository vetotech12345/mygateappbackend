package com.mygateapp.visitors.repository;

import com.mygateapp.visitors.entity.VisitorEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitorEventRepository extends JpaRepository<VisitorEvent, UUID> {

    List<VisitorEvent> findByVisitorIdOrderByOccurredAtAsc(UUID visitorId);
}
