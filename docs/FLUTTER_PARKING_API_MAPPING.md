# Flutter ↔ backend: society, vehicle and parking mapping

The Flutter app (`C:\Users\hp\Desktop\mygateapp`, read only) already contains typed clients for this module
(`lib/features/{society,vehicles,parking}/data/*_remote_data_source.dart`) written against its own contract,
`docs/PARKING_API_REQUIREMENTS.md`. This backend implements that contract. Codes below (S1, V3, G5…) are the ones
used in the Flutter document. Full backend contract: [PARKING_API_CONTRACTS.md](PARKING_API_CONTRACTS.md).

**Status: implemented and covered by backend integration tests. On 2026-11-13 the app's real data layer (its
`ApiClient`, auth interceptor, repositories and DTOs) was run against this backend on the dev profile with the
Flutter test `test/live/parking_live_api_test.dart`: 17 checks, all passing (see §5). That run found one backend bug,
now fixed: guard search by slot or permit stripped the hyphens from the term but not from the stored numbers, so
`B1-042` or `GREENWOOD-SRV-1001` were never found (and long permit numbers failed validation); see `ParkingGateIT`.
The screens themselves have not yet been exercised by hand against this backend.**

## 1. Switching the app to the real parking API

```bash
flutter run --dart-define=USE_MOCK=false --dart-define=PARKING_API=true --dart-define=API_BASE_URL=http://10.0.2.2:8080
```

Before testing with real accounts:

1. Grant guards the new toggle **`VEHICLE_ENTRY`** (`PUT /api/v1/guards/{id}` with the full permission list). Guards
   created before V9 do not have it; the app correctly shows its permission-denied screen until they do. Guards
   created from now on without an explicit list get all four toggles.
2. As society admin, create parking areas, slots and (for paid parking) rates, and set the society parking settings
   (`PUT /api/v1/societies/{id}/parking/settings`) — see [REACT_ADMIN_PARKING_API_MAPPING.md](REACT_ADMIN_PARKING_API_MAPPING.md).
3. Residents' vehicles must be approved by the society office and given a slot or permit before the gate lets them in.

## 2. Endpoint mapping

