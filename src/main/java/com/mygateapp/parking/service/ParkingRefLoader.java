package com.mygateapp.parking.service;

import com.mygateapp.common.dto.Refs.FlatRef;
import com.mygateapp.common.dto.Refs.GateRef;
import com.mygateapp.guards.entity.SocietyGate;
import com.mygateapp.guards.repository.GateRepository;
import com.mygateapp.parking.dto.ParkingRefs.AreaRef;
import com.mygateapp.parking.dto.ParkingRefs.PermitRef;
import com.mygateapp.parking.dto.ParkingRefs.SlotRef;
import com.mygateapp.parking.dto.ParkingRefs.VehicleRef;
import com.mygateapp.parking.entity.ParkingArea;
import com.mygateapp.parking.entity.ParkingPermit;
import com.mygateapp.parking.entity.ParkingSlot;
import com.mygateapp.parking.repository.ParkingAreaRepository;
import com.mygateapp.parking.repository.ParkingPermitRepository;
import com.mygateapp.parking.repository.ParkingSlotRepository;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.repository.UserRepository;
import com.mygateapp.vehicle.entity.Vehicle;
import com.mygateapp.vehicle.repository.VehicleRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Builds the small reference fragments (flat, slot, area, vehicle, permit, gate, user name) for responses. A
 * {@link Refs} instance caches per mapping call; {@code preload*} loads a whole page in one query per type.
 */
@Component
public class ParkingRefLoader {

    private final NamedParameterJdbcTemplate jdbc;
    private final ParkingSlotRepository slots;
    private final ParkingAreaRepository areas;
    private final VehicleRepository vehicles;
    private final ParkingPermitRepository permits;
    private final GateRepository gates;
    private final UserRepository users;

    public ParkingRefLoader(NamedParameterJdbcTemplate jdbc, ParkingSlotRepository slots, ParkingAreaRepository areas,
                            VehicleRepository vehicles, ParkingPermitRepository permits, GateRepository gates,
                            UserRepository users) {
        this.jdbc = jdbc;
        this.slots = slots;
        this.areas = areas;
        this.vehicles = vehicles;
        this.permits = permits;
        this.gates = gates;
        this.users = users;
    }

    public Refs refs() {
        return new Refs();
    }

    public static SlotRef slotRef(ParkingSlot slot, ParkingArea area) {
        return new SlotRef(slot.getId(), slot.getSlotNumber(), areaRef(area),
                slot.getVehicleType().accepts().stream().sorted().toList());
    }

    public static AreaRef areaRef(ParkingArea area) {
        return area == null ? null : new AreaRef(area.getId(), area.getName(), area.getLevel());
    }

    public static VehicleRef vehicleRef(Vehicle v) {
        return v == null ? null : new VehicleRef(v.getId(), v.getRegistrationNumber(), v.getVehicleType(), v.getMake(),
                v.getModel(), v.getColor());
    }

    public static PermitRef permitRef(ParkingPermit p) {
        return p == null ? null : new PermitRef(p.getId(), p.getPermitNumber(), p.getPermitType(), p.getStatus(),
                p.getValidUntil());
    }

    /** Per-call cache. Getters load missing ids one at a time; use preload for lists. */
    public final class Refs {

        private final Map<UUID, FlatRef> flatRefs = new HashMap<>();
        private final Map<UUID, ParkingSlot> slotById = new HashMap<>();
        private final Map<UUID, ParkingArea> areaById = new HashMap<>();
        private final Map<UUID, Vehicle> vehicleById = new HashMap<>();
        private final Map<UUID, ParkingPermit> permitById = new HashMap<>();
        private final Map<UUID, SocietyGate> gateById = new HashMap<>();
        private final Map<UUID, String> userNames = new HashMap<>();

        public Refs preloadFlats(Collection<UUID> ids) {
            Set<UUID> missing = missing(ids, flatRefs);
            if (!missing.isEmpty()) {
                jdbc.query("""
                        SELECT f.id, t.code, w.name, f.flat_number
                        FROM flats f JOIN towers t ON t.id = f.tower_id LEFT JOIN wings w ON w.id = f.wing_id
                        WHERE f.id IN (:ids)
                        """, new MapSqlParameterSource("ids", missing), rs -> {
                    UUID id = rs.getObject(1, UUID.class);
                    flatRefs.put(id, FlatRef.of(id, rs.getString(2), rs.getString(3), rs.getString(4)));
                });
            }
            return this;
        }

