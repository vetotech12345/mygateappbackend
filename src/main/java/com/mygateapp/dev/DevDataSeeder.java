package com.mygateapp.dev;

import com.mygateapp.common.ActiveStatus;
import com.mygateapp.domesticstaff.entity.DomesticStaff;
import com.mygateapp.domesticstaff.entity.StaffFlatAssignment;
import com.mygateapp.domesticstaff.repository.StaffAssignmentRepository;
import com.mygateapp.domesticstaff.repository.StaffRepository;
import com.mygateapp.flats.entity.Flat;
import com.mygateapp.flats.entity.Tower;
import com.mygateapp.flats.repository.FlatRepository;
import com.mygateapp.flats.repository.TowerRepository;
import com.mygateapp.guards.entity.GuardAssignment;
import com.mygateapp.guards.entity.GuardDutyLog;
import com.mygateapp.guards.entity.SecurityGuard;
import com.mygateapp.guards.entity.SocietyGate;
import com.mygateapp.guards.repository.GateRepository;
import com.mygateapp.guards.repository.GuardAssignmentRepository;
import com.mygateapp.guards.repository.GuardDutyLogRepository;
import com.mygateapp.guards.repository.GuardRepository;
import com.mygateapp.notices.entity.Notice;
import com.mygateapp.notices.repository.NoticeRepository;
import com.mygateapp.residents.entity.FamilyMember;
import com.mygateapp.residents.entity.FlatMembership;
import com.mygateapp.residents.repository.FamilyMemberRepository;
import com.mygateapp.residents.repository.FlatMembershipRepository;
import com.mygateapp.security.GuardPermission;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DEVELOPMENT ONLY ({@code dev} profile and {@code app.dev.seed=true}). Creates the demo society used by the Flutter
 * app's mock mode, with the same mobile numbers, so the app can be pointed at this backend without new test data.
 * Runs once: it does nothing if the GREENWOOD society already exists.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "app.dev", name = "seed", havingValue = "true")