| # | Flutter call | Backend | Status | Notes / differences |
| --- | --- | --- | --- | --- |
| S1 | `GET /users/me` | existing | ✅ | `guard.permissions` now may contain `VEHICLE_ENTRY` |
| S2 | `X-Society-Id` | existing | ✅ | Every parking query is limited to that society |
| S3 | `GET /flats/{id}` | existing | ✅ | |
| S4 | `GET /society/flats` | existing | ✅ | |
| S5 | `GET /societies/my` | `MySocietyController` | ✅ | `officeHours` is always `null` (not stored); also returns `timeZone`; towers carry `wings[{id,name}]` |
| P1 | `GET /parking/config` | ✅ | ✅ | Same fields; extra `allowedVehicleTypes`, `acceptedPaymentMethods`, `currency`. `onlinePaymentEnabled` is always `false` |
| V1 | `GET /vehicles?q&page&size` | ✅ | ✅ | Residents see vehicles of their flats in the current society (active and inactive) |
| V2 | `GET /vehicles/{id}` | ✅ | ✅ | Another flat's vehicle → `404 VEHICLE_NOT_FOUND`. `registration.reviewedBy` is `"Society office"` for residents |
| V3 | `POST /vehicles` | ✅ | ✅ | Body as specified. Optional extra `numberFormat: GENERIC` for foreign/temporary plates. `409 VEHICLE_ALREADY_REGISTERED` (field `registrationNumber`), `422 VEHICLE_LIMIT_REACHED`, `403 NOT_FLAT_RESIDENT`, `400 INVALID_VEHICLE_NUMBER` |
| V4 | `PATCH /vehicles/{id}` | ✅ | ✅ | Number/type change of an approved vehicle → `PENDING`, its slot and permits `SUSPENDED` until re-approved. `active:false` ends its slot/permits; refused while the vehicle is inside |
| V5 | `POST /vehicles/{id}/submit` | ✅ | ✅ | `409 VEHICLE_NOT_SUBMITTABLE` otherwise |
| P2 | `GET /parking/summary` | ✅ | ✅ | Alerts: `PERMIT_EXPIRING` (7 days), `OVERSTAY`, `PAYMENT_DUE`, `REQUEST_UPDATE` (decided in the last 3 days), `VEHICLE_UPDATE` (rejected/suspended). `unpaidAmount` = pending payments of the flats |
| P3 | `GET /parking/allocations/mine` | ✅ | ✅ | Array; live allocations plus those ended in the last 30 days |
| P4 | `GET /parking/permits/mine` | ✅ | ✅ | Array; `issuedBy` is the issuing admin's name, `"Society office"` or `"Auto-approved"` |
| P5 | `GET /parking/requests/mine?status&type&page&size` | ✅ | ✅ | Page; includes visitor parking (`VISITOR_SLOT`) |
| P6 | `POST /parking/requests` | ✅ | ✅ | `409 REQUEST_ALREADY_PENDING`; `PAID_PERMIT` needs paid parking enabled (`409 PARKING_FEATURE_DISABLED`) and a `MONTHLY` resident rate (`409 RATE_NOT_APPLICABLE`). `ADDITIONAL_SLOT` with `validUntil` becomes a temporary allocation |
| P7 | `POST /parking/requests/{id}/cancel` | ✅ | ✅ | Also cancels approved, unused visitor parking (releases the slot) |
| P8 | `POST /parking/visitor-requests` | ✅ | ✅ | `visitorName` required unless `visitorId` is given. Auto-approved with a slot when the society enables `visitorParkingAutoApprove`, else `PENDING`. `409 PARKING_UNAVAILABLE` when no slot is free in the window. Window ≤ `maxVisitorParkingHours` |
| P9 | `GET /parking/availability` | ✅ | ✅ | |
| P10 | `GET /parking/rates?vehicleType` | ✅ | ✅ | Array of rates in effect. Extra fields `overstayHourlyRate`, `taxPercent`, `taxLabel`, `area`, `effectiveFrom/Until`, `active` |
| P11 | `POST /parking/quotes` | ✅ | ✅ | `total` is the amount due now; extra `alreadyPaid` and a negative "Paid earlier" line when something was already paid |
| P12 | `GET /parking/sessions?scope&…` | ✅ | ✅ | Residents: own flats only. `paid=true` filters paid sessions |
| P13 | `GET /parking/sessions/{id}` | ✅ | ✅ | `status OVERSTAY` is derived (past `validUntil` + society grace). Extra: `charges.paid`, `charges.outstanding`, `emergencyOverride`, `overrideReason`, `parkingRequestId`, `history[]` (timeline) |
| P14 | `GET /parking/payments` | ✅ | ✅ | Residents: own flats |
| P15 | `GET /parking/payments/{id}` | ✅ | ✅ | Includes `receipt` and `history[]`. Office payments may use methods `UPI` or `BANK_TRANSFER`, which the app's `PaymentMethod` enum does not know: it parses them as `null` and shows the payment without a method (no crash) |
| G1 | `GET /guards/parking/dashboard` | ✅ | ✅ | |
| G2 | `GET /parking/vehicles/search?q&by&category` | ✅ | ✅ | Array (max 20). Owners are role labels (`"Resident · A-1204"`, `"Staff vehicle"`), never names or mobiles. Extra `vehicleId` |
| G3 | `GET /parking/slots?areaId=` | ✅ | ✅ | Returns a **page**; the app's `getList` reads `content`. With `areaId` the default page holds the whole area (≤ 500). Extra fields `allocated`, `occupied`, `reserved`, `operationalStatus`, `currentAllocation` |
| G4 | `POST /parking/verifications` | ✅ | ✅ | Valid 5 minutes, single use, same guard. Extra `emergencyOverrideAllowed`. `visitor.status` is the visitor display status (`PENDING`, `APPROVED`, `PRE_APPROVED`, …) |
| G5 | `POST /parking/sessions` | ✅ | ✅ | The app sends no `vehicleType`: for unregistered vehicles the server takes it from the rate, the slot, or defaults to `CAR`. A linked approved visitor is checked in at the same time (one gate event) when the guard also has `VISITOR_ENTRY` |
| G6 | `POST /parking/sessions/{id}/payments` | ✅ | ✅ | `409 QUOTE_EXPIRED` when the quote is older than 10 minutes or the charge changed. After payment the vehicle may exit until `charges.paidUntil` |
| G7 | `POST /parking/sessions/{id}/close` | ✅ | ✅ | `409 PAYMENT_PENDING` when charges are outstanding and the society requires payment before exit. Releases visitor allocations and records the linked visitor's exit |
| N1 | `NotificationType.PARKING_UPDATE` | ✅ | ✅ | New `referenceType` values (`VEHICLE`, `PARKING_REQUEST`, `PARKING_ALLOCATION`, `PARKING_PERMIT`, `PARKING_SESSION`, `PARKING_PAYMENT`); the app routes by `type` |
| A1 | `GuardPermission.VEHICLE_ENTRY` | ✅ | ✅ | Must be granted to existing guards (see §1) |

