# API contracts

Single REST API for the Flutter app (residents, guards) and the React admin dashboard.
The live, always-current reference is Swagger UI (`/swagger-ui/index.html`) and `/v3/api-docs`. This document adds
conventions, integration guidance and real request/response samples captured from a running server (dev profile).

## Conventions

| Topic | Contract |
| --- | --- |
| Base URL | `https://<host>/api/v1` (dev: `http://localhost:8080/api/v1`) |
| Auth | `Authorization: Bearer <accessToken>` on everything except `auth/request-otp`, `auth/verify-otp`, `auth/refresh`, `auth/logout` and signed file URLs |
| Content | `application/json`; `multipart/form-data` for uploads (JSON part `data` + file part) |
| Success body | The resource itself — **no envelope** (decision recorded in FLUTTER_BACKEND_API_MAPPING.md §8) |
| Lists | Page object: `content`, `number` (0-based), `size`, `totalElements`, `totalPages`, `first`, `last`, `numberOfElements`, `empty`. Query: `page`, `size` (default 20, max 100); admin lists also accept `sort=field,asc|desc` (whitelisted fields) |
| Errors | `{ "success": false, "code", "message", "errors": [{ "field", "message" }], "timestamp", "path" }` — `message` is safe to show to users |
| Dates | Instants as ISO-8601 UTC (`2026-11-04T07:01:03.545641Z`); report ranges as local dates `from=2026-11-01&to=2026-11-04` (society time zone) |
| Ids | UUID strings |
| Enums | `UPPER_SNAKE_CASE` strings |
| Society context | Derived from the user's memberships. Users in several societies may send `X-Society-Id: <uuid>`; admin endpoints also accept `societyId` |
| Idempotency | Optional `Idempotency-Key: <8-100 chars>` on POST; a retry with the same key replays the first successful response (header `Idempotent-Replayed: true`) for 24 h |
| Rate limits | 240 requests/min per user (configurable); OTP: 30 s cooldown, 5/hour per number, 30/hour per IP → `429` with `Retry-After` where applicable |
| Images | Responses contain signed URLs (`/api/v1/files/{id}?expires=…&signature=…`, valid 1 hour) usable without a bearer token |

### Status codes

| Code | Meaning |
| --- | --- |
| 200 / 201 / 204 | OK / created / done without body |
| 400 | Validation (`VALIDATION_FAILED` with field errors), malformed body, invalid business input (`INVITE_EXPIRED`, `OTP_INVALID`…) |
| 401 | Not signed in, expired or revoked token (`UNAUTHORIZED`, `INVALID_TOKEN`, `SESSION_REVOKED`, `INVALID_REFRESH_TOKEN`, `REFRESH_TOKEN_REUSED`) |
| 403 | Not allowed (`ACCESS_DENIED`, `NOT_FLAT_RESIDENT`, `GUARD_OFF_DUTY`, `GUARD_PERMISSION_DENIED`, `VISITOR_REJECTED`, `USER_INACTIVE`…) |
| 404 | Not found — **also used for records of another society** |
| 409 | State conflict (`VISITOR_ALREADY_DECIDED`, `VISITOR_ALREADY_INSIDE`, `VISITOR_ALREADY_EXITED`, `STAFF_ALREADY_INSIDE`, `INVALID_STATUS_TRANSITION`, `CONCURRENT_UPDATE`…) |
| 413 / 415 | File too large / unsupported file type |
| 429 | Rate limited (`RATE_LIMITED`, `OTP_RATE_LIMITED`) |
| 5xx | `INTERNAL_ERROR` (details only in server logs), `SERVICE_UNAVAILABLE` (e.g. Redis down during OTP) |

The full list of codes is in `src/main/java/com/mygateapp/exception/ErrorCode.java`.

## Token handling (both clients)

1. `POST /auth/request-otp` `{ mobile, countryCode }` → `{ requestId, resendAfterSeconds, expiresInSeconds }`.
2. `POST /auth/verify-otp` `{ mobile, otp, requestId }` → `{ accessToken, refreshToken, tokenType, expiresIn, refreshExpiresIn, user }`.
3. Send the access token as a bearer header. It expires after `expiresIn` seconds (15 min).
4. On `401`, call `POST /auth/refresh` `{ refreshToken }` **once** (share one call between concurrent requests), store
   **both** new tokens (the refresh token rotates), then retry. If refresh returns any 4xx, sign the user out.
   Never reuse an old refresh token: doing so revokes the whole session (`REFRESH_TOKEN_REUSED`).
5. Logout: `POST /auth/logout` `{ refreshToken }` (no bearer needed), then delete the push device
   (`DELETE /devices/{token}`) and local credentials.

The Flutter `AuthInterceptor` (lib/core/network/auth_interceptor.dart) already implements exactly this.

## Flutter integration

* Run with `--dart-define=USE_MOCK=false --dart-define=API_BASE_URL=<url>` (see README for emulator/device URLs).
* All repository implementations (`*RepositoryImpl`) already call these endpoints; mock repositories are only used
  when `USE_MOCK=true`. The full mapping, mismatches and test checklist are in
  [FLUTTER_BACKEND_API_MAPPING.md](../FLUTTER_BACKEND_API_MAPPING.md).
