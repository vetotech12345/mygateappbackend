# MyGateApp Backend (Phase 1)

Spring Boot backend for **MyGateApp**: one REST API for the Flutter app (residents and security guards) and the
React society-admin dashboard. Multi-society, PostgreSQL, Redis, JWT + refresh tokens, Firebase push.

| | |
| --- | --- |
| Stack | Java 21 · Spring Boot 4.1.2 · Spring Security 7 (OAuth2 resource server, HS256 JWT) · Spring Data JPA / Hibernate 7 · Flyway · PostgreSQL 17 · Redis 7 · springdoc-openapi 3 · Firebase Admin SDK 9 |
| Architecture | Modular monolith, package per feature (`com.mygateapp.<module>`) — see [docs/architecture.md](docs/architecture.md) |
| Database | 43 tables, 8 Flyway migrations — see [docs/database.md](docs/database.md) |
| API | 96 paths under `/api/v1` — see [docs/api-contracts.md](docs/api-contracts.md) and Swagger UI |
| Flutter contract | [FLUTTER_BACKEND_API_MAPPING.md](FLUTTER_BACKEND_API_MAPPING.md) |

## Status

| Area | State |
| --- | --- |
| All 12 Phase 1 modules (auth, societies, structure, residents, guards/gates, visitors, deliveries, domestic staff, complaints, notices, notifications, reports/audit) | Implemented and covered by automated tests |
| Automated tests | **117 tests** (JUnit 5, Mockito, Spring Boot Test, Testcontainers PostgreSQL + Redis) — all passing |
| Local run | Verified with `mvnw spring-boot:run` and with the Docker image (`docker compose --profile app`): migrations, Redis, demo data and a 26-step live smoke test against the running server |
| SMS (OTP) | Mock provider (dev) works. Twilio provider implemented but **not tested against the live Twilio API** |
| Push (FCM) | Outbox, retries and token cleanup tested with the log provider; Firebase SDK initialisation tested with a generated key. **Real delivery not tested** (needs a Firebase project and device) |
| Flutter / React integration | **Not tested end to end yet** — checklist in the mapping document |

## Quick start (Windows)

Prerequisites: JDK 21+ (`java -version`), Docker Desktop running. Maven is not needed (the wrapper downloads it).

```powershell
# 1. Start PostgreSQL and Redis
docker compose up -d

# 2. Run the backend with demo data (dev profile)
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

CMD: `mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev`. Git Bash / macOS / Linux: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`.

If PostgreSQL or Redis already run natively on your machine (ports 5432 / 6379 taken), start the containers on other
ports and point the app at them:

