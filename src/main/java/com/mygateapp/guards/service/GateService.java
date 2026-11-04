package com.mygateapp.guards.service;

import com.mygateapp.audit.service.AuditService;
import com.mygateapp.common.ActiveStatus;
import com.mygateapp.common.dto.StatusRequest;
import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.guards.dto.GuardDtos;
import com.mygateapp.guards.entity.SocietyGate;
import com.mygateapp.guards.repository.GateRepository;
import com.mygateapp.security.AccessService;
import com.mygateapp.security.Permission;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GateService {

    private final GateRepository gates;
    private final AccessService access;
    private final AuditService audit;

    public GateService(GateRepository gates, AccessService access, AuditService audit) {
        this.gates = gates;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public GuardDtos.GateResponse create(GuardDtos.CreateGateRequest request) {
        UUID societyId = access.resolveSocietyId(request.societyId());
        access.require(societyId, Permission.GATE_MANAGE);
        String name = request.name().trim();
        if (gates.existsBySocietyIdAndNameIgnoreCase(societyId, name)) {
            throw ApiException.field(ErrorCode.GATE_NAME_TAKEN, "name", ErrorCode.GATE_NAME_TAKEN.defaultMessage());
        }
        SocietyGate gate = new SocietyGate();
        gate.setSocietyId(societyId);
        gate.setName(name);
        gate.setGateType(request.gateType() == null ? SocietyGate.GateType.MAIN : request.gateType());
        gates.save(gate);
        audit.record(societyId, "GATE_CREATED", "GATE", gate.getId(), "Gate " + name + " created", Map.of());
        return GuardDtos.GateResponse.of(gate);
    }

    @Transactional(readOnly = true)
    public List<GuardDtos.GateResponse> list(UUID societyIdParam) {
        UUID societyId = access.resolveSocietyId(societyIdParam);
        access.require(societyId, Permission.SOCIETY_VIEW);
        return gates.findBySocietyIdOrderByName(societyId).stream().map(GuardDtos.GateResponse::of).toList();
    }

    @Transactional
    public GuardDtos.GateResponse update(UUID id, GuardDtos.UpdateGateRequest request) {
        SocietyGate gate = requireGate(id);
        access.require(gate.getSocietyId(), Permission.GATE_MANAGE);
        String name = request.name().trim();
        if (!name.equalsIgnoreCase(gate.getName()) && gates.existsBySocietyIdAndNameIgnoreCase(gate.getSocietyId(), name)) {
            throw ApiException.field(ErrorCode.GATE_NAME_TAKEN, "name", ErrorCode.GATE_NAME_TAKEN.defaultMessage());
        }
        gate.setName(name);
        if (request.gateType() != null) {
            gate.setGateType(request.gateType());
        }
        audit.record(gate.getSocietyId(), "GATE_UPDATED", "GATE", id, "Gate " + name + " updated", Map.of());
        return GuardDtos.GateResponse.of(gate);
    }

    /** Guards assigned to an inactive gate can no longer record gate operations until reassigned. */
    @Transactional
    public GuardDtos.GateResponse updateStatus(UUID id, StatusRequest request) {
        SocietyGate gate = requireGate(id);
        access.require(gate.getSocietyId(), Permission.GATE_MANAGE);
        ActiveStatus previous = gate.getStatus();
        gate.setStatus(request.status());
        audit.record(gate.getSocietyId(), "GATE_STATUS_CHANGED", "GATE", id,
                "Gate " + gate.getName() + " " + previous + " -> " + request.status(), Map.of());
        return GuardDtos.GateResponse.of(gate);
    }

    public SocietyGate requireGate(UUID id) {
        SocietyGate gate = gates.findById(id).orElseThrow(() -> new ApiException(ErrorCode.GATE_NOT_FOUND));
        access.ensureInScope(gate.getSocietyId(), ErrorCode.GATE_NOT_FOUND);
        return gate;
    }
}
