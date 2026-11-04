package com.mygateapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mygateapp.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Starting the context proves that every Flyway migration applies to a real PostgreSQL and that Hibernate's
 * {@code ddl-auto=validate} accepts the schema for all entities.
 */
class ApplicationStartupIT extends IntegrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void allMigrationsApplied() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).hasSizeGreaterThanOrEqualTo(8);
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions WHERE role_code = 'GUARD'", Integer.class))
                .isEqualTo(5);
    }

    @Test
    void historyTablesAreImmutable() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_trigger WHERE tgname LIKE 'trg_%_immutable'", Integer.class)).isEqualTo(4);
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiDocumentIsServed() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("MyGateApp API"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/verify-otp']").exists());
    }

    @Test
    void protectedEndpointsRequireAToken() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void unknownPathsAreDenied() throws Exception {
        // Anything outside /api/** and the public endpoints is denied; anonymous callers get 401.
        mvc.perform(get("/internal/secret")).andExpect(status().isUnauthorized());
    }
}
