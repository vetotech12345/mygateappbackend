package com.mygateapp.support;

import com.mygateapp.security.Role;
import com.mygateapp.society.entity.Society;
import com.mygateapp.society.entity.SocietyMembership;
import com.mygateapp.society.entity.SocietySettings;
import com.mygateapp.society.repository.SocietyMembershipRepository;
import com.mygateapp.society.repository.SocietyRepository;
import com.mygateapp.society.repository.SocietySettingsRepository;
import com.mygateapp.users.entity.User;
import com.mygateapp.users.enums.UserStatus;
import com.mygateapp.users.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Low-level fixtures written straight to the database (for tests that must not depend on admin APIs). */
@TestComponent
public class TestData {

    private final UserRepository users;
    private final SocietyRepository societies;
    private final SocietySettingsRepository settings;
    private final SocietyMembershipRepository memberships;
    private final JdbcTemplate jdbc;

    public TestData(UserRepository users, SocietyRepository societies, SocietySettingsRepository settings,
                    SocietyMembershipRepository memberships, JdbcTemplate jdbc) {
        this.users = users;
        this.societies = societies;
        this.settings = settings;
        this.memberships = memberships;
        this.jdbc = jdbc;
    }

    @Transactional
    public User user(String mobile, String name, UserStatus status) {
        User user = new User();
        user.setCountryCode("+91");
        user.setMobile(mobile);
        user.setName(name);
        user.setStatus(status);
        return users.save(user);
    }

    @Transactional
    public Society society(String name) {
        Society society = new Society();
        society.setCode("T" + Integer.toString(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE), 36).toUpperCase());
        society.setName(name);
        society.setAddressLine1("1 Test Road");
        society.setCity("Pune");
        society.setState("Maharashtra");
        society.setPinCode("411001");
        society.setCountry("India");
        Society saved = societies.save(society);
        SocietySettings s = new SocietySettings();
        s.setSocietyId(saved.getId());
        settings.save(s);
        return saved;
    }

    @Transactional
    public SocietyMembership membership(UUID userId, UUID societyId, Role role, SocietyMembership.Status status) {
        SocietyMembership membership = new SocietyMembership();
        membership.setUserId(userId);
        membership.setSocietyId(societyId);
        membership.setRole(role);
        membership.setStatus(status);
        return memberships.save(membership);
    }

    @Transactional
    public void grantSuperAdmin(UUID userId) {
        jdbc.update("INSERT INTO user_roles (user_id, role_code, granted_at) VALUES (?, 'SUPER_ADMIN', ?)",
                userId, java.sql.Timestamp.from(Instant.now()));
    }

    @Transactional
    public void setUserStatus(UUID userId, UserStatus status) {
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", status.name(), userId);
    }
}
