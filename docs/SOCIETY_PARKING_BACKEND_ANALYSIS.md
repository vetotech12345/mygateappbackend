# Society & parking — backend analysis

Written 2026-11-12 before any parking code was added. It records what already exists in the three projects, what
is missing, and the compatibility decisions that the implementation follows.

| Project | Location (verified) | Access |
| --- | --- | --- |
| Spring Boot backend | `C:\mygateappbackend\mygateapp-backend` | read / write |
| Flutter app (`society_app`) | `C:\Users\hp\Desktop\mygateapp` | read only |
| React admin (`mygateapp-admin`) | `C:\Users\hp\Desktop\mygateweb` | read only |

The master prompt names `C:\Users\hp\Desktop\mygateapp-backend` and `C:\Users\hp\Desktop\mygateapp-admin`; neither
exists. The verified repositories above are used and no duplicate project was created.

## 1. Existing backend (Phase 1)

| Area | What exists | Reused for parking |
| --- | --- | --- |
| Stack | Java 21, Spring Boot 4.1.2, Spring Security 7 (HS256 JWT resource server), Hibernate 7 (`ddl-auto=validate`), Flyway V1–V8, PostgreSQL 17, Redis 7, springdoc 3, Firebase Admin 9, Testcontainers | Unchanged. No dependency added or upgraded |
| Architecture | Modular monolith, one package per feature (`controller/dto/entity/repository/service`), thin controllers, DTO records, `GlobalExceptionHandler` → `{success,code,message,errors,timestamp,path}` | New packages `vehicle`, `parking`, `parkingpayment`, `parkingreport` follow the same layout |
| Societies | `societies`, `society_settings` (visitor/delivery rules), `society_memberships` (role per society), `society_counters` (atomic sequences) | Parking settings get their own 1:1 table `society_parking_settings`; counters issue request/permit/receipt numbers |
| Structure | `towers`, `wings`, `flats` with composite keys `(id, society_id)` | Vehicles, allocations, requests, sessions reference flats through `(flat_id, society_id)` |
| Residents | `flat_memberships` (ACTIVE owner/tenant per flat) | Residents act only on vehicles/requests of their active flats (`AccessService.requireResidentFlat`) |
| Guards & gates | `security_guards`, `guard_permissions` (`VISITOR_ENTRY`, `DELIVERY_ENTRY`, `STAFF_ATTENDANCE`), `guard_assignments` (one active gate), `guard_duty_logs`, gate types incl. `VEHICLE` | New guard toggle `VEHICLE_ENTRY`; `AccessService.requireOnDutyGuard` enforces on-duty + assigned gate + toggle |
| Authorization | `AccessService`: society context from memberships or `X-Society-Id`; `require(societyId, Permission)`; `ensureInScope` → 404 for other societies; role grants in `role_permissions` (V8) loaded by `RolePermissionRegistry` | New permissions seeded by V9; every parking service call goes through `AccessService` |
| Visitors | `visitors` (walk-in + invitation, separate approval and movement state), `visitor_events` (immutable), `vehicle_number` free text | Visitor parking **links** to `visitors.id`; parking never approves a visitor — visitor approval stays in the visitor module |
| Gate safety | `GateMovementPolicy`, row locks (`SELECT … FOR UPDATE`) + `@Version`, server clock (`MicrosecondClock`) | Same patterns for parking sessions; plus partial unique indexes and a GiST exclusion constraint |
| Idempotency | `IdempotencyFilter`: optional `Idempotency-Key` on any authenticated POST, Redis, 24 h replay | Entry, exit and payment calls use it (Flutter already sends the header) |
| Notifications | `notifications` + transactional `notification_outbox`, `NotificationDispatcher` (FCM or log provider) | New type `PARKING_UPDATE` (already understood by Flutter) |
| Audit | `audit_logs` (immutable), `AuditService.record` in the caller's transaction | Every approval, allocation, permit, entry, exit, payment, refund and rate change |
| Jobs | `MaintenanceJobs` (visitor expiry, retention) | New `ParkingJobs`: expiry, overstay detection, permit reminders, retention of visitor data on requests |
| Tests | 117 tests; `IntegrationTest` + `WorldBuilder` build a society through the public API | New ITs reuse `WorldBuilder` |

### Vehicle / parking functionality before this work

A search for `vehicle|parking` in `src/main`, migrations and docs found only:

* `visitors.vehicle_number varchar(13)` (free text, upper-cased, not normalised),
* complaint category `PARKING`,
* gate type `VEHICLE`.

There was **no** vehicle registry, parking inventory, allocation, permit, session, rate, payment or report API.

## 2. Flutter app (read only)

The Flutter app already contains complete society, vehicle and parking features
(`lib/features/{society,vehicles,parking}`) with typed remote data sources. They are only called when the app is
built with `--dart-define=PARKING_API=true`; otherwise parking entry points are hidden. The client contract is
`docs/PARKING_API_REQUIREMENTS.md` in the Flutter project, and its DTO parsers (`parking_dto.dart`,
`vehicle_dto.dart`, `society_dto.dart`) fix the exact JSON field names and enum values. **This backend implements
that contract** so that the existing app works without changes.

Client behaviours the backend must respect:

| Client behaviour | Backend consequence |
| --- | --- |
| Enums parsed by `enumFromWire` (case/underscore-insensitive, unknown → fallback value) | Send UPPER_SNAKE values from the Flutter enums. An unknown value would silently fall back (e.g. payment `CONFIRMED` would show as `PENDING`), so the Flutter values win over the master prompt's suggestions |
| `ApiClient.postJson` rejects empty 204 bodies | Every POST the app calls returns a JSON body |
| `getList` accepts a JSON array or a page with `content` | Rates, allocations, permits, vehicle search: arrays. Slots: page with an area-sized default |
| Money is `{ "amount": "120.00", "currency": "INR" }` | `BigDecimal` serialised as a decimal **string** with scale 2 |
| `Idempotency-Key` sent on entry, exit, payment | Existing filter replays the first response |
| Verification id must be fresh and from the same guard | Stored server-side (`parking_verifications`), single use, 5-minute expiry |
| Guard parking UI requires `GuardPermission.vehicleEntry` | New toggle `VEHICLE_ENTRY` returned in `/users/me` `guard.permissions` |
| `NotificationType.parkingUpdate` | New `NotificationType.PARKING_UPDATE` |
| `GET /societies/my` for residents and guards | New endpoint (the existing `/societies/{id}` stays admin-only) |

## 3. React admin (read only)

`mygateweb` (React 19, TanStack Query, axios). Conventions: `api` axios instance, `X-Society-Id` from the society
switcher, Spring pages, `ApiErrorBody` mapped to form errors, navigation items gated by permission codes from
`/users/me` → `roles`/permissions. It has **no** parking or vehicle screens; only the `PARKING` complaint category
and gate types. Every admin parking API in this document is therefore new and is mapped in
`REACT_ADMIN_PARKING_API_MAPPING.md` for the future admin screens.

## 4. Gap list (all implemented in this change)

| Requirement | Implementation |
| --- | --- |
| Society parking settings | `society_parking_settings`, `GET/PUT /societies/{id}/parking/settings` (admins), `GET /parking/config` (members, filtered) |
| My society for residents/guards | `GET /societies/my` |
| Vehicle registry and approval | `vehicles`, `/vehicles` CRUD + submit/approve/reject/suspend, search |
| Areas, slots, availability | `parking_areas`, `parking_slots`, `/parking/areas`, `/parking/slots`, `/parking/availability` |
| Allocations (permanent, temporary, transfer, cancel, expiry) | `parking_allocations` with GiST exclusion constraint, `/parking/allocations` |
| Permits | `parking_permits`, `/parking/permits` |
| Resident requests and visitor parking | `parking_requests` (one table, `type` incl. `VISITOR_SLOT`), `/parking/requests`, `/parking/visitor-requests` |
| Gate verification, entry, exit | `parking_verifications`, `parking_sessions`, `parking_movements`, `/parking/verifications`, `/parking/sessions` |
| Pricing, quotes, payments, receipts | `parking_rate_plans`, `parking_quotes`, `parking_payments`, `parking_payment_events`, `parking_receipts` |
| Reports | `/parking/reports/{overview,occupancy,sessions,revenue}` |
| Notifications | `PARKING_UPDATE` via the existing outbox |
| Background work | `ParkingJobs` |

## 5. Compatibility decisions

### 5.1 Enum reconciliation

Where the master prompt's suggested enums conflict with values the Flutter app already parses, the Flutter values
are used (they are a superset or a rename). Mapping:

| Master prompt | Implemented (wire value) | Note |
| --- | --- | --- |
| `VehicleCategory` RESIDENT, VISITOR, PAID, SOCIETY, SERVICE | RESIDENT, VISITOR, PAID, SOCIETY, **STAFF**, SERVICE, **EMERGENCY** | superset |
| `ParkingUsage` SOCIETY_RESERVED | **RESERVED** (plus SOCIETY, STAFF, SERVICE, EMERGENCY) | renamed |
| `ParkingUsage` FLEXIBLE | **COMMON** | renamed; Flutter's fallback for unknown usage is `COMMON` |
| `RequestStatus` | PENDING, APPROVED, REJECTED, CANCELLED, EXPIRED | identical |
| `SessionStatus` ACTIVE, CLOSED, CANCELLED | ACTIVE, **OVERSTAY**, **COMPLETED**, CANCELLED | `CLOSED` → `COMPLETED`; `OVERSTAY` is derived (stored ACTIVE, past `validUntil`) |
| `PaymentStatus` PENDING, CONFIRMED, FAILED, REFUNDED | PENDING, **PAID**, FAILED, REFUNDED, **WAIVED** | `CONFIRMED` → `PAID` |
| Vehicle "registration status" + "verification status" | one field `registration.status`: NOT_SUBMITTED, PENDING, APPROVED, REJECTED, SUSPENDED + `active` flag | the society-office review **is** the verification; two fields would contradict each other |

### 5.2 Other decisions

1. **Registration never grants access.** Gate entry needs an ACTIVE permit (issued with an allocation, an approved
   request or by an admin), an approved visitor parking request, or a paid session. A society may relax this for
   approved resident vehicles with `residentEntryRequiresPermit=false` (explicit setting, default `true`).
2. **One visitor approval workflow.** Parking requests reserve capacity; they never approve a person. At the gate a
   linked visitor that is still `PENDING` yields `NEEDS_VISITOR_APPROVAL`, and the guard uses the existing visitor
   approval flow. When a session links an approved visitor, the visitor's check-in/check-out is recorded through
   `VisitorService` (only if the guard also holds `VISITOR_ENTRY`).
3. **Emergency override is explicit.** `EMERGENCY` entry of a vehicle without authorisation is only possible when the
   society enables `emergencyOverrideAllowed`; it requires a reason, is flagged on the session, written to the
   movement history and audit log, and notifies society admins.
4. **Money.** `numeric(12,2)` columns, `BigDecimal` with `HALF_UP` rounding, ISO currency codes, decimal strings on
   the wire. Clients never send amounts that are trusted: quotes and payments are recomputed server-side.
5. **Pricing snapshots.** A session copies the rate plan into `rate_snapshot` at entry; later rate edits never change
   the charge of an existing session.
6. **Online payments are not operational.** No provider or credentials exist. An `OnlinePaymentGateway` interface
   exists with a disabled implementation; `onlinePaymentEnabled` is always `false`. Manual (cash, UPI, card at gate,
   bank transfer) payments and waivers are fully functional.
7. **Existing guards do not get `VEHICLE_ENTRY` automatically** (no production data is changed). New guards created
   without an explicit permission list get all toggles, as before. Admins grant it with `PUT /guards/{id}`.
8. **No destructive migration.** V9 only creates tables, indexes, triggers and seed rows, widens one CHECK constraint
   (`guard_permissions`), and adds one UNIQUE constraint `(id, society_id)` on `visitors` (always satisfied because
   `id` is the primary key).
9. **`btree_gist` extension.** The allocation overlap guard is a GiST exclusion constraint on `(slot_id, period)`,
   which needs `btree_gist` (a *trusted* extension since PostgreSQL 13, available on all major managed services).
   V9 runs `CREATE EXTENSION IF NOT EXISTS btree_gist`.

## 6. Risks / open items

| Item | Status |
| --- | --- |
| Online payment provider | Not selected; interface only |
| Firebase delivery | Uses the existing provider switch; real delivery still untested (no credentials) |
| React admin parking screens | Do not exist; API mapping documented |
| Flutter `PERMISSION_DENIED` code | The app expects it for missing guard permission; the backend has always sent `GUARD_PERMISSION_DENIED` (403). Both map to the app's forbidden screen |
| Visitor `vehicle_number` format | Stored as typed (e.g. `MH 12 AB 1234`); parking compares a normalised form, existing data is not rewritten |