        public Refs preloadSlots(Collection<UUID> ids) {
            Set<UUID> missing = missing(ids, slotById);
            if (!missing.isEmpty()) {
                slots.findAllById(missing).forEach(s -> slotById.put(s.getId(), s));
                preloadAreas(slotById.values().stream().map(ParkingSlot::getAreaId).toList());
            }
            return this;
        }

        public Refs preloadAreas(Collection<UUID> ids) {
            Set<UUID> missing = missing(ids, areaById);
            if (!missing.isEmpty()) {
                areas.findAllById(missing).forEach(a -> areaById.put(a.getId(), a));
            }
            return this;
        }

        public Refs preloadVehicles(Collection<UUID> ids) {
            Set<UUID> missing = missing(ids, vehicleById);
            if (!missing.isEmpty()) {
                vehicles.findAllById(missing).forEach(v -> vehicleById.put(v.getId(), v));
            }
            return this;
        }

        public Refs preloadPermits(Collection<UUID> ids) {
            Set<UUID> missing = missing(ids, permitById);
            if (!missing.isEmpty()) {
                permits.findAllById(missing).forEach(p -> permitById.put(p.getId(), p));
            }
            return this;
        }

        public Refs preloadGates(Collection<UUID> ids) {
            Set<UUID> missing = missing(ids, gateById);
            if (!missing.isEmpty()) {
                gates.findAllById(missing).forEach(g -> gateById.put(g.getId(), g));
            }
            return this;
        }

        public Refs preloadUsers(Collection<UUID> ids) {
            Set<UUID> missing = missing(ids, userNames);
            if (!missing.isEmpty()) {
                users.findAllById(missing).forEach(u -> userNames.put(u.getId(), u.displayName()));
            }
            return this;
        }

        public FlatRef flat(UUID id) {
            if (id == null) {
                return null;
            }
            preloadFlats(List.of(id));
            return flatRefs.get(id);
        }

        public ParkingSlot slotEntity(UUID id) {
            if (id == null) {
                return null;
            }
            preloadSlots(List.of(id));
            return slotById.get(id);
        }

        public SlotRef slot(UUID id) {
            ParkingSlot slot = slotEntity(id);
            return slot == null ? null : slotRef(slot, area(slot.getAreaId()));
        }

        public ParkingArea area(UUID id) {
            if (id == null) {
                return null;
            }
            preloadAreas(List.of(id));
            return areaById.get(id);
        }

        public AreaRef areaRefOf(UUID id) {
            return areaRef(area(id));
        }

        public Vehicle vehicleEntity(UUID id) {
            if (id == null) {
                return null;
            }
            preloadVehicles(List.of(id));
            return vehicleById.get(id);
        }

        public VehicleRef vehicle(UUID id) {
            return vehicleRef(vehicleEntity(id));
        }

        public ParkingPermit permitEntity(UUID id) {
            if (id == null) {
                return null;
            }
            preloadPermits(List.of(id));
            return permitById.get(id);
        }

        public PermitRef permit(UUID id) {
            return permitRef(permitEntity(id));
        }

        public GateRef gate(UUID id) {
            if (id == null) {
                return null;
            }
            preloadGates(List.of(id));
            SocietyGate gate = gateById.get(id);
            return gate == null ? null : gate.toRef();
        }

        public String userName(UUID id) {
            if (id == null) {
                return null;
            }
            preloadUsers(List.of(id));
            return userNames.get(id);
        }

        private static <V> Set<UUID> missing(Collection<UUID> ids, Map<UUID, V> cache) {
            return ids.stream().filter(Objects::nonNull).filter(id -> !cache.containsKey(id)).collect(Collectors.toSet());
        }
    }

    /** Display name of a user (null-safe), for "decided by" / "recorded by" fields. */
    public String userName(UUID userId) {
        return userId == null ? null : users.findById(userId).map(User::displayName).orElse(null);
    }

    static <T> Map<UUID, T> byId(Collection<T> items, Function<T, UUID> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }
}
