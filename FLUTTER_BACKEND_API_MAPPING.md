# Flutter ↔ Java Backend API Mapping

| | |
| --- | --- |
| Flutter project (audited read-only) | `C:\Users\hp\Desktop\mygateapp` (package `society_app`, brand "Haven", v1.0.0+1) |
| Backend project | `C:\mygateappbackend\mygateapp-backend` (Spring Boot 4.1.2, Java 21) |
| Audit date | 2026-11-04 |
| Flutter files read | `pubspec.yaml`, `README.md`, `docs/API_INTEGRATION.md`, all of `lib/core/**`, `lib/shared/**`, every `lib/features/*/data|domain/**`, `lib/mock/**`, `lib/app/router/**`, selected screens |
| Flutter files changed | **None.** The Flutter project was treated as read-only. |

> **Legend — two kinds of contract are listed in this document**
> * **Flutter contract (existing)** — paths and JSON shapes that are already coded in the Flutter app
>   (`lib/core/network/api_endpoints.dart` and the `*_dto.dart` files). The Flutter README itself calls them
>   *proposals*: they have never been called against a real server.
> * **Java endpoint (proposed → implemented)** — what this backend exposes. The "Backend" column says whether it is
>   implemented in this repository. Nothing in this document claims that Flutter and the backend have been tested
>   together; see [§9 End-to-end checklist](#9-end-to-end-test-checklist).

> **Backend status (updated after implementation, 2026-11-04)**
> * "Implemented" = the endpoint exists in this repository and is exercised by the automated integration tests
>   (Testcontainers PostgreSQL + Redis) and/or the live smoke run against the dev server. Response shapes were captured
>   from the running server and compared with the Flutter `*_dto.dart` parsers (samples in
>   [docs/api-contracts.md](docs/api-contracts.md)).
> * **Not yet verified**: the Flutter app running against this backend (checklist §9), SMS delivery through Twilio, and
>   push delivery through Firebase (both need real credentials).

---

## 1. Flutter audit summary

### 1.1 What the Flutter app is

* One app for **residents** and **security guards**. The role returned by `GET /api/v1/users/me` decides the
  dashboard (`lib/app/router/route_guard.dart`). Roles other than `RESIDENT` / `GUARD`, or `status != ACTIVE`, block sign-in.
* Stack: Riverpod 3, GoRouter 17, Dio 5, `flutter_secure_storage` (JWT pair), `firebase_messaging` 17.
* Every feature has `domain/` (entities + repository interface), `data/` (DTO mapper, remote data source,
  repository impl, **mock repository**), `application/`, `presentation/`.
* `USE_MOCK=true` (default in debug) wires all repositories to an **in-memory mock backend** (`lib/mock/`), which
  enforces the same business rules the real backend must enforce. `USE_MOCK` is forced off when `APP_ENV=prod`.

### 1.2 Feature inventory

| Area | Screens (lib/features/…) | State in Flutter |
| --- | --- | --- |
| Auth: splash, onboarding, mobile entry, OTP, profile setup, logout, session restore | `auth/presentation/screens/*.dart` | **Working in mock mode**; real API calls coded (`auth_remote_data_source.dart`), never run against a server |
| Resident dashboard | `resident/presentation/screens/resident_home_screen.dart` | Mock-only (remote impl coded) |
| Resident activity feed | `resident/presentation/screens/resident_activity_screen.dart` | Mock-only (remote impl coded) |
| Resident visitors: pending approvals, approve/reject, invite, history, detail, cancel invite | `visitors/presentation/screens/resident_visitors_screen.dart`, `invite_visitor_screen.dart`, `visitor_detail_screen.dart` | Mock-only (remote impl coded) |
| Resident deliveries: approve / reject / leave at gate, history | `deliveries/presentation/screens/resident_deliveries_screen.dart`, `delivery_detail_screen.dart` | Mock-only (remote impl coded) |
| Resident domestic help: list, detail, attendance | `domestic_help/presentation/screens/resident_staff_screen.dart`, `staff_detail_screen.dart` | Mock-only (remote impl coded) |
| Resident complaints: list, raise (with optional photo), detail/timeline | `complaints/presentation/screens/*.dart` | Mock-only (remote impl coded) |
| Resident notices: list, filter, detail, mark read | `notices/presentation/screens/*.dart` | Mock-only (remote impl coded) |
| Resident profile: flats, family members (read-only), society card, edit profile | `profile/presentation/screens/resident_profile_screen.dart` | Mock-only; **family members are display-only** (no add/edit UI) |
| Notifications: list, unread badge, mark read / all, settings (per-type toggles) | `notifications/presentation/**`, `profile/presentation/screens/notification_settings_screen.dart` | Mock-only (remote impl coded) |
| Push notifications (FCM) | `shared/services/push/firebase_push_notification_service.dart`, `notifications/presentation/push_notification_handler.dart` | **Blocked**: no `google-services.json` / `GoogleService-Info.plist`; Android id still `com.example.mygateapp` |
| Guard dashboard + duty toggle | `guard/presentation/screens/guard_home_screen.dart`, `widgets/duty_status_card.dart` | Mock-only (remote impl coded) |
| Guard visitor entry (walk-in, with optional photo), approvals wait screen (polls every 5 s), invite-code check, mark entry, visitors inside / exit | `visitors/presentation/screens/guard_new_visitor_screen.dart`, `guard_approvals_screen.dart`, `verify_pass_screen.dart`, `visitor_exit_screen.dart` | Mock-only (remote impl coded) |
| Guard delivery entry, entry/exit | `deliveries/presentation/screens/guard_new_delivery_screen.dart` | Mock-only (remote impl coded) |
| Guard staff check-in / check-out | `domestic_help/presentation/screens/guard_staff_screen.dart` | Mock-only (remote impl coded) |
| Guard history (activity feed) | `guard/presentation/screens/guard_history_screen.dart` | Mock-only (remote impl coded) |
| Demo tools (simulate the other side) | `profile/presentation/widgets/demo_tools_card.dart`, `lib/mock/mock_simulator.dart` | **Mock-only by design** — no backend equivalent needed |
| Society admin features | — | **Not in Flutter** (React admin panel, separate project) |

### 1.3 Network layer facts that shape the backend

| Fact (source) | Backend consequence |
| --- | --- |
| `ApiClient.getJson/postJson` return the **raw decoded body** (`lib/core/network/api_client.dart`) | Success responses are **raw JSON DTOs**, not wrapped in an envelope (decision confirmed by the project owner, see §8 #1) |
| `Paginated.fromSpringPage` reads `content`, `number`, `size`, `totalElements`, `totalPages`, `last` (`lib/shared/models/paginated.dart`) | All list endpoints return a Spring-`Page`-shaped object (`PageResponse<T>`) |
| `mapDioException` reads `message` (or `error_description`/`detail`), `code` (or `errorCode`), `errors[{field,message}]` (or `fieldErrors`) (`dio_error_mapper.dart`) | Error body: `{ success:false, code, message, errors:[{field,message}], timestamp, path }` |
| 401 → one refresh via `POST /auth/refresh`; any 4xx from refresh signs out; 5xx/network does not (`auth_interceptor.dart`) | Refresh returns 401 for invalid/expired/revoked/reused tokens; access tokens are short-lived (15 min) |
| `AuthTokens.fromJson`: `accessToken`, `refreshToken`, `expiresIn` (seconds) | Token response uses exactly these names |
| Ids are opaque strings; dates parsed by `DateTime.tryParse` | UUID strings; ISO-8601 UTC instants (`2026-11-04T10:15:00Z`) |
| Enums are `UPPER_SNAKE_CASE`, parsed tolerantly (`enumFromWire`) | Java enums serialised by name |
| Multipart uploads: part `data` (JSON) + part `photo` (visitors) / `image` (complaints) | `@RequestPart("data")` + `@RequestPart("photo"|"image")` |
| Mobile input: 10 digits, `^[6-9]\d{9}$`, plus `countryCode: "+91"` (`core/utils/validators.dart`) | Same rule enforced server-side for `+91` |
| Vehicle `^[A-Z0-9 -]{4,13}$`, name `^[A-Za-z][A-Za-z .'-]*$` ≤ 60, purpose ≤ 120, complaint title 5–80, description 10–1000, reject reason ≤ 200, notes ≤ 200, guests ≤ 20, packages ≤ 20 (`app_constants.dart`) | Same limits as Bean Validation constraints |
| Flutter never sends society id or role; "the backend derives society, flats and gate from the JWT" (`API_INTEGRATION.md`) | Backend derives scope from the authenticated user's memberships; optional `X-Society-Id` header only to choose between several memberships |

### 1.4 Mock services that must be replaced by the backend

All are selected in `lib/mock/mock_providers.dart` / feature providers when `USE_MOCK=true`. Running with
`--dart-define=USE_MOCK=false` swaps in the `*RepositoryImpl` classes, which call the endpoints below.

| Flutter mock | Real implementation already coded in Flutter | Backend APIs it needs |
| --- | --- | --- |
| `MockAuthRepository` | `AuthRepositoryImpl` | `/auth/request-otp`, `/auth/verify-otp`, `/auth/refresh`, `/auth/logout`, `/users/me` |
| `MockProfileRepository` | `ProfileRepositoryImpl` | `PATCH /users/me` |
| `MockResidentRepository` | `ResidentRepositoryImpl` | `/residents/dashboard`, `/residents/activity` |
| `MockGuardRepository` | `GuardRepositoryImpl` | `/guards/dashboard`, `/guards/me/duty`, `/guards/activity` |
| `MockVisitorRepository` | `VisitorRepositoryImpl` | `/visitors/**` |
| `MockDeliveryRepository` | `DeliveryRepositoryImpl` | `/deliveries/**` |
| `MockDomesticHelpRepository` | `DomesticHelpRepositoryImpl` | `/domestic-staff/**` |
| `MockComplaintRepository` | `ComplaintRepositoryImpl` | `/complaints/**` |
| `MockNoticeRepository` | `NoticeRepositoryImpl` | `/notices/**` |
| `MockNotificationRepository` | `NotificationRepositoryImpl` | `/notifications/**`, `/devices`, `/users/me/notification-preferences` |
| `MockReferenceDataRepository` | `ReferenceDataRepositoryImpl` | `/config/lookups`, `/society/flats` |
| `MockPushNotificationService` | `FirebasePushNotificationService` | FCM via Firebase Admin SDK (server side) |
| `MockSimulator` (demo tools) | — | none (demo only) |

---

## 2. Conventions (backend)

| Topic | Contract |
| --- | --- |
| Base path | `/api/v1` |
| Auth header | `Authorization: Bearer <accessToken>` on everything except `/api/v1/auth/request-otp`, `/verify-otp`, `/refresh`, `/logout`, signed file URLs, Swagger, `/actuator/health` |
| Society context | Derived from memberships. A user with exactly one society needs nothing. Users with several societies may send `X-Society-Id: <uuid>`; otherwise the default is chosen by role precedence (RESIDENT → GUARD → SOCIETY_ADMIN). Admin APIs that take an explicit `societyId` validate it against the caller's memberships. |
| Success body | Raw DTO JSON (no envelope). `204 No Content` for actions with nothing to return. |
| Error body | `{ "success": false, "code": "VISITOR_ALREADY_EXITED", "message": "This visitor has already exited.", "errors": [ { "field": "mobile", "message": "..." } ], "timestamp": "2026-11-04T10:00:00Z", "path": "/api/v1/visitors/…/exit" }` |
| Status codes | 400 validation / bad state input · 401 unauthenticated · 403 forbidden · 404 not found **or not in your society** · 409 state conflict (already decided, already inside, already exited…) · 413 file too large · 415 bad file type · 429 rate limited · 500 generic (no internals) |
| Pagination | `page` (0-based), `size` (default 20, max 100), `sort=field,dir` on admin lists. Response: `{ content, number, size, totalElements, totalPages, first, last, numberOfElements, empty }` |
| Dates | Request and response instants are ISO-8601 (`2026-11-04T10:15:00Z`). Report ranges use local dates `from=2026-11-01&to=2026-11-04` in the society time zone (default `Asia/Kolkata`). |
| Ids | UUID strings |
| Idempotency | Optional `Idempotency-Key` header on `POST` requests: a retried request with the same key (same user, method, path) replays the first response for 24 h instead of creating a duplicate. State-changing actions (approve, entry, exit…) are additionally protected by the state machine and row locks. |
| Images | Responses contain short-lived **signed URLs** (`/api/v1/files/{id}?expires=…&signature=…`, default 1 h) that load without an `Authorization` header. Do not persist them for longer than that. |

---

## 3. Authentication and users

| Flutter feature (source) | Flutter contract (existing) | Java endpoint | Request | Response | Auth / permission | Flutter status | Backend |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Mobile entry → request OTP (`auth/presentation/screens/mobile_entry_screen.dart`, `auth_remote_data_source.dart`) | `POST /api/v1/auth/request-otp` | `POST /api/v1/auth/request-otp` | `{ "mobile": "9876543210", "countryCode": "+91" }` | `{ "requestId": "uuid", "resendAfterSeconds": 30, "expiresInSeconds": 300 }` | Public. Only **registered** users with an active/pending membership or platform role. Rate limited per mobile and per IP. | Mock-only | Implemented |
| OTP screen → verify (`otp_verification_screen.dart`) | `POST /api/v1/auth/verify-otp` | same | `{ "mobile", "otp", "requestId"? }` (`requestId` omitted when null) | `{ "accessToken", "refreshToken", "tokenType": "Bearer", "expiresIn": 900, "refreshExpiresIn": 2592000, "user": UserMe }` | Public. Max 5 attempts per OTP. | Mock-only | Implemented |
| Token refresh (`core/network/auth_interceptor.dart`) | `POST /api/v1/auth/refresh` | same | `{ "refreshToken" }` | same as verify-otp (new **rotated** refresh token) | Public. Reuse of an already-rotated token revokes the whole session (401 `REFRESH_TOKEN_REUSED`). | Coded, untested | Implemented |
| Logout (`auth_repository_impl.dart`) | `POST /api/v1/auth/logout` | same | `{ "refreshToken" }` | `204` | Public (identified by the refresh token); idempotent | Coded, untested | Implemented |
| Session restore / role routing (`auth_controller.dart`, `route_guard.dart`) | `GET /api/v1/users/me` | `GET /api/v1/users/me` **and** `GET /api/v1/auth/me` (alias required by master prompt) | — | `UserMe` (below) | Authenticated | Coded, untested | Implemented |
| Profile setup / edit (`profile_setup_screen.dart`, `profile_repository_impl.dart`) | `PATCH /api/v1/users/me` | same | `{ "name": "Aarav Mehta", "email": null }` | `UserMe` | Authenticated; society/flat/role not editable | Coded, untested | Implemented |
| Notification settings (`notification_settings_screen.dart`) | `GET` / `PUT /api/v1/users/me/notification-preferences` | same | `{ "VISITOR_CHECKED_IN": true, "DELIVERY_ARRIVAL": false, … }` | same map, all types present | Authenticated. `VISITOR_APPROVAL_REQUEST` is always `true` | Coded, untested | Implemented |
| Device registration (`push_notification_handler.dart`) | `POST /api/v1/devices` | same | `{ "token": "<FCM token / FID>", "platform": "ANDROID" \| "IOS" \| "WEB" }` | `{ "id", "platform", "active", "createdAt" }` (token not echoed) | Authenticated; a token moving to another user is re-assigned | Coded, untested | Implemented |
| Device removal on logout | `DELETE /api/v1/devices/{token}` | `DELETE /api/v1/devices/{idOrToken}` (master prompt uses `{id}`; both accepted) | — | `204` | Authenticated; own devices only | Coded, untested | Implemented |

**`UserMe`** (resident variant; guard variant has `"role": "GUARD"`, `"resident": null`):

```json
{
  "id": "0b6c…", "name": "Aarav Mehta", "mobile": "9876543210", "countryCode": "+91",
  "email": "aarav@example.com", "role": "RESIDENT", "roles": ["RESIDENT"],
  "status": "ACTIVE", "profileComplete": true, "avatarUrl": null, "activeSocietyId": "5d1e…",
  "resident": {
    "society": { "id": "5d1e…", "name": "Greenwood Residency", "city": "Pune" },
    "flats": [ { "id": "9a7f…", "tower": "A", "wing": null, "number": "1204", "label": "A-1204" } ],
    "primaryFlatId": "9a7f…", "occupancy": "OWNER",
    "familyMembers": [ { "id": "…", "name": "Priya Mehta", "relation": "Spouse", "mobile": "9876543211" } ]
  },
  "guard": null,
  "memberships": [ { "societyId": "5d1e…", "societyName": "Greenwood Residency", "role": "RESIDENT", "status": "ACTIVE" } ]
}
```

Guard block: `{ "id", "society": {…}, "gate": { "id", "name" }, "shift": "Day shift · 08:00 – 20:00", "onDuty": true, "permissions": ["VISITOR_ENTRY","DELIVERY_ENTRY","STAFF_ATTENDANCE"] }`.

Auth error codes used by Flutter: `USER_NOT_REGISTERED` (404), `USER_INACTIVE` (403), `OTP_RATE_LIMITED` / `RATE_LIMITED` (429),
`OTP_INVALID` (400, message *"Incorrect code. 4 attempts left."*), `OTP_EXPIRED` (400), `OTP_LOCKED` (400).

---

## 4. Reference data, dashboards and activity

| Flutter feature (source) | Flutter contract | Java endpoint | Request | Response | Auth / permission | Flutter status | Backend |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Lookups for all forms (`shared/services/reference_data/*`) — falls back to built-in defaults if missing | `GET /api/v1/config/lookups` | same | — | `{ visitorTypes[], deliveryCategories[{code,label,requiresApproval}], staffCategories[], complaintCategories[], noticeCategories[], visitPurposes[], deliveryCompanies[], maxInviteValidityHours, approvalTimeoutSeconds }` | Authenticated; society settings of the current society | Coded, untested | Implemented |
| Guard destination flat picker (`shared/widgets/flat_picker_field.dart`) | `GET /api/v1/society/flats` → **JSON array** | same | — | `[ FlatRef ]` (active flats of the caller's society) | Authenticated member of the society | Coded, untested | Implemented |
| Resident dashboard (`resident_home_screen.dart`) | `GET /api/v1/residents/dashboard` | same | — | `{ pendingVisitors[Visitor], pendingDeliveries[Delivery], todayVisitorCount, insideNowCount, todayDeliveryCount, staffInsideCount, openComplaintCount, unreadNoticeCount, latestNotices[Notice], recentActivity[ActivityItem] }` | RESIDENT; only the caller's active flats | Coded, untested | Implemented |
| Resident activity (`resident_activity_screen.dart`) | `GET /api/v1/residents/activity?kind=&page=&size=` | same | `kind`: `VISITOR\|DELIVERY\|STAFF\|COMPLAINT` | `Page<ActivityItem>` | RESIDENT; own flats | Coded, untested | Implemented |
| Guard dashboard (`guard_home_screen.dart`) | `GET /api/v1/guards/dashboard` | same | — | `{ todayVisitorCount, pendingApprovalCount, approvedWaitingCount, insideNowCount, deliveriesTodayCount, staffInsideCount, recentActivity[] }` | GUARD; own society | Coded, untested | Implemented |
| Duty toggle (`duty_status_card.dart`) | `POST /api/v1/guards/me/duty` | same | `{ "onDuty": true }` | `UserMe` | GUARD; logged in `guard_duty_logs` | Coded, untested | Implemented |
| Guard history (`guard_history_screen.dart`) | `GET /api/v1/guards/activity?kind=&page=&size=` | same | as resident | `Page<ActivityItem>` | GUARD; own society | Coded, untested | Implemented |

`ActivityItem`: `{ "id", "kind": "VISITOR|DELIVERY|STAFF|COMPLAINT", "title", "subtitle", "at", "referenceId", "status" }`.

---

## 5. Visitors (most important module)

### 5.1 Status model — **contract mismatch resolved**

| Flutter (single field) | Backend (two independent fields + source) |
| --- | --- |
| `status`: `PENDING, APPROVED, REJECTED, PRE_APPROVED, CHECKED_IN, CHECKED_OUT, CANCELLED, EXPIRED` | `approvalStatus`: `PENDING, APPROVED, REJECTED, CANCELLED, EXPIRED` · `movementStatus`: `NOT_ENTERED, INSIDE, EXITED` · `source`: `WALK_IN, INVITATION` |

The backend stores the master-prompt model (approval separate from gate movement) and **also returns the Flutter
`status`**, derived as: `INSIDE → CHECKED_IN`, `EXITED → CHECKED_OUT`, otherwise `APPROVED + INVITATION → PRE_APPROVED`,
otherwise `approvalStatus`. Flutter keeps working unchanged; React can use the precise fields.

Visitor types: Flutter `GUEST, FAMILY, SERVICE, CAB, OTHER`; master prompt adds *Delivery Agent*. Backend accepts
`GUEST, FAMILY, SERVICE, DELIVERY, CAB, OTHER` (labels served by `/config/lookups`).

### 5.2 Endpoints

| Flutter feature (source) | Flutter contract | Java endpoint | Request | Response | Auth / permission | Flutter status | Backend |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Lists: resident pending/expected/history; guard approvals, inside, history (`resident_visitors_screen.dart`, `guard_approvals_screen.dart`, `visitor_exit_screen.dart`) | `GET /api/v1/visitors?scope=&status=&q=&from=&page=&size=` | same | `scope`: `PENDING\|EXPECTED\|INSIDE\|HISTORY` (default HISTORY); `status` (Flutter status); `q` name/mobile/flat/purpose/vehicle; `from` instant | `Page<Visitor>` | RESIDENT: own flats; GUARD: own society; SOCIETY_ADMIN: own society | Coded, untested | Implemented |
| — (master prompt) | — | `GET /api/v1/guards/pending-visitors`, `GET /api/v1/guards/active-visitors` | `page,size` | `Page<Visitor>` | GUARD | n/a | Implemented (aliases of scope PENDING / INSIDE) |
| Visitor detail (`visitor_detail_screen.dart`) | `GET /api/v1/visitors/{id}` | same | — | `Visitor` | 403 for another flat in the same society; 404 across societies | Coded, untested | Implemented |
| Guard registers walk-in (`guard_new_visitor_screen.dart`) | `POST /api/v1/visitors` (JSON, or multipart `data` + `photo`) | same | `{ name, mobile, type, flatId, purpose, vehicleNumber?, guestCount? }` | `201 Visitor` (`PENDING`) | GUARD, on duty, `VISITOR_ENTRY`, has gate assignment; flat must be in guard's society. Notifies flat residents (`VISITOR_APPROVAL_REQUEST`) | Coded, untested | Implemented |
| Resident invites (`invite_visitor_screen.dart`) | `POST /api/v1/visitors/invites` | `POST /api/v1/visitors/invites` **and** `POST /api/v1/visitor-invitations` | `{ name, mobile, type, flatId, purpose, validFrom, validUntil, guestCount, vehicleNumber? }` | `201 Visitor` with `status: PRE_APPROVED`, 6-digit `passCode` | RESIDENT of that flat; window must end in the future, end > start, ≤ `maxInviteValidityHours` | Coded, untested | Implemented |
| — (master prompt) | — | `GET /api/v1/visitor-invitations` | `page,size` | `Page<Visitor>` (source INVITATION) | RESIDENT own flats / GUARD / ADMIN society | n/a | Implemented |
| Approve (`visitor_decision_buttons.dart`) | `POST /api/v1/visitors/{id}/approve` | same | — | `Visitor` | RESIDENT of the flat; only `PENDING` (else 409 `VISITOR_ALREADY_DECIDED`). Notifies gate guards | Coded, untested | Implemented |
| Reject | `POST /api/v1/visitors/{id}/reject` | same | `{ "reason": "Not at home" }` (optional, ≤ 200) | `Visitor` | same as approve | Coded, untested | Implemented |
| Cancel | `POST /api/v1/visitors/{id}/cancel` | same | — | `Visitor` | GUARD: pending walk-ins. RESIDENT: own upcoming invites | Coded, untested | Implemented |
| Mark entry (`guard_approvals_screen.dart`, `verify_pass_screen.dart`) | `POST /api/v1/visitors/{id}/entry` | same | — | `Visitor` | GUARD on duty with `VISITOR_ENTRY`. Only APPROVED (or invitation inside its window). Rejected → 403 `VISITOR_REJECTED`; pending → 409; already inside → 409 `VISITOR_ALREADY_INSIDE`. Notifies residents (`VISITOR_CHECKED_IN`) | Coded, untested | Implemented |
| Mark exit (`visitor_exit_screen.dart`) | `POST /api/v1/visitors/{id}/exit` | same | — | `Visitor` | GUARD. Only INSIDE; second call → 409 `VISITOR_ALREADY_EXITED`; never entered → 409 `VISITOR_NOT_INSIDE` | Coded, untested | Implemented |
| Verify invite code (`verify_pass_screen.dart`) | `POST /api/v1/visitors/verify-pass` | same | `{ "passCode": "482913" }` | `Visitor` | GUARD on duty; 404 unknown, 400 not valid now/expired/cancelled, 409 already used | Coded, untested | Implemented |

**Visitor JSON**

```json
{
  "id": "…", "name": "Vikram Iyer", "mobile": "9822011122", "type": "GUEST", "photoUrl": null,
  "flat": { "id": "…", "tower": "A", "wing": null, "number": "1204", "label": "A-1204" },
  "gate": { "id": "…", "name": "Main Gate" },
  "purpose": "Family visit", "status": "PENDING",
  "approvalStatus": "PENDING", "movementStatus": "NOT_ENTERED", "source": "WALK_IN",
  "requestedAt": "2026-11-04T10:15:00Z", "decidedAt": null, "decidedBy": null, "rejectionReason": null,
  "entryAt": null, "exitAt": null, "vehicleNumber": null, "createdBy": "Ramesh Yadav",
  "passCode": null, "validFrom": null, "validUntil": null, "guestCount": 1,
  "history": [ { "title": "Request created at Main Gate", "at": "…", "actor": "Ramesh Yadav", "status": "PENDING", "description": null } ]
}
```

`passCode` is only returned to residents of the flat, admins, and guards after a successful `verify-pass`.

---

## 6. Deliveries, domestic staff, complaints, notices, notifications

### 6.1 Deliveries

Flutter statuses `PENDING, APPROVED, REJECTED, LEFT_AT_GATE, ENTERED, COMPLETED, CANCELLED` are derived the same way
as visitors from `approvalStatus` (`PENDING, APPROVED, REJECTED, LEFT_AT_GATE, CANCELLED`) and the shared
`movementStatus` (`INSIDE → ENTERED`, `EXITED → COMPLETED`). Entry/exit use the **same gate-movement component** as visitors.

| Flutter feature (source) | Flutter contract | Java endpoint | Request | Response | Auth / permission | Flutter status | Backend |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Lists (`resident_deliveries_screen.dart`, guard home) | `GET /api/v1/deliveries?scope=&q=&from=&page=&size=` | same | `scope`: `PENDING\|ACTIVE\|HISTORY` | `Page<Delivery>` | RESIDENT own flats; GUARD/ADMIN society | Coded, untested | Implemented |
| Detail (`delivery_detail_screen.dart`) | `GET /api/v1/deliveries/{id}` | same | — | `Delivery` | as above | Coded, untested | Implemented |
| Guard registers (`guard_new_delivery_screen.dart`) | `POST /api/v1/deliveries` | same | `{ category, company, agentName, agentMobile?, flatId, packageCount, notes? }` | `201 Delivery` — `PENDING` or `APPROVED` from the society's category rule | GUARD on duty with `DELIVERY_ENTRY`. Notifies residents (`DELIVERY_ARRIVAL`) | Coded, untested | Implemented |
| Resident decision | `POST /api/v1/deliveries/{id}/approve` · `/reject` · `/leave-at-gate` | same | — | `Delivery` | RESIDENT of the flat; only `PENDING` | Coded, untested | Implemented |
| Guard movement | `POST /api/v1/deliveries/{id}/entry` · `/exit` | same | — | `Delivery` | GUARD on duty; `APPROVED → ENTERED → COMPLETED`; duplicates 409 | Coded, untested | Implemented |

`Delivery`: `{ id, category, company, agentName, agentMobile, flat, gate, status, approvalStatus, movementStatus, requestedAt, requiresApproval, decidedAt, decidedBy, entryAt, exitAt, packageCount, notes, history[] }`.

### 6.2 Domestic staff

| Flutter feature (source) | Flutter contract | Java endpoint | Request | Response | Auth / permission | Flutter status | Backend |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Staff list (`resident_staff_screen.dart`, `guard_staff_screen.dart`) | `GET /api/v1/domestic-staff?q=&presence=&category=&page=&size=` | same | `presence`: `INSIDE\|OUTSIDE` | `Page<DomesticStaff>` | RESIDENT: staff assigned to own flats; GUARD/ADMIN: society | Coded, untested | Implemented |
| Staff detail (`staff_detail_screen.dart`) | `GET /api/v1/domestic-staff/{id}` | same | — | `DomesticStaff` | as above | Coded, untested | Implemented |
| Attendance list | `GET /api/v1/domestic-staff/attendance?staffId=&from=&page=&size=` | same **and** `GET /api/v1/domestic-staff/{id}/attendance` (master prompt) | | `Page<Attendance>` | as above | Coded, untested | Implemented |
| Check-in / out | `POST /api/v1/domestic-staff/{id}/check-in` · `/check-out` | same | — | `DomesticStaff` | GUARD on duty with `STAFF_ATTENDANCE`; duplicate → 409. Check-in notifies residents (`DOMESTIC_HELP_ARRIVAL`) | Coded, untested | Implemented |
| — (React admin) | — | `POST /api/v1/domestic-staff`, `PUT /api/v1/domestic-staff/{id}`, `PATCH /api/v1/domestic-staff/{id}/status` | `{ name, mobile, category, flatIds[], passNumber?, workingSince? }` | `DomesticStaff` | SOCIETY_ADMIN | n/a | Implemented |

`DomesticStaff`: `{ id, name, mobile, category, photoUrl, assignedFlats[FlatRef], inside, lastCheckInAt, lastCheckOutAt, workingSince, passNumber, status }`.
`Attendance`: `{ id, staffId, staffName, category, checkInAt, checkOutAt, gate, recordedBy, checkOutRecordedBy }`.

### 6.3 Complaints

| Flutter feature (source) | Flutter contract | Java endpoint | Request | Response | Auth / permission | Flutter status | Backend |
| --- | --- | --- | --- | --- | --- | --- | --- |
| List (`complaints_screen.dart`) | `GET /api/v1/complaints?status=&page=&size=` | same (+ `category`, `flatId`, `q` for admins) | | `Page<Complaint>` | RESIDENT own flats; SOCIETY_ADMIN society | Coded, untested | Implemented |
| Detail (`complaint_detail_screen.dart`) | `GET /api/v1/complaints/{id}` | same | — | `Complaint` (with history, non-internal comments) | as above | Coded, untested | Implemented |
| Raise (`new_complaint_screen.dart`) | `POST /api/v1/complaints` (JSON, or multipart `data` + `image`) | same | `{ category, title, description, flatId }` | `201 Complaint` with `ticketNumber` `CMP-1001` | RESIDENT of the flat | Coded, untested | Implemented |
| — (React admin / master prompt) | — | `PUT /api/v1/complaints/{id}` · `PATCH /api/v1/complaints/{id}/status` · `POST /api/v1/complaints/{id}/comments` | `PUT`: `{ title?, description?, category?, assignedToUserId? }`; `PATCH`: `{ status, remarks? }`; comment: `{ body, internal? }` | `Complaint` / `Comment` | Status: SOCIETY_ADMIN (resident may only close a `RESOLVED` complaint). Sends `COMPLAINT_UPDATE` | Flutter: status changes "come from the admin panel" | Implemented |

`Complaint`: `{ id, ticketNumber, category, title, description, attachments[url], createdAt, updatedAt, status, adminRemarks, flat, raisedBy, assignedTo, history[], comments[] }`.

### 6.4 Notices

| Flutter feature (source) | Flutter contract | Java endpoint | Request | Response | Auth / permission | Flutter status | Backend |
| --- | --- | --- | --- | --- | --- | --- | --- |
| List (`notices_screen.dart`) | `GET /api/v1/notices?category=&unread=&page=&size=` | same | | `Page<Notice>` | Members see **published** notices for their society and audience; admins also see drafts | Coded, untested | Implemented |
| Detail (`notice_detail_screen.dart`) | `GET /api/v1/notices/{id}` | same | — | `Notice` | as above | Coded, untested | Implemented |
| Mark read | `POST /api/v1/notices/{id}/read` | same | — | `204` | as above; idempotent | Coded, untested | Implemented |
| — (React admin) | — | `POST /api/v1/notices` · `PUT /{id}` · `DELETE /{id}` · `POST /{id}/publish` · `POST /{id}/attachments` (multipart `file`) | `{ title, body, category, important, audience: ALL\|RESIDENTS\|GUARDS\|TOWERS, towerIds[], publish }` | `Notice` | SOCIETY_ADMIN. Publishing notifies the audience (`ANNOUNCEMENT`) | n/a | Implemented |

`Notice`: `{ id, title, body, category, publishedAt, postedBy, important, read, audience (label, e.g. "All residents", "Tower A"), audienceType, towerIds[], status, attachments[{name,url,size}] }`.

### 6.5 Notifications

| Flutter feature (source) | Flutter contract | Java endpoint | Request | Response | Auth | Flutter status | Backend |
| --- | --- | --- | --- | --- | --- | --- | --- |
| List (`notifications_screen.dart`) | `GET /api/v1/notifications?page=&size=` | same | | `Page<{ id, type, title, body, createdAt, read, referenceId, referenceType }>` | own only | Coded, untested | Implemented |
| Badge (`notification_bell.dart`) | `GET /api/v1/notifications/unread-count` | same | — | `{ "count": 3 }` | own only | Coded, untested | Implemented |
| Mark read | `POST /api/v1/notifications/{id}/read` | `POST` (Flutter) **and** `PATCH` (master prompt) | — | `204` | own only | Coded, untested | Implemented |
| Mark all read | `POST /api/v1/notifications/read-all` | same | — | `204` | own only | Coded, untested | Implemented |
| Push payload (`push_notification_service.dart`) | `notification{title,body}` + `data{type, referenceId, notificationId}` | Firebase Admin SDK, `Message` with the same keys (+ `referenceType`) | | | | **Blocked** (Firebase config) | Implemented; FCM delivery untested (no Firebase project) |

Types: `VISITOR_APPROVAL_REQUEST, VISITOR_APPROVED, VISITOR_REJECTED, VISITOR_CHECKED_IN, DELIVERY_ARRIVAL, DOMESTIC_HELP_ARRIVAL, COMPLAINT_UPDATE, ANNOUNCEMENT, GENERAL`.

---

## 7. Backend-only APIs (React admin dashboard and platform admin)

None of these exist in Flutter. All are implemented and covered by integration tests; see Swagger UI and [docs/api-contracts.md](docs/api-contracts.md).

| Area | Endpoints | Roles |
| --- | --- | --- |
| Societies | `POST/GET /societies`, `GET/PUT /societies/{id}`, `PATCH /societies/{id}/status`, `GET/PUT /societies/{id}/settings`, `GET/POST /societies/{id}/admins`, `DELETE /societies/{id}/admins/{userId}` | SUPER_ADMIN (all); SOCIETY_ADMIN (own society: read, update settings, list admins) |
| Platform | `GET /platform/stats`, `GET/PUT /platform/settings`, `PATCH /users/{id}/status` | SUPER_ADMIN |
| Towers / wings / flats | `POST/GET /towers`, `PUT /towers/{id}`, `POST/GET /towers/{id}/wings`, `POST/GET /flats`, `POST /flats/bulk`, `GET/PUT /flats/{id}`, `PATCH /flats/{id}/status`, `GET /flats/{id}/occupancy-history` | SOCIETY_ADMIN |
| Residents | `GET/POST /residents`, `GET/PUT /residents/{id}`, `POST /residents/{id}/approve`, `POST /residents/{id}/reject`, `POST /residents/{id}/end`, `GET /residents/me`, `GET/POST /residents/me/family-members`, `DELETE /residents/me/family-members/{id}` | SOCIETY_ADMIN; RESIDENT (owner can request a co-resident/tenant → `PENDING`, admin approves) |
| Guards & gates | `POST/GET /guards`, `GET/PUT /guards/{id}`, `PATCH /guards/{id}/status`, `POST /guards/{id}/assignments`, `GET /guards/{id}/activity`, `POST/GET /gates`, `PUT /gates/{id}`, `PATCH /gates/{id}/status` | SOCIETY_ADMIN |
| Reports & audit | `GET /admin/dashboard`, `GET /reports/visitors`, `GET /reports/deliveries`, `GET /reports/complaints`, `GET /audit-logs` | SOCIETY_ADMIN (own society), SUPER_ADMIN |

---

## 8. Contract mismatches and resolutions

| # | Mismatch | Resolution |
| --- | --- | --- |
| 1 | Master prompt §7 wraps successes in `{success, message, data, timestamp}`; Flutter reads raw bodies and Spring `Page` JSON | **Raw JSON** chosen by the project owner. Error responses follow §7 (`success:false, code, message, timestamp`) plus `errors[]`, which Flutter already parses. |
| 2 | Single visitor/delivery `status` (Flutter) vs approval + movement statuses (master prompt) | Both returned: precise `approvalStatus` + `movementStatus` and the derived Flutter `status` (§5.1). |
| 3 | `/auth/me` (prompt) vs `/users/me` (Flutter) | Both served by the same handler. |
| 4 | `/visitor-invitations` (prompt) vs `/visitors/invites` (Flutter) | Both served. |
| 5 | `DELETE /devices/{id}` (prompt) vs `DELETE /devices/{token}` (Flutter) | Path value is matched against the caller's device id first, then token. |
| 6 | `PATCH /notifications/{id}/read` (prompt) vs `POST` (Flutter) | Both methods mapped. |
| 7 | `/guards/pending-visitors`, `/guards/active-visitors` (prompt) vs `GET /visitors?scope=` (Flutter) | Both served. |
| 8 | Visitor types: Flutter has `CAB`, prompt has *Delivery Agent* | Union accepted: `GUEST, FAMILY, SERVICE, DELIVERY, CAB, OTHER`. |
| 9 | Flutter `AppUserDto` **requires** `guard.gate` (`reqObject('gate')`); a guard with no gate assignment would fail to parse | Backend returns `gate: null` when unassigned and rejects gate operations with 403 `GUARD_NO_GATE`. **Admins must assign a gate before a guard signs in.** Recommended Flutter change (needs approval): make `gate` optional. |
| 10 | `requestOtp` for an unknown number returns 404 `USER_NOT_REGISTERED` (Flutter contract) — enables account enumeration | Kept for UX parity; mitigated by per-mobile and per-IP rate limits. Can be switched to a generic 200 later if the UX changes. |
| 11 | Image URLs: Flutter caches `/users/me` JSON (incl. `avatarUrl`) | URLs are signed and expire (1 h). Flutter should refresh `/users/me` rather than rely on cached URLs. |
| 12 | FCM target: Flutter comments say firebase_messaging 17 reports a *Firebase Installation ID* via `onRegistered` and "the backend should use it as the FCM send target" | Backend sends to the registered value with the Admin SDK `Message.setToken(...)`. **Not verified** — needs a real Firebase project and device. If FCM rejects FIDs as tokens, Flutter must register the FCM registration token instead. |
| 13 | Complaint status changes in Flutter "come from the admin panel" | `PATCH /complaints/{id}/status` for admins; residents may close a `RESOLVED` complaint (optional for Flutter). |
| 14 | Family members are display-only in Flutter | Backend adds `/residents/me/family-members` CRUD; Flutter has no UI for it yet (planned feature). |
| 15 | Flutter has no society-context selector | Not needed for single-society users. Multi-society users get a default context; Flutter may send `X-Society-Id` later. |

---

## 9. End-to-end test checklist

To be executed once both projects run together (`flutter run --dart-define=USE_MOCK=false --dart-define=API_BASE_URL=…`).
**None of these have been executed yet.**

- [ ] OTP login as resident (`9876543210`) → resident dashboard; as guard (`9876500001`) → guard dashboard (dev OTP `123456`).
- [ ] Unregistered number → "not registered" message; inactive account (`9876500009`) → blocked message.
- [ ] Wrong OTP five times → `OTP_LOCKED`; request a new code works after the resend timer.
- [ ] Access token expiry → silent refresh → request retried; revoked session → app signs out.
- [ ] New user (`9876500002`) → profile setup → `PATCH /users/me` → dashboard.
- [ ] Guard toggles duty off → gate actions show "Start your shift…" (`GUARD_OFF_DUTY`).
- [ ] Guard registers walk-in visitor (with and without photo) → resident gets notification → approves → guard approvals screen updates within 5 s → mark entry → resident gets "has entered" → mark exit.
- [ ] Resident rejects → guard sees rejection → entry blocked (403).
- [ ] Resident creates invite → shares 6-digit code → guard verifies code → mark entry → code cannot be reused.
- [ ] Delivery: FOOD (needs approval) and COURIER (auto-approved) flows, leave-at-gate.
- [ ] Domestic staff check-in → resident notified → check-out → attendance history visible to resident.
- [ ] Complaint with photo → admin changes status (Swagger/React) → resident notified and timeline updated.
- [ ] Admin publishes notice → resident notified → unread badge → open → read state persists.
- [ ] Notification list, unread count, mark one / all read, preferences persist.
- [ ] Push on a physical device with real Firebase config (blocked until Firebase is configured).
- [ ] Logout → device token removed → refresh token rejected.

## 10. API base URL per environment (Flutter `API_BASE_URL`)

| Where the app runs | Value |
| --- | --- |
| Android emulator | `http://10.0.2.2:8080` (Flutter default) |
| iOS simulator | `http://localhost:8080` |
| Chrome / Flutter web | `http://localhost:8080` — add the dev server origin (e.g. `http://localhost:5000`) to `CORS_ALLOWED_ORIGINS` |
| Physical device on the same Wi-Fi | `http://<PC LAN IP>:8080` (allow port 8080 in Windows Firewall; debug builds allow HTTP) |
| Production | `https://api.<your-domain>` (HTTPS required by Flutter release builds) |

## 11. How the backend matches what the Flutter code parses (verified against captured responses)

| Flutter parser | Field checks |
| --- | --- |
| `AuthTokens.fromJson` | `accessToken`, `refreshToken`, `expiresIn` (seconds) present |
| `AppUserDto.fromJson` | `id`, `name`, `mobile`, `email`, `role` (`RESIDENT`/`GUARD`), `status` (`ACTIVE`), `profileComplete`, `avatarUrl`; `resident.society{id,name,city}`, `resident.flats[{id,tower,number}]`, `primaryFlatId`, `occupancy`, `familyMembers[{id,name,relation,mobile}]`; `guard.society`, `guard.gate{id,name}`, `guard.shift`, `guard.onDuty`, `guard.permissions[]` |
| `VisitorDto.fromJson` | all fields of §5.2 incl. `history[{title,at,actor,status,description}]`; `status` uses the Flutter enum names |
| `DeliveryDto.fromJson` | `category`, `company`, `agentName`, `agentMobile`, `flat`, `gate`, `status`, `requestedAt`, `requiresApproval`, `decidedAt`, `entryAt`, `exitAt`, `packageCount`, `notes`, `history` |
| `DomesticStaffDto` | `assignedFlats`, `inside`, `lastCheckInAt`, `lastCheckOutAt`, `workingSince` (date `2023-04-01`, parsed by `DateTime.tryParse`), `passNumber`; attendance `staffId`, `staffName`, `category`, `checkInAt`, `checkOutAt`, `gate`, `recordedBy` |
| `ComplaintDto` | `ticketNumber`, `attachments` (URL strings), `adminRemarks`, `flat`, `history` |
| `NoticeDto` | `publishedAt`, `postedBy`, `important`, `read`, `audience` (label), `attachments[{name,url,size}]` |
| `NotificationDto` | `type`, `title`, `body`, `createdAt`, `read`, `referenceId`; unread count `{ "count": n }` |
| `Paginated.fromSpringPage` | `content`, `number`, `size`, `totalElements`, `totalPages`, `last` |
| `mapDioException` | `message`, `code`, `errors[{field,message}]` |
| `ActivityItem.fromJson` | `id`, `kind`, `title`, `subtitle`, `at`, `referenceId`, `status` |
| `ReferenceData.fromJson` | `visitorTypes`, `deliveryCategories[{code,label,requiresApproval}]`, `staffCategories`, `complaintCategories`, `noticeCategories`, `visitPurposes`, `deliveryCompanies`, `maxInviteValidityHours`, `approvalTimeoutSeconds` |

Timestamps: the backend emits UTC instants with up to 6 fractional digits (`2026-11-04T07:01:03.545641Z`), which
`DateTime.tryParse` accepts.
