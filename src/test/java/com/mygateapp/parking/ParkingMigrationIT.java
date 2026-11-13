package com.mygateapp.parking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mygateapp.support.IntegrationTest;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Upgrade path: a database with Phase 1 data (V1–V8) is migrated to V9. Existing rows must survive unchanged and the
 * new constraints must work. Runs in a throw-away database inside the test container.
 */
class ParkingMigrationIT extends IntegrationTest {

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    JdbcTemplate mainJdbc;

    @Test
    void phaseOneDataSurvivesTheParkingMigration() {
        String database = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        mainJdbc.execute("CREATE DATABASE " + database);
        try {
            String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("8")).load().migrate();
            assertThat(jdbc.queryForObject("SELECT to_regclass('public.vehicles') IS NULL", Boolean.class)).isTrue();

            UUID user = UUID.randomUUID();
            UUID society = UUID.randomUUID();
            UUID tower = UUID.randomUUID();
            UUID flat = UUID.randomUUID();
            UUID gate = UUID.randomUUID();
            UUID guard = UUID.randomUUID();
            UUID visitor = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, country_code, mobile, name, status, created_at, updated_at) "
                    + "VALUES (?, '+91', '9876543210', 'Aarav Mehta', 'ACTIVE', now(), now())", user);
            jdbc.update("INSERT INTO societies (id, code, name, address_line1, city, state, pin_code, country, status, created_at, updated_at) "
                    + "VALUES (?, 'UPGRADE', 'Upgrade Society', '1 Road', 'Pune', 'MH', '411001', 'India', 'ACTIVE', now(), now())", society);
            jdbc.update("INSERT INTO towers (id, society_id, code, name, status, created_at, updated_at) "
                    + "VALUES (?, ?, 'A', 'Tower A', 'ACTIVE', now(), now())", tower, society);
            jdbc.update("INSERT INTO flats (id, society_id, tower_id, floor_number, flat_number, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 12, '1204', 'ACTIVE', now(), now())", flat, society, tower);
            jdbc.update("INSERT INTO society_gates (id, society_id, name, gate_type, status, created_at, updated_at) "
                    + "VALUES (?, ?, 'Main Gate', 'MAIN', 'ACTIVE', now(), now())", gate, society);
            jdbc.update("INSERT INTO security_guards (id, society_id, user_id, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 'ACTIVE', now(), now())", guard, society, user);
            jdbc.update("INSERT INTO guard_permissions (guard_id, permission) VALUES (?, 'VISITOR_ENTRY'), (?, 'STAFF_ATTENDANCE')",
                    guard, guard);
            jdbc.update("INSERT INTO visitors (id, society_id, flat_id, gate_id, source, name, visitor_type, purpose, "
                    + "vehicle_number, approval_status, movement_status, created_by, requested_at, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, 'WALK_IN', 'Vikram', 'GUEST', 'Visit', 'MH 14 AB 1111', 'PENDING', 'NOT_ENTERED', ?, "
                    + "now(), now(), now())", visitor, society, flat, gate, user);
            // The V3 CHECK rejects the new toggle before V9.
            assertThatThrownBy(() -> jdbc.update("INSERT INTO guard_permissions (guard_id, permission) VALUES (?, 'VEHICLE_ENTRY')", guard))
                    .isInstanceOf(DataIntegrityViolationException.class);

            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

            // Existing rows are unchanged.
            assertThat(jdbc.queryForObject("SELECT name FROM visitors WHERE id = ?", String.class, visitor)).isEqualTo("Vikram");
            assertThat(jdbc.queryForObject("SELECT vehicle_number FROM visitors WHERE id = ?", String.class, visitor))
                    .isEqualTo("MH 14 AB 1111");
            assertThat(jdbc.queryForList("SELECT permission FROM guard_permissions WHERE guard_id = ? ORDER BY permission",
                    String.class, guard)).containsExactly("STAFF_ATTENDANCE", "VISITOR_ENTRY");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM society_parking_settings", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions WHERE role_code = 'RESIDENT'", Integer.class))
                    .isEqualTo(16);

            // New constraints work on the upgraded schema.
            jdbc.update("INSERT INTO guard_permissions (guard_id, permission) VALUES (?, 'VEHICLE_ENTRY')", guard);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO guard_permissions (guard_id, permission) VALUES (?, 'ANYTHING')", guard))
                    .isInstanceOf(DataIntegrityViolationException.class);
            UUID area = UUID.randomUUID();
            UUID slot = UUID.randomUUID();
            jdbc.update("INSERT INTO parking_areas (id, society_id, code, name, usage_type, vehicle_types, status, created_at, updated_at) "
                    + "VALUES (?, ?, 'B1', 'Basement 1', 'RESIDENT', 'CAR', 'ACTIVE', now(), now())", area, society);
            jdbc.update("INSERT INTO parking_slots (id, society_id, area_id, slot_number, vehicle_type, usage_type, "
                    + "operational_status, created_at, updated_at) VALUES (?, ?, ?, 'B1-001', 'CAR', 'RESIDENT', 'ACTIVE', now(), now())",
                    slot, society, area);
            String allocation = "INSERT INTO parking_allocations (id, society_id, slot_id, flat_id, allocation_type, status, "
                    + "valid_from, valid_until, created_at, updated_at) VALUES (gen_random_uuid(), ?, ?, ?, 'TEMPORARY', 'ACTIVE', "
                    + "now(), now() + interval '2 days', now(), now())";
            jdbc.update(allocation, society, slot, flat);
            assertThatThrownBy(() -> jdbc.update(allocation, society, slot, flat))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ex_parking_allocations_overlap");
            // Parking rows can reference existing visitors of the same society only.
            jdbc.update("INSERT INTO parking_requests (id, society_id, reference_number, request_type, status, flat_id, "
                    + "requested_by, vehicle_number, vehicle_type, valid_from, valid_until, visitor_id, visitor_name, parking_type, "
                    + "created_at, updated_at) VALUES (gen_random_uuid(), ?, 'PR-1', 'VISITOR_SLOT', 'PENDING', ?, ?, 'MH14AB1111', "
                    + "'CAR', now(), now() + interval '1 hour', ?, 'Vikram', 'VISITOR', now(), now())", society, flat, user, visitor);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_extension WHERE extname = 'btree_gist'", Integer.class))
                    .isEqualTo(1);
        } finally {
            mainJdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }
}