public class DevDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    static final String SOCIETY_CODE = "GREENWOOD";

    private final UserRepository users;
    private final SocietyRepository societies;
    private final SocietySettingsRepository settings;
    private final SocietyMembershipRepository memberships;
    private final TowerRepository towers;
    private final FlatRepository flats;
    private final FlatMembershipRepository flatMemberships;
    private final FamilyMemberRepository family;
    private final GateRepository gates;
    private final GuardRepository guards;
    private final GuardAssignmentRepository assignments;
    private final GuardDutyLogRepository dutyLogs;
    private final StaffRepository staff;
    private final StaffAssignmentRepository staffAssignments;
    private final NoticeRepository notices;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DevDataSeeder(UserRepository users, SocietyRepository societies, SocietySettingsRepository settings,
                         SocietyMembershipRepository memberships, TowerRepository towers, FlatRepository flats,
                         FlatMembershipRepository flatMemberships, FamilyMemberRepository family,
                         GateRepository gates, GuardRepository guards,
                         GuardAssignmentRepository assignments, GuardDutyLogRepository dutyLogs,
                         StaffRepository staff, StaffAssignmentRepository staffAssignments, NoticeRepository notices,
                         JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.societies = societies;
        this.settings = settings;
        this.memberships = memberships;
        this.towers = towers;
        this.flats = flats;
        this.flatMemberships = flatMemberships;
        this.family = family;
        this.gates = gates;
        this.guards = guards;
        this.assignments = assignments;
        this.dutyLogs = dutyLogs;
        this.staff = staff;
        this.staffAssignments = staffAssignments;
        this.notices = notices;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (societies.existsByCode(SOCIETY_CODE)) {
            log.info("Demo data already present (society {})", SOCIETY_CODE);
            return;
        }
        transactions.executeWithoutResult(status -> createDemoData());
        log.info("""
                Demo data created. Sign in with these mobile numbers (+91) and the development OTP (app.otp.fixed-code):
                  9876543210  Resident Aarav Mehta, A-1204        9876500001  Guard Ramesh Yadav, Main Gate (on duty)
                  9876500002  New resident (profile setup), B-402 9876500003  Guard Suresh Patil (no staff attendance)
                  9876500004  Resident Neha Sharma, B-703         9876500009  Inactive account
                  9876500100  Society admin (React panel)         9876500000  Platform super admin""");
    }

    private void createDemoData() {
        Instant now = clock.instant();
        User superAdmin = user("9876500000", "Platform Admin", "platform.admin@example.com", UserStatus.ACTIVE);
        jdbc.update("INSERT INTO user_roles (user_id, role_code, granted_at) VALUES (?, 'SUPER_ADMIN', ?)",
                superAdmin.getId(), Timestamp.from(now));

        Society society = new Society();
        society.setCode(SOCIETY_CODE);
        society.setName("Greenwood Residency");
        society.setAddressLine1("Baner Road");
        society.setCity("Pune");
        society.setState("Maharashtra");
        society.setPinCode("411045");
        society.setCountry("India");
        society.setContactPhone("+91 20 4000 1234");
        society.setContactEmail("office@greenwood.example.com");
        society.setCreatedBy(superAdmin.getId());
        societies.save(society);
        SocietySettings rules = new SocietySettings();
        rules.setSocietyId(society.getId());
        rules.setVisitPurposes(new ArrayList<>(SocietySettings.DEFAULT_VISIT_PURPOSES));
        rules.setDeliveryCompanies(new ArrayList<>(List.of("QuickBite", "FreshCart", "ParcelGo", "ShopKart", "DailyBasket")));
        rules.setDeliveryApprovalRules(new EnumMap<>(SocietySettings.DEFAULT_DELIVERY_RULES));
        settings.save(rules);

        User admin = user("9876500100", "Society Office", "office@greenwood.example.com", UserStatus.ACTIVE);
        membership(society, admin, Role.SOCIETY_ADMIN);

        Map<String, Flat> flatByLabel = new HashMap<>();
        for (String code : List.of("A", "B", "C")) {
            Tower tower = new Tower();
            tower.setSocietyId(society.getId());
            tower.setCode(code);
            tower.setName("Tower " + code);
            tower.setFloorCount(14);
            towers.save(tower);
            for (int floor = 1; floor <= 14; floor++) {
                for (int unit = 1; unit <= 4; unit++) {
                    Flat flat = new Flat();
                    flat.setSocietyId(society.getId());
                    flat.setTower(tower);
                    flat.setFloorNumber(floor);
                    flat.setFlatNumber(String.valueOf(floor * 100 + unit));
                    flat.setFlatType(unit <= 2 ? "3BHK" : "2BHK");
                    flats.save(flat);
                    flatByLabel.put(code + "-" + flat.getFlatNumber(), flat);
                }
            }
        }

        SocietyGate main = gate(society, "Main Gate", SocietyGate.GateType.MAIN);
        SocietyGate service = gate(society, "Service Gate", SocietyGate.GateType.SERVICE);

        User aarav = user("9876543210", "Aarav Mehta", "aarav.mehta@example.com", UserStatus.ACTIVE);
        FlatMembership aaravHome = resident(society, aarav, flatByLabel.get("A-1204"), FlatMembership.Occupancy.OWNER, admin, now);
        familyMember(aaravHome, "Priya Mehta", "Spouse", "9876543211");
        familyMember(aaravHome, "Kabir Mehta", "Son", null);
        User newcomer = user("9876500002", null, null, UserStatus.ACTIVE);
        resident(society, newcomer, flatByLabel.get("B-402"), FlatMembership.Occupancy.TENANT, admin, now);
        User neha = user("9876500004", "Neha Sharma", null, UserStatus.ACTIVE);
        resident(society, neha, flatByLabel.get("B-703"), FlatMembership.Occupancy.OWNER, admin, now);
        User inactive = user("9876500009", "Inactive Resident", null, UserStatus.INACTIVE);
        resident(society, inactive, flatByLabel.get("C-201"), FlatMembership.Occupancy.OWNER, admin, now);

        User ramesh = user("9876500001", "Ramesh Yadav", null, UserStatus.ACTIVE);
        SecurityGuard rameshGuard = guard(society, ramesh, "GW-G-001", EnumSet.allOf(GuardPermission.class), true, now);
        assign(rameshGuard, main, "Day shift", LocalTime.of(8, 0), LocalTime.of(20, 0), admin, now);
        GuardDutyLog duty = new GuardDutyLog();
        duty.setSocietyId(society.getId());
        duty.setGuardId(rameshGuard.getId());
        duty.setGateId(main.getId());
        duty.setStartedAt(now);
        dutyLogs.save(duty);
        User suresh = user("9876500003", "Suresh Patil", null, UserStatus.ACTIVE);
        SecurityGuard sureshGuard = guard(society, suresh, "GW-G-002",
                EnumSet.of(GuardPermission.VISITOR_ENTRY, GuardPermission.DELIVERY_ENTRY), false, now);
        assign(sureshGuard, service, "Night shift", LocalTime.of(20, 0), LocalTime.of(8, 0), admin, now);

        staffMember(society, "Sunita Devi", "9123456780", DomesticStaff.Category.MAID, "GR-S-1021",
                List.of(flatByLabel.get("A-1204"), flatByLabel.get("B-402")), admin, now);
        staffMember(society, "Ramu Prasad", "9123456781", DomesticStaff.Category.DRIVER, "GR-S-1022",
                List.of(flatByLabel.get("A-1204")), admin, now);

        Notice notice = new Notice();
        notice.setSocietyId(society.getId());
        notice.setTitle("Water supply interruption on Saturday");
        notice.setBody("Dear residents,\n\nDue to cleaning of the overhead water tanks, water supply will be interrupted "
                + "on Saturday from 10:00 AM to 4:00 PM in all towers.\n\nPlease store sufficient water in advance.\n\n"
                + "— Managing Committee");
        notice.setCategory(Notice.Category.MAINTENANCE);
        notice.setImportant(true);
        notice.setAudience(Notice.Audience.RESIDENTS);
        notice.setStatus(Notice.Status.PUBLISHED);
        notice.setPublishedAt(now);
        notice.setCreatedBy(admin.getId());
        notices.save(notice);
    }

    private User user(String mobile, String name, String email, UserStatus status) {
        User user = new User();
        user.setCountryCode("+91");
        user.setMobile(mobile);
        user.setName(name);
        user.setEmail(email);
        user.setStatus(status);
        // Flushed immediately: some rows referencing users are written with plain JDBC.
        return users.saveAndFlush(user);
    }

    private void membership(Society society, User user, Role role) {
        SocietyMembership membership = new SocietyMembership();
        membership.setSocietyId(society.getId());
        membership.setUserId(user.getId());
        membership.setRole(role);
        membership.setStatus(SocietyMembership.Status.ACTIVE);
        memberships.save(membership);
    }

    private FlatMembership resident(Society society, User user, Flat flat, FlatMembership.Occupancy occupancy, User admin,
                                    Instant now) {
        FlatMembership membership = new FlatMembership();
        membership.setSocietyId(society.getId());
        membership.setFlat(flat);
        membership.setUser(user);
        membership.setOccupancy(occupancy);
        membership.setStatus(FlatMembership.Status.ACTIVE);
        membership.setPrimary(true);
        membership.setApprovedBy(admin.getId());
        membership.setApprovedAt(now);
        membership.setStartDate(LocalDate.of(2023, 4, 1));
        flatMemberships.save(membership);
        membership(society, user, Role.RESIDENT);
        return membership;
    }

    private void familyMember(FlatMembership membership, String name, String relation, String mobile) {
        FamilyMember member = new FamilyMember();
        member.setFlatMembershipId(membership.getId());
        member.setSocietyId(membership.getSocietyId());
        member.setName(name);
        member.setRelation(relation);
        member.setMobile(mobile);
        family.save(member);
    }

    private SocietyGate gate(Society society, String name, SocietyGate.GateType type) {
        SocietyGate gate = new SocietyGate();
        gate.setSocietyId(society.getId());
        gate.setName(name);
        gate.setGateType(type);
        return gates.save(gate);
    }

    private SecurityGuard guard(Society society, User user, String code, EnumSet<GuardPermission> permissions, boolean onDuty,
                                Instant now) {
        SecurityGuard guard = new SecurityGuard();
        guard.setSocietyId(society.getId());
        guard.setUser(user);
        guard.setEmployeeCode(code);
        guard.setAgencyName("SecureForce Services");
        guard.setPermissions(permissions);
        guard.setOnDuty(onDuty);
        guard.setDutyChangedAt(now);
        guards.save(guard);
        membership(society, user, Role.GUARD);
        return guard;
    }

    private void assign(SecurityGuard guard, SocietyGate gate, String shift, LocalTime start, LocalTime end, User admin,
                        Instant now) {
        GuardAssignment assignment = new GuardAssignment();
        assignment.setSocietyId(guard.getSocietyId());
        assignment.setGuardId(guard.getId());
        assignment.setGate(gate);
        assignment.setShiftName(shift);
        assignment.setShiftStart(start);
        assignment.setShiftEnd(end);
        assignment.setAssignedBy(admin.getId());
        assignment.setValidFrom(now);
        assignments.save(assignment);
    }

    private void staffMember(Society society, String name, String mobile, DomesticStaff.Category category, String pass,
                             List<Flat> assignedFlats, User admin, Instant now) {
        DomesticStaff member = new DomesticStaff();
        member.setSocietyId(society.getId());
        member.setName(name);
        member.setMobile(mobile);
        member.setCategory(category);
        member.setPassNumber(pass);
        member.setStatus(ActiveStatus.ACTIVE);
        member.setWorkingSince(LocalDate.of(2023, 4, 1));
        member.setCreatedBy(admin.getId());
        staff.save(member);
        for (Flat flat : assignedFlats) {
            StaffFlatAssignment assignment = new StaffFlatAssignment();
            assignment.setSocietyId(society.getId());
            assignment.setStaffId(member.getId());
            assignment.setFlat(flat);
            assignment.setAssignedAt(now);
            staffAssignments.save(assignment);
        }
    }
}