```powershell
$env:POSTGRES_HOST_PORT="5433"; $env:REDIS_HOST_PORT="6380"; docker compose up -d
$env:DB_URL="jdbc:postgresql://localhost:5433/mygateapp"; $env:REDIS_PORT="6380"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

| URL | |
| --- | --- |
| API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui/index.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health (also `/liveness`, `/readiness`) |

### Demo accounts (dev profile only)

The `dev` profile seeds society **Greenwood Residency** (towers A–C, 168 flats, Main and Service gates) with the same
mobile numbers as the Flutter app's mock mode. The development OTP is **123456** (`OTP_FIXED_CODE`, refused in prod).

| Mobile | Account |
| --- | --- |
| 9876543210 | Resident Aarav Mehta — owner of A-1204, 2 family members, 2 domestic staff |
| 9876500001 | Guard Ramesh Yadav — Main Gate, day shift, on duty, all gate permissions |
| 9876500002 | New resident (no name yet → profile setup), tenant of B-402 |
| 9876500003 | Guard Suresh Patil — Service Gate, off duty, no staff-attendance permission |
| 9876500004 | Resident Neha Sharma — owner of B-703 |
| 9876500009 | Inactive account (sign-in is refused) |
| 9876500100 | Society admin of Greenwood (React panel) |
| 9876500000 | Platform super admin |

Seeding runs once (skipped when the `GREENWOOD` society exists). Reset everything with `docker compose down -v`.

### Try it

```powershell
$otp = Invoke-RestMethod -Method Post http://localhost:8080/api/v1/auth/request-otp -ContentType 'application/json' -Body '{"mobile":"9876543210","countryCode":"+91"}'
$auth = Invoke-RestMethod -Method Post http://localhost:8080/api/v1/auth/verify-otp -ContentType 'application/json' -Body (@{mobile="9876543210"; otp="123456"; requestId=$otp.requestId} | ConvertTo-Json)
Invoke-RestMethod http://localhost:8080/api/v1/users/me -Headers @{Authorization = "Bearer $($auth.accessToken)"}
```

In Swagger UI: call `request-otp` and `verify-otp`, copy `accessToken`, press **Authorize** and paste it.

## Configuration

Profiles: `dev` (local, demo data, mock SMS, fixed OTP), `prod` (everything from environment variables, Swagger off by
default, structured JSON logs), and `test` (automated tests). Without a profile the app uses safe defaults and will not
send OTPs (`SMS_PROVIDER=none`).

All variables are listed in [.env.example](.env.example). The important ones:

| Variable | Notes |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `dev` or `prod` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL 13+ (17 used in development and tests) |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis: OTPs, rate limits, idempotency keys |
| `JWT_SECRET` | ≥ 32 bytes, random. The app refuses to start with a shorter secret |
| `JWT_ACCESS_EXPIRATION`, `JWT_REFRESH_EXPIRATION` | ISO-8601 durations, default `PT15M` / `P30D` |
| `SMS_PROVIDER`, `SMS_ACCOUNT_ID`, `SMS_API_KEY`, `SMS_SENDER_ID` | `twilio` in production; `mock` is refused by the prod profile |
| `PUSH_PROVIDER`, `FIREBASE_CREDENTIALS_PATH` | `firebase` + path to a mounted service-account JSON |
| `CORS_ALLOWED_ORIGINS` | Comma-separated browser origins (React admin, Flutter web). Wildcards refused in prod |
| `PUBLIC_BASE_URL` | Public API URL used in signed image links when behind a proxy |

The prod profile fails fast on unsafe settings (`ProductionSafetyCheck`): short JWT secret, mock SMS, fixed OTP,
wildcard CORS.

## Tests

```powershell
.\mvnw.cmd test
```

Docker must be running: integration tests start throw-away PostgreSQL and Redis containers (Testcontainers). The first
run downloads the images. No test touches a shared or production database.

| Suite | What it proves |
| --- | --- |
| `ApplicationStartupIT` | All Flyway migrations apply; Hibernate validates every entity against the schema; immutable-history triggers; health and OpenAPI; unauthenticated access denied |
| `AuthFlowIT`, `OtpRateLimitIT`, `OtpServiceTest` | OTP issue/verify, wrong/expired/locked codes, rate limits, refresh rotation and reuse detection, logout, deactivation |
| `CoreManagementIT` | Societies, towers/wings/flats, residents (approval, tenant requests, move-out), guards, gates, lookups |
| `VisitorWorkflowIT` | Walk-in approval → entry → exit, rejection, duplicate/out-of-order movements, **concurrent duplicate entry**, invitations and pass codes, expiry, photo upload via signed URLs, idempotency keys |
| `SocietyOperationsIT` | Deliveries, domestic-staff attendance, complaints, notices |
| `NotificationIT`, `NotificationDispatcherTest`, `FirebasePushSenderTest` | In-app notifications, preferences, devices, push outbox outcomes, Firebase SDK initialisation |
| `DashboardsAndReportsIT` | Resident / guard / admin dashboards, activity feeds, reports, audit log |
| `CrossSocietyIsolationIT` | Users of society B cannot read or change any record of society A by id, list or header |
| Unit tests | Gate movement rules, complaint transitions, mobile normalisation, file sniffing, flat numbering, production safety, production clock |

## Docker

```powershell
docker build -t mygateapp-backend .
docker compose --profile app up -d      # PostgreSQL + Redis + backend (dev profile by default)
```

The image runs as a non-root user on a JRE, with a liveness health check. Uploaded files are stored in the
`/app/data/uploads` volume.

## Deployment (VPS / cloud)

1. Provision PostgreSQL (≥ 13) and Redis (with a password) — managed services are recommended.
2. Run the container with `SPRING_PROFILES_ACTIVE=prod` and all variables marked REQUIRED in `.env.example`
   (use the platform's secret store; never bake secrets into the image).
3. Mount the Firebase service-account file read-only and set `FIREBASE_CREDENTIALS_PATH`.
4. Put the app behind an HTTPS reverse proxy / load balancer (`server.forward-headers-strategy=framework` is enabled in
   prod, so client IPs for rate limiting come from `X-Forwarded-For`; make sure the proxy overwrites that header).
5. Mount a persistent volume at `FILE_STORAGE_DIR` (or a shared volume when running several instances).
6. Flyway migrates the schema on start-up. Several instances may run at once: background jobs are idempotent and the
   push outbox uses `SKIP LOCKED`.
7. Expose only `/api/**` and `/actuator/health`; keep Swagger disabled unless `SWAGGER_ENABLED=true` is wanted.

## Connecting the Flutter app

The Flutter app already contains the HTTP layer; switch it from mock data to this backend:

```bash
flutter run --dart-define=USE_MOCK=false --dart-define=API_BASE_URL=http://10.0.2.2:8080
```

| Where the app runs | `API_BASE_URL` |
| --- | --- |
| Android emulator | `http://10.0.2.2:8080` |
| iOS simulator / Chrome | `http://localhost:8080` (for Chrome add the web origin to `CORS_ALLOWED_ORIGINS`) |
| Physical phone on the same Wi-Fi | `http://<your PC's LAN IP>:8080` (allow port 8080 in Windows Firewall) |
| Production | `https://api.<your-domain>` (release builds require HTTPS) |

Contract details, mismatches and the end-to-end checklist: [FLUTTER_BACKEND_API_MAPPING.md](FLUTTER_BACKEND_API_MAPPING.md).

## Project layout

```text
src/main/java/com/mygateapp/
├── MyGateAppApplication.java
├── config/          AppProperties, ProductionSafetyCheck, OpenAPI, JPA, async/scheduling
├── security/        SecurityConfig, JWT, AccessService (tenant + permission checks), roles/permissions
├── common/          base entities, paging, gate-movement policy, rate limiting, idempotency, helpers
├── exception/       ErrorCode, ApiException, GlobalExceptionHandler
├── auth/            OTP, SMS providers, refresh sessions, /auth/*
├── users/           users, /users/me
├── society/         societies, settings, admins, platform, /config/lookups
├── flats/           towers, wings, flats
├── residents/       flat memberships, family members
├── guards/          guards, gates, assignments, duty
├── visitors/        walk-ins, invitations, approvals, entry/exit
├── deliveries/      deliveries
├── domesticstaff/   staff, flat assignments, attendance
├── complaints/      complaints, comments, status history
├── notices/         notices, attachments, read receipts
├── notifications/   notifications, outbox dispatcher, devices, FCM
├── activity/        unified activity feed
├── reports/         dashboards and reports
├── audit/           audit log
├── files/           uploads and signed URLs
├── maintenance/     expiry and retention jobs
└── dev/             demo data seeder (dev profile only)
src/main/resources/db/migration/   Flyway migrations V1–V8
```

## Known limitations / next steps

- Twilio SMS and Firebase delivery are implemented but untested against the live services (no credentials here).
- Uploaded files are stored on local disk; use a shared volume or swap `FileStorage` for object storage when running
  several instances.
- Self-registration of residents is not in Phase 1: residents are created by admins or requested by a flat owner.
- `X-Society-Id` lets multi-society users pick a society; the Flutter app does not send it yet (single-society users
  need nothing).