## 3. Error codes the app branches on

| App expects | Backend sends | Effect in the app |
| --- | --- | --- |
| `SOCIETY_ACCESS_DENIED` 403 | same | Access denied screen |
| `GUARD_OFF_DUTY` 403 | same | "Start your shift…" |
| `PERMISSION_DENIED` 403 | **`GUARD_PERMISSION_DENIED`** 403 (existing code, kept for backward compatibility) | Both are 403 → `ForbiddenException` → permission-denied screen |
| `VEHICLE_ALREADY_REGISTERED` 409 | same, with field error `registrationNumber` | Field error |
| `VEHICLE_LIMIT_REACHED` 422 | same | Form error |
| `REQUEST_ALREADY_PENDING` 409 | same | Snackbar |
| `PARKING_UNAVAILABLE` 409 | same | Availability failure card |
| `SESSION_ALREADY_ACTIVE` 409 | same | "Already inside" |
| `VERIFICATION_EXPIRED` 409 | same | "Verify again" |
| `ENTRY_NOT_AUTHORIZED` 403 | same | Denied result |
| `PAYMENT_PENDING` 409 | same | Charges card on the exit screen |
| `QUOTE_EXPIRED` 409 | same | "Refresh charge" |
| `SESSION_ALREADY_CLOSED` 409 | same | Session reloaded |

Other new codes (see the contract) arrive as 400/403/404/409 and are shown with the server `message`.

## 4. Security requirements of the Flutter contract (§5)

| Requirement | Backend |
| --- | --- |
| Never trust client amounts | Quotes, final charges and payment amounts are computed server-side; a payment must reference an unexpired quote whose total equals the outstanding amount; manual amounts must match |
| No `PAID` without a server record or verified callback | Only guards (gate methods) and admins (office methods) can record payments; online payment is not implemented (`onlinePaymentEnabled=false`) |
| A number alone never authorises entry | Entry consumes a fresh, single-use verification of the same guard and society |
| Residents only see their flats; guards see no contact data | Service-level flat scoping (`404` for others); search returns role labels only |
| Society filtering | Every query is scoped; foreign ids → `404` |
| Audit every entry, exit, payment, approval, permit change | `audit_logs` + immutable `parking_movements` / `parking_payment_events` |

## 5. End-to-end checklist (to do with the running app)

Ticked = verified through the app's data layer by `test/live/parking_live_api_test.dart` (2026-11-13). "Partly" lists
what that run did not cover.

- [x] Resident: add vehicle → office approves (admin API) → status updates and notification arrives (in-app list)
- [x] Resident: edit an approved vehicle's number → shows pending; gate denies until re-approved
- [x] Resident requests: additional slot (office approval → allocation), temporary permit (duplicate refused, cancel),
  paid monthly permit with its rate
- [ ] Resident visitor parking — partly: auto-approved booking with a slot and `PARKING_UNAVAILABLE` once the bay is
  full verified; a society that needs office approval not yet (the dev society auto-approves)
- [x] Guard without `VEHICLE_ENTRY` refused (403); off-duty guard refused (`GUARD_OFF_DUTY`)
- [x] Guard: verify resident / visitor / unknown vehicle; entry; duplicate entry; exit
- [x] Guard: paid entry → charge → cash payment (retry with the same `Idempotency-Key`) → exit; `PAYMENT_PENDING`
- [x] Guard search by vehicle, flat and slot through the app; permit search covered by `ParkingGateIT`; slot map per area
- [ ] Notifications — partly: in-app `PARKING_UPDATE` list and preferences round trip verified; push needs Firebase
- [ ] Multi-society user: switch society → parking data changes, foreign ids rejected (dev seed has one society)