* Use the dev demo accounts (README) — they have the same mobile numbers as the Flutter mock data.

## React admin integration

* Add the dashboard origin to `CORS_ALLOWED_ORIGINS` (e.g. `http://localhost:5173`).
* Admin users sign in with the same OTP flow (`9876500100` in dev). `GET /users/me` returns `role: SOCIETY_ADMIN`,
  `roles` and `memberships` (society id + name) to build a society switcher; send `X-Society-Id` or `societyId`.
* Admin endpoints: societies, settings, towers/flats (bulk create), residents (search/approve/end/transfer), guards and
  gates, domestic staff, complaints (status, comments, assignment), notices (draft/publish/attachments), dashboards,
  reports and the audit log. Platform admins additionally use `/platform/*` and society/admin management.

## Endpoint catalogue

Generated from the running application's OpenAPI document (125 operations on 96 paths). `POST /visitors` and
`POST /complaints` accept both JSON and multipart (`data` + `photo` / `image`).

### Authentication

| Method | Path | Summary |
| --- | --- | --- |
| POST | `/api/v1/auth/logout` | Revoke the session of the given refresh token (idempotent) |
| GET | `/api/v1/auth/me` | Current user (alias of GET /api/v1/users/me) |
| POST | `/api/v1/auth/refresh` | Exchange a refresh token for a new token pair (the refresh token is rotated) |
| POST | `/api/v1/auth/request-otp` | Send an OTP to a registered mobile number |
| POST | `/api/v1/auth/verify-otp` | Verify the OTP and receive an access + refresh token pair |

### Users

| Method | Path | Summary |
| --- | --- | --- |
| PATCH | `/api/v1/users/{id}/status` | Activate or deactivate a user account (platform admin). Deactivation ends all sessions. |
| GET | `/api/v1/users/me` | Current user with role, society, flats (resident) or gate and permissions (guard) |
| PATCH | `/api/v1/users/me` | Update own name and email. Society, flats and role cannot be changed here. |

### Societies

| Method | Path | Summary |
| --- | --- | --- |
| GET | `/api/v1/societies` | List societies: all for platform admins, assigned societies for society admins |
| POST | `/api/v1/societies` | Create a society (platform admin). Society codes are unique. |
| GET | `/api/v1/societies/{id}` |  |
| PUT | `/api/v1/societies/{id}` | Update society details (the code cannot be changed) |
| GET | `/api/v1/societies/{id}/admins` |  |
| POST | `/api/v1/societies/{id}/admins` | Make a person (by mobile number) an administrator of the society; creates the user if needed |
| DELETE | `/api/v1/societies/{id}/admins/{userId}` |  |
| GET | `/api/v1/societies/{id}/settings` |  |
| PUT | `/api/v1/societies/{id}/settings` | Update visitor / delivery rules of the society |
| PATCH | `/api/v1/societies/{id}/status` | Activate / deactivate a society and manage its subscription. Members of an inactive society lose access. |

### Platform

| Method | Path | Summary |
| --- | --- | --- |
| GET | `/api/v1/platform/settings` |  |
| PUT | `/api/v1/platform/settings` | Create or update platform settings (key -> value, max 1000 characters per value) |
| GET | `/api/v1/platform/stats` | Society and member counts across the platform |

### Reference data

| Method | Path | Summary |
| --- | --- | --- |
| GET | `/api/v1/config/lookups` | Visitor types, delivery categories (with the society's approval rules), staff / complaint / notice categories, visit purposes and invite limits for the caller's society |

### Towers & flats

| Method | Path | Summary |
| --- | --- | --- |
| GET | `/api/v1/flats` |  |
| POST | `/api/v1/flats` | Register a flat. Flat numbers are unique within the tower (or wing). |
| GET | `/api/v1/flats/{id}` | Flat details. Admins also get the current occupants; residents may read their own flat. |
| PUT | `/api/v1/flats/{id}` |  |
| GET | `/api/v1/flats/{id}/occupancy-history` |  |
| PATCH | `/api/v1/flats/{id}/status` |  |
| POST | `/api/v1/flats/bulk` | Create many flats at once (floor x units); existing numbers are skipped |
| GET | `/api/v1/society/flats` | Active flats of the caller's society (destination picker in the guard app). Plain JSON array. |
| GET | `/api/v1/towers` | Towers (with wings) of a society |
| POST | `/api/v1/towers` |  |
| PUT | `/api/v1/towers/{id}` |  |
| GET | `/api/v1/towers/{id}/wings` |  |
| POST | `/api/v1/towers/{id}/wings` |  |

### Residents

| Method | Path | Summary |
| --- | --- | --- |
| GET | `/api/v1/residents` | Search residents of a society (name, mobile or flat number) with pagination |
| POST | `/api/v1/residents` | Register a resident for a flat |
| GET | `/api/v1/residents/{id}` | One resident membership. Admins of the society or the resident themself. |
| PUT | `/api/v1/residents/{id}` |  |
| POST | `/api/v1/residents/{id}/approve` |  |
| POST | `/api/v1/residents/{id}/end` | End an active membership (move-out). Kept as occupancy history. |
| POST | `/api/v1/residents/{id}/reject` |  |
| POST | `/api/v1/residents/{id}/transfer` | Move a resident to another flat of the same society |
| GET | `/api/v1/residents/me` | The caller's flats (active and pending requests) with family members |
| GET | `/api/v1/residents/me/family-members` |  |
| POST | `/api/v1/residents/me/family-members` | Add a household member without a login (shown in the resident profile) |
| DELETE | `/api/v1/residents/me/family-members/{id}` |  |

### Guards & gates

| Method | Path | Summary |
| --- | --- | --- |
| GET | `/api/v1/gates` |  |
| POST | `/api/v1/gates` |  |
| PUT | `/api/v1/gates/{id}` |  |
| PATCH | `/api/v1/gates/{id}/status` |  |
| GET | `/api/v1/guards` |  |
| POST | `/api/v1/guards` | Register a guard in a society (creates the user by mobile number if needed) |
| GET | `/api/v1/guards/{id}` | Guard details (admins, or the guard themself) |
| PUT | `/api/v1/guards/{id}` |  |
| GET | `/api/v1/guards/{id}/activity` | Everything the guard recorded: visitor/delivery events, staff attendance and duty periods |
| GET | `/api/v1/guards/{id}/assignments` |  |
| POST | `/api/v1/guards/{id}/assignments` | Assign the guard to a gate and shift (replaces the current assignment) |
| PATCH | `/api/v1/guards/{id}/status` | Activate / deactivate a guard. Inactive guards lose all gate access. |
| POST | `/api/v1/guards/me/duty` | Start or end the caller's shift. Returns the refreshed /users/me. |

### Visitors

| Method | Path | Summary |
| --- | --- | --- |
| GET | `/api/v1/guards/active-visitors` | Visitors currently inside (same as GET /visitors?scope=INSIDE) |
| GET | `/api/v1/guards/pending-visitors` | Walk-ins waiting for a resident decision (same as GET /visitors?scope=PENDING) |
| GET | `/api/v1/visitor-invitations` | Invitations (pre-approved visitors) visible to the caller |
| POST | `/api/v1/visitor-invitations` | Resident pre-approves a visitor for a time window; returns a 6-digit pass code |
| GET | `/api/v1/visitors` | List visitors. Residents get their flats only; guards and admins the whole society. |
| POST | `/api/v1/visitors` | Guard registers a walk-in visitor; the flat's residents are asked to approve |
| GET | `/api/v1/visitors/{id}` |  |
| POST | `/api/v1/visitors/{id}/approve` |  |
| POST | `/api/v1/visitors/{id}/cancel` | Guard: cancel a pending walk-in. Resident: cancel an upcoming invite. |
| POST | `/api/v1/visitors/{id}/entry` | Record entry. Only approved visitors, or invites inside their window. Duplicate -> 409. |
| POST | `/api/v1/visitors/{id}/exit` | Record exit. Only visitors inside; second call -> 409 VISITOR_ALREADY_EXITED. |
| POST | `/api/v1/visitors/{id}/reject` |  |
| POST | `/api/v1/visitors/invites` | Resident pre-approves a visitor for a time window; returns a 6-digit pass code |
| POST | `/api/v1/visitors/verify-pass` | Find a valid invitation by its 6-digit pass code |

### Deliveries

| Method | Path | Summary |
| --- | --- | --- |
| GET | `/api/v1/deliveries` | List deliveries. scope: PENDING (awaiting resident), ACTIVE (approved or inside), HISTORY (default) |
| POST | `/api/v1/deliveries` | Guard registers a delivery; the backend decides PENDING or APPROVED from the category rule |
| GET | `/api/v1/deliveries/{id}` |  |
| POST | `/api/v1/deliveries/{id}/approve` |  |
| POST | `/api/v1/deliveries/{id}/entry` |  |
| POST | `/api/v1/deliveries/{id}/exit` |  |
| POST | `/api/v1/deliveries/{id}/leave-at-gate` | Resident asks security to keep the parcel at the gate |
| POST | `/api/v1/deliveries/{id}/reject` |  |

### Domestic staff

| Method | Path | Summary |
| --- | --- | --- |
| GET | `/api/v1/domestic-staff` | Staff list: residents get staff assigned to their flats; guards and admins the society |
| POST | `/api/v1/domestic-staff` |  |
| GET | `/api/v1/domestic-staff/{id}` |  |
| PUT | `/api/v1/domestic-staff/{id}` | Update a staff member; flatIds replaces the flat assignments when provided |
| GET | `/api/v1/domestic-staff/{id}/attendance` |  |
| POST | `/api/v1/domestic-staff/{id}/check-in` | Record arrival (guard on duty with STAFF_ATTENDANCE). Notifies residents of the assigned flats. |
| POST | `/api/v1/domestic-staff/{id}/check-out` |  |
| POST | `/api/v1/domestic-staff/{id}/photo` | Upload the staff photo (multipart part 'photo', JPEG/PNG/WebP, max 5 MB) |
| PATCH | `/api/v1/domestic-staff/{id}/status` |  |
| GET | `/api/v1/domestic-staff/attendance` | Attendance history (Flutter path), optionally for one staff member |

### Complaints

| Method | Path | Summary |
| --- | --- | --- |
| GET | `/api/v1/complaints` | Residents: complaints of their flats. Admins: all complaints of the society. |
| POST | `/api/v1/complaints` | Raise a complaint with a photo: multipart part 'data' (JSON) + part 'image' |
| GET | `/api/v1/complaints/{id}` |  |
| PUT | `/api/v1/complaints/{id}` | Edit an open complaint (resident) or assign / add remarks (admin) |
| POST | `/api/v1/complaints/{id}/comments` |  |
| PATCH | `/api/v1/complaints/{id}/status` | Change status (admin). Residents may only close a RESOLVED complaint. Notifies the flat. |

### Notices

| Method | Path | Summary |
| --- | --- | --- |
| GET | `/api/v1/notices` | Published notices for the caller's society and audience; admins may add includeDrafts=true |
| POST | `/api/v1/notices` | Create a notice (draft, or published immediately with publish=true) |
| DELETE | `/api/v1/notices/{id}` |  |
| GET | `/api/v1/notices/{id}` |  |
| PUT | `/api/v1/notices/{id}` |  |
| POST | `/api/v1/notices/{id}/attachments` | Attach a PDF or image (multipart part 'file', optional 'name') |
| POST | `/api/v1/notices/{id}/publish` | Publish a draft and notify its audience |
| POST | `/api/v1/notices/{id}/read` | Mark a notice as read for the caller (idempotent) |

### Notifications

| Method | Path | Summary |
| --- | --- | --- |
| POST | `/api/v1/devices` | Register this device for push notifications |
| DELETE | `/api/v1/devices/{idOrToken}` | Unregister a device by id or by token |
| GET | `/api/v1/notifications` | The caller's notifications, newest first |
| PATCH | `/api/v1/notifications/{id}/read` | Mark one notification as read (POST for the Flutter app, PATCH per the API spec) |
| POST | `/api/v1/notifications/{id}/read` | Mark one notification as read (POST for the Flutter app, PATCH per the API spec) |
| POST | `/api/v1/notifications/read-all` |  |
| GET | `/api/v1/notifications/unread-count` |  |
| GET | `/api/v1/users/me/notification-preferences` | Per-type push preferences. VISITOR_APPROVAL_REQUEST is always enabled. |
| PUT | `/api/v1/users/me/notification-preferences` |  |

### Dashboards & reports

| Method | Path | Summary |
| --- | --- | --- |
| GET | `/api/v1/admin/dashboard` | Society metrics for the admin panel |
| GET | `/api/v1/guards/activity` | Gate activity of the guard's society; kind = VISITOR / DELIVERY / STAFF |
| GET | `/api/v1/guards/dashboard` | Guard home: today's gate counts and recent gate activity of the society |
| GET | `/api/v1/reports/complaints` |  |
| GET | `/api/v1/reports/deliveries` |  |
| GET | `/api/v1/reports/visitors` | Visitor report for a date range (society time zone, inclusive, default last 7 days) |
| GET | `/api/v1/residents/activity` | Activity of the resident's flats; kind = VISITOR / DELIVERY / STAFF / COMPLAINT |
| GET | `/api/v1/residents/dashboard` | Resident home: pending approvals, today's counts, latest notices and recent activity |

### Audit logs

| Method | Path | Summary |
| --- | --- | --- |
| GET | `/api/v1/audit-logs` | Search the audit log. Society admins see their society; super admins may omit societyId to see all. |

### Files

| Method | Path | Summary |
| --- | --- | --- |
| GET | `/api/v1/files/{id}` | Download a file through a signed URL returned by another endpoint (no bearer token needed) |

## Samples (captured from a running server)

Ids are random UUIDs from a dev database. Demo accounts: see README.

### Request OTP

`POST /api/v1/auth/request-otp`

```json
{
  "mobile": "9876500004",
  "countryCode": "+91"
}
```

Response `200`

```json
{
  "requestId": "a09cdf9a-6649-4eca-8053-c574a71939aa",
  "resendAfterSeconds": 30,
  "expiresInSeconds": 300
}
```

### Verify OTP

Tokens shortened here.

`POST /api/v1/auth/verify-otp`

```json
{
  "mobile": "9876500004",
  "otp": "123456",
  "requestId": "a09cdf9a-6649-4eca-8053-c574a71939aa"
}
```

Response `200`

```json
{
  "accessToken": "eyJ0eXAiOiJKV1QiLCJhbGci…",
  "refreshToken": "5EOonol0oxHl6SBeW2W3WfZ3…",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshExpiresIn": 2592000,
  "user": "… same as GET /users/me …"
}
```

### OTP resend too early

`POST /api/v1/auth/request-otp`

```json
{
  "mobile": "9876500004"
}
```

Response `429`

```json
{
  "success": false,
  "code": "OTP_RATE_LIMITED",
  "message": "Please wait 30 seconds before requesting a new code.",
  "errors": [],
  "timestamp": "2026-11-04T07:01:03.205055500Z",
  "path": "/api/v1/auth/request-otp"
}
```

### Current user (resident)

`GET /api/v1/users/me`

Response `200`

```json
{
  "id": "3ba545f3-bdf5-450d-8809-e8877845610e",
  "name": "Aarav Mehta",
  "mobile": "9876543210",
  "countryCode": "+91",
  "email": "aarav.mehta@example.com",
  "role": "RESIDENT",
  "roles": [
    "RESIDENT"
  ],
  "status": "ACTIVE",
  "profileComplete": true,
  "avatarUrl": null,
  "activeSocietyId": "f45efde6-558c-4cc0-89be-b8fa1370d453",
  "resident": {
    "society": {
      "id": "f45efde6-558c-4cc0-89be-b8fa1370d453",
      "name": "Greenwood Residency",
      "city": "Pune"
    },
    "flats": [
      {
        "id": "58885daf-f857-488d-b0f5-5adfaac6a010",
        "tower": "A",
        "wing": null,
        "number": "1204",
        "label": "A-1204"
      }
    ],
    "primaryFlatId": "58885daf-f857-488d-b0f5-5adfaac6a010",
    "occupancy": "OWNER",
    "familyMembers": [
      {
        "id": "9114e84e-65ba-4c2b-9770-eb796c4f217c",
        "name": "Priya Mehta",
        "relation": "Spouse",
        "mobile": "9876543211"
      },
      {
        "id": "c0b9ab8b-4976-4b64-b9cc-5d8e5e406c99",
        "name": "Kabir Mehta",
        "relation": "Son",
        "mobile": null
      }
    ]
  },
  "guard": null,
  "memberships": [
    {
      "societyId": "f45efde6-558c-4cc0-89be-b8fa1370d453",
      "societyName": "Greenwood Residency",
      "role": "RESIDENT",
      "status": "ACTIVE"
    }
  ]
}
```

### Current user (guard)

`GET /api/v1/users/me`

Response `200`

```json
{
  "id": "7731b156-5a4e-428a-b1c4-a4a8707cae52",
  "name": "Ramesh Yadav",
  "mobile": "9876500001",
  "countryCode": "+91",
  "email": null,
  "role": "GUARD",
  "roles": [
    "GUARD"
  ],
  "status": "ACTIVE",
  "profileComplete": true,
  "avatarUrl": null,
  "activeSocietyId": "f45efde6-558c-4cc0-89be-b8fa1370d453",
  "resident": null,
  "guard": {
    "id": "fd550594-f64a-414b-8c6d-b1df4f3d6682",
    "society": {
      "id": "f45efde6-558c-4cc0-89be-b8fa1370d453",
      "name": "Greenwood Residency",
      "city": "Pune"
    },
    "gate": {
      "id": "dab27e86-f470-4475-905a-77c187ea3c7b",
      "name": "Main Gate"
    },
    "shift": "Day shift · 08:00 – 20:00",
    "onDuty": true,
    "permissions": [
      "VISITOR_ENTRY",
      "DELIVERY_ENTRY",
      "STAFF_ATTENDANCE"
    ]
  },
  "memberships": [
    {
      "societyId": "f45efde6-558c-4cc0-89be-b8fa1370d453",
      "societyName": "Greenwood Residency",
      "role": "GUARD",
      "status": "ACTIVE"
    }
  ]
}
```

### Guard registers a walk-in visitor

`POST /api/v1/visitors`

```json
{
  "name": "Vikram Iyer",
  "mobile": "9822011122",
  "type": "GUEST",
  "flatId": "58885daf-f857-488d-b0f5-5adfaac6a010",
  "purpose": "Family visit",
  "vehicleNumber": "MH12AB1234"
}
```

Response `201`

```json
{
  "id": "87903ac0-e93e-4b66-bb67-01c8af64908e",
  "name": "Vikram Iyer",
  "mobile": "9822011122",
  "type": "GUEST",
  "photoUrl": null,
  "flat": {
    "id": "58885daf-f857-488d-b0f5-5adfaac6a010",
    "tower": "A",
    "wing": null,
    "number": "1204",
    "label": "A-1204"
  },
  "gate": {
    "id": "dab27e86-f470-4475-905a-77c187ea3c7b",
    "name": "Main Gate"
  },
  "purpose": "Family visit",
  "status": "PENDING",
  "approvalStatus": "PENDING",
  "movementStatus": "NOT_ENTERED",
  "source": "WALK_IN",
  "requestedAt": "2026-11-04T07:01:03.459699900Z",
  "decidedAt": null,
  "decidedBy": null,
  "rejectionReason": null,
  "entryAt": null,
  "exitAt": null,
  "vehicleNumber": "MH12AB1234",
  "createdBy": "Ramesh Yadav",
  "passCode": null,
  "validFrom": null,
  "validUntil": null,
  "guestCount": 1,
  "notes": null,
  "history": [
    {
      "title": "Request created at Main Gate",
      "at": "2026-11-04T07:01:03.459699900Z",
      "actor": "Ramesh Yadav",
      "status": "PENDING",
      "description": null
    }
  ]
}
```

### Guard records entry

After the resident approved (`POST /visitors/{id}/approve`).

`POST /api/v1/visitors/87903ac0-e93e-4b66-bb67-01c8af64908e/entry`

Response `200`

```json
{
  "id": "87903ac0-e93e-4b66-bb67-01c8af64908e",
  "name": "Vikram Iyer",
  "mobile": "9822011122",
  "type": "GUEST",
  "photoUrl": null,
  "flat": {
    "id": "58885daf-f857-488d-b0f5-5adfaac6a010",
    "tower": "A",
    "wing": null,
    "number": "1204",
    "label": "A-1204"
  },
  "gate": {
    "id": "dab27e86-f470-4475-905a-77c187ea3c7b",
    "name": "Main Gate"
  },
  "purpose": "Family visit",
  "status": "CHECKED_IN",
  "approvalStatus": "APPROVED",
  "movementStatus": "INSIDE",
  "source": "WALK_IN",
  "requestedAt": "2026-11-04T07:01:03.459700Z",
  "decidedAt": "2026-11-04T07:01:03.494467Z",
  "decidedBy": "Aarav Mehta",
  "rejectionReason": null,
  "entryAt": "2026-11-04T07:01:03.545641300Z",
  "exitAt": null,
  "vehicleNumber": "MH12AB1234",
  "createdBy": "Ramesh Yadav",
  "passCode": null,
  "validFrom": null,
  "validUntil": null,
  "guestCount": 1,
  "notes": null,
  "history": [
    {
      "title": "Request created at Main Gate",
      "at": "2026-11-04T07:01:03.459700Z",
      "actor": "Ramesh Yadav",
      "status": "PENDING",
      "description": null
    },
    {
      "title": "Approved by resident",
      "at": "2026-11-04T07:01:03.494467Z",
      "actor": "Aarav Mehta",
      "status": "APPROVED",
      "description": null
    },
    {
      "title": "Entered the premises",
      "at": "2026-11-04T07:01:03.550017900Z",
      "actor": "Ramesh Yadav",
      "status": "CHECKED_IN",
      "description": null
    }
  ]
}
```

### Duplicate entry

`POST /api/v1/visitors/87903ac0-e93e-4b66-bb67-01c8af64908e/entry`

Response `409`

```json
{
  "success": false,
  "code": "VISITOR_ALREADY_INSIDE",
  "message": "Entry has already been recorded for this visitor.",
  "errors": [],
  "timestamp": "2026-11-04T07:01:03.586311400Z",
  "path": "/api/v1/visitors/87903ac0-e93e-4b66-bb67-01c8af64908e/entry"
}
```

### Resident invites a visitor

`POST /api/v1/visitors/invites`

```json
{
  "name": "Meera Nair",
  "mobile": "9811122233",
  "type": "FAMILY",
  "flatId": "58885daf-f857-488d-b0f5-5adfaac6a010",
  "purpose": "Dinner",
  "validFrom": "2026-11-04T07:01:03.587Z",
  "validUntil": "2026-11-04T11:01:03.587Z",
  "guestCount": 3
}
```

Response `201`

```json
{
  "id": "01fcf164-bcd4-48b8-8a60-4f6c0a83e612",
  "name": "Meera Nair",
  "mobile": "9811122233",
  "type": "FAMILY",
  "photoUrl": null,
  "flat": {
    "id": "58885daf-f857-488d-b0f5-5adfaac6a010",
    "tower": "A",
    "wing": null,
    "number": "1204",
    "label": "A-1204"
  },
  "gate": null,
  "purpose": "Dinner",
  "status": "PRE_APPROVED",
  "approvalStatus": "APPROVED",
  "movementStatus": "NOT_ENTERED",
  "source": "INVITATION",
  "requestedAt": "2026-11-04T07:01:03.621405Z",
  "decidedAt": "2026-11-04T07:01:03.621405Z",
  "decidedBy": "Aarav Mehta",
  "rejectionReason": null,
  "entryAt": null,
  "exitAt": null,
  "vehicleNumber": null,
  "createdBy": "Aarav Mehta",
  "passCode": "948984",
  "validFrom": "2026-11-04T07:01:03.587Z",
  "validUntil": "2026-11-04T11:01:03.587Z",
  "guestCount": 3,
  "notes": null,
  "history": [
    {
      "title": "Invite created",
      "at": "2026-11-04T07:01:03.631758500Z",
      "actor": "Aarav Mehta",
      "status": "PRE_APPROVED",
      "description": null
    }
  ]
}
```

### Visitor list (page)

Only the first item shown.

`GET /api/v1/visitors?scope=INSIDE&size=1`

Response `200`

```json
{
  "content": [
    {
      "id": "87903ac0-e93e-4b66-bb67-01c8af64908e",
      "name": "Vikram Iyer",
      "mobile": "9822011122",
      "type": "GUEST",
      "photoUrl": null,
      "flat": {
        "id": "58885daf-f857-488d-b0f5-5adfaac6a010",
        "tower": "A",
        "wing": null,
        "number": "1204",
        "label": "A-1204"
      },
      "gate": {
        "id": "dab27e86-f470-4475-905a-77c187ea3c7b",
        "name": "Main Gate"
      },
      "purpose": "Family visit",
      "status": "CHECKED_IN",
      "approvalStatus": "APPROVED",
      "movementStatus": "INSIDE",
      "source": "WALK_IN",
      "requestedAt": "2026-11-04T07:01:03.459700Z",
      "decidedAt": "2026-11-04T07:01:03.494467Z",
      "decidedBy": "Aarav Mehta",
      "rejectionReason": null,
      "entryAt": "2026-11-04T07:01:03.545641Z",
      "exitAt": null,
      "vehicleNumber": "MH12AB1234",
      "createdBy": "Ramesh Yadav",
      "passCode": null,
      "validFrom": null,
      "validUntil": null,
      "guestCount": 1,
      "notes": null,
      "history": []
    }
  ],
  "number": 0,
  "size": 1,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true,
  "numberOfElements": 1,
  "empty": false
}
```

### Guard registers a delivery

`POST /api/v1/deliveries`

```json
{
  "category": "FOOD",
  "company": "QuickBite",
  "agentName": "Arjun",
  "agentMobile": "9000011111",
  "flatId": "58885daf-f857-488d-b0f5-5adfaac6a010",
  "packageCount": 1
}
```

Response `201`

```json
{
  "id": "6120a9a2-3055-479f-9ddc-1b739a6ccb31",
  "category": "FOOD",
  "company": "QuickBite",
  "agentName": "Arjun",
  "agentMobile": "9000011111",
  "flat": {
    "id": "58885daf-f857-488d-b0f5-5adfaac6a010",
    "tower": "A",
    "wing": null,
    "number": "1204",
    "label": "A-1204"
  },
  "gate": {
    "id": "dab27e86-f470-4475-905a-77c187ea3c7b",
    "name": "Main Gate"
  },
  "status": "PENDING",
  "approvalStatus": "PENDING",
  "movementStatus": "NOT_ENTERED",
  "requestedAt": "2026-11-04T07:01:03.696991700Z",
  "requiresApproval": true,
  "decidedAt": null,
  "decidedBy": null,
  "entryAt": null,
  "exitAt": null,
  "packageCount": 1,
  "notes": null,
  "history": [
    {
      "title": "Arrived at Main Gate",
      "at": "2026-11-04T07:01:03.698066900Z",
      "actor": "Ramesh Yadav",
      "status": "PENDING",
      "description": null
    }
  ]
}
```

### Staff check-in

`POST /api/v1/domestic-staff/557efad1-9b22-4c9c-8817-a7b47d8451ec/check-in`

Response `200`

```json
{
  "id": "557efad1-9b22-4c9c-8817-a7b47d8451ec",
  "name": "Ramu Prasad",
  "mobile": "9123456781",
  "category": "DRIVER",
  "photoUrl": null,
  "assignedFlats": [
    {
      "id": "58885daf-f857-488d-b0f5-5adfaac6a010",
      "tower": "A",
      "wing": null,
      "number": "1204",
      "label": "A-1204"
    }
  ],
  "inside": true,
  "lastCheckInAt": "2026-11-04T07:01:03.762573800Z",
  "lastCheckOutAt": null,
  "workingSince": "2023-04-01",
  "passNumber": "GR-S-1022",
  "status": "ACTIVE"
}
```

### Resident raises a complaint

`POST /api/v1/complaints`

```json
{
  "category": "LIFT",
  "title": "Lift stuck on floor 12",
  "description": "The lift in Tower A stops between floors 11 and 12.",
  "flatId": "58885daf-f857-488d-b0f5-5adfaac6a010"
}
```

Response `201`

```json
{
  "id": "31bff159-bb46-4928-ae32-01f99a099e2d",
  "ticketNumber": "CMP-1001",
  "category": "LIFT",
  "title": "Lift stuck on floor 12",
  "description": "The lift in Tower A stops between floors 11 and 12.",
  "attachments": [],
  "createdAt": "2026-11-04T07:01:03.826096Z",
  "updatedAt": "2026-11-04T07:01:03.826096Z",
  "status": "OPEN",
  "adminRemarks": null,
  "flat": {
    "id": "58885daf-f857-488d-b0f5-5adfaac6a010",
    "tower": "A",
    "wing": null,
    "number": "1204",
    "label": "A-1204"
  },
  "raisedBy": {
    "id": "3ba545f3-bdf5-450d-8809-e8877845610e",
    "name": "Aarav Mehta"
  },
  "assignedTo": null,
  "resolvedAt": null,
  "closedAt": null,
  "history": [
    {
      "title": "Complaint raised",
      "at": "2026-11-04T07:01:03.817767900Z",
      "actor": "Aarav Mehta",
      "status": "OPEN",
      "description": null
    }
  ],
  "comments": []
}
```

### Admin changes complaint status

`PATCH /api/v1/complaints/31bff159-bb46-4928-ae32-01f99a099e2d/status`

```json
{
  "status": "IN_PROGRESS",
  "remarks": "Technician assigned"
}
```

Response `200`

```json
{
  "id": "31bff159-bb46-4928-ae32-01f99a099e2d",
  "ticketNumber": "CMP-1001",
  "category": "LIFT",
  "title": "Lift stuck on floor 12",
  "description": "The lift in Tower A stops between floors 11 and 12.",
  "attachments": [],
  "createdAt": "2026-11-04T07:01:03.826096Z",
  "updatedAt": "2026-11-04T07:01:03.881051Z",
  "status": "IN_PROGRESS",
  "adminRemarks": "Technician assigned",
  "flat": {
    "id": "58885daf-f857-488d-b0f5-5adfaac6a010",
    "tower": "A",
    "wing": null,
    "number": "1204",
    "label": "A-1204"
  },
  "raisedBy": {
    "id": "3ba545f3-bdf5-450d-8809-e8877845610e",
    "name": "Aarav Mehta"
  },
  "assignedTo": null,
  "resolvedAt": null,
  "closedAt": null,
  "history": [
    {
      "title": "Complaint raised",
      "at": "2026-11-04T07:01:03.817768Z",
      "actor": "Aarav Mehta",
      "status": "OPEN",
      "description": null
    },
    {
      "title": "In progress",
      "at": "2026-11-04T07:01:03.872774500Z",
      "actor": "Society Office",
      "status": "IN_PROGRESS",
      "description": "Technician assigned"
    }
  ],
  "comments": []
}
```

### Notifications

Only the first item shown.

`GET /api/v1/notifications?size=2`

Response `200`

```json
{
  "content": [
    {
      "id": "b27d7c0a-cea6-4172-9539-c1585512e827",
      "type": "COMPLAINT_UPDATE",
      "title": "Complaint CMP-1001 is in progress",
      "body": "Lift stuck on floor 12",
      "createdAt": "2026-11-04T07:01:03.875466Z",
      "read": false,
      "referenceId": "31bff159-bb46-4928-ae32-01f99a099e2d",
      "referenceType": "COMPLAINT"
    }
  ],
  "number": 0,
  "size": 2,
  "totalElements": 11,
  "totalPages": 6,
  "first": true,
  "last": false,
  "numberOfElements": 2,
  "empty": false
}
```

### Guard dashboard

Only the first activity item shown.

`GET /api/v1/guards/dashboard`

Response `200`

```json
{
  "todayVisitorCount": 4,
  "pendingApprovalCount": 0,
  "approvedWaitingCount": 1,
  "insideNowCount": 1,
  "deliveriesTodayCount": 1,
  "staffInsideCount": 1,
  "recentActivity": [
    {
      "id": "STAFF:a0fa964f-9ae0-4016-abe6-1e08925aa71f",
      "kind": "STAFF",
      "title": "Ramu Prasad checked in",
      "subtitle": "Driver",
      "at": "2026-11-04T07:01:03.762574Z",
      "referenceId": "557efad1-9b22-4c9c-8817-a7b47d8451ec",
      "status": "INSIDE"
    }
  ]
}
```

### Admin dashboard

`GET /api/v1/admin/dashboard`

Response `200`

```json
{
  "societyId": "f45efde6-558c-4cc0-89be-b8fa1370d453",
  "date": "2026-11-04",
  "visitorsToday": 4,
  "pendingVisitorApprovals": 0,
  "visitorsInside": 1,
  "deliveriesToday": 1,
  "pendingDeliveryApprovals": 1,
  "openComplaints": 0,
  "inProgressComplaints": 1,
  "pendingResidentRequests": 0,
  "activeResidents": 4,
  "activeGuards": 2,
  "guardsOnDuty": 1,
  "staffInside": 1,
  "totalFlats": 168,
  "occupiedFlats": 4,
  "generatedAt": "2026-11-04T07:01:04.078914300Z"
}
```

### Validation error

`POST /api/v1/visitors`

```json
{
  "name": "X",
  "mobile": "123",
  "type": "GUEST",
  "purpose": ""
}
```

Response `400`

```json
{
  "success": false,
  "code": "VALIDATION_FAILED",
  "message": "Please check the highlighted fields.",
  "errors": [
    {
      "field": "flatId",
      "message": "Select a destination flat"
    },
    {
      "field": "name",
      "message": "Name must be 2-60 characters"
    },
    {
      "field": "purpose",
      "message": "Purpose is required"
    }
  ],
  "timestamp": "2026-11-04T07:01:04.093916100Z",
  "path": "/api/v1/visitors"
}
```
