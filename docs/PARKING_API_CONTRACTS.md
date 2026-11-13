# Parking API contracts

Society, vehicle and parking API added in V9. Swagger UI (`/swagger-ui/index.html`, tags *Vehicles*, *Parking …*) is
generated from the same controllers. All conventions of [api-contracts.md](api-contracts.md) apply.

| Topic | Contract |
| --- | --- |
| Base path | `/api/v1` |
| Auth | `Authorization: Bearer <accessToken>` on every endpoint |
| Society scope | Taken from the caller's membership, or `X-Society-Id` (validated; foreign id → `403 SOCIETY_ACCESS_DENIED`). Records of another society are reported as `404` |
| Lists | Spring page `{content, number, size, totalElements, totalPages, first, last, numberOfElements, empty}`; `page` (0-based), `size` (default 20, max 100; slots max 500). Endpoints marked *array* return a plain JSON array |
| Money | `{ "amount": "120.00", "currency": "INR" }` — decimal string, two places. Request amounts are validated but never trusted as totals |
| Times | ISO-8601 UTC instants. Entry/exit/payment/decision times are always set by the server. Report ranges are local dates in the society time zone |
| Vehicle numbers | Sent as typed; stored and returned normalised (`mh 12-ab 1234` → `MH12AB1234`) |
| Idempotency | Send `Idempotency-Key` (8–100 chars) on entry, exit and payment POSTs; a retry with the same key replays the first 2xx response with `Idempotent-Replayed: true` |
| POST bodies | Every POST returns a JSON body (no empty 204) |
| Errors | `{ success:false, code, message, errors:[{field,message}], timestamp, path }` |

## 1. Roles and permissions

Role checks on the route (`@PreAuthorize`) plus a society-scoped permission check in the service. V9 permissions:

| Permission | SUPER_ADMIN | SOCIETY_ADMIN | RESIDENT | GUARD |
| --- | :-: | :-: | :-: | :-: |
| `PARKING_VIEW` (config, areas, availability, rates, quotes) | ✓ | ✓ | ✓ | ✓ |
| `PARKING_SETTINGS_MANAGE` | ✓ | ✓ | | |
| `PARKING_INVENTORY_MANAGE` (areas, slots) | ✓ | ✓ | | |
| `PARKING_ALLOCATION_MANAGE` (allocations, permits, request decisions, session cancel) | ✓ | ✓ | | |
| `VEHICLE_MANAGE` (all vehicles, approve/reject/suspend, society vehicles) | ✓ | ✓ | | |
| `PARKING_RATE_MANAGE` | ✓ | ✓ | | |
| `PARKING_PAYMENT_MANAGE` (manual payments, confirm, fail, refund, waive) | ✓ | ✓ | | |
| `PARKING_LOG_VIEW` (all sessions, permits, requests, payments) | ✓ | ✓ | | |
| `PARKING_REPORT_VIEW` (reports incl. revenue) | ✓ | ✓ | | |
| `VEHICLE_REGISTER_OWN`, `PARKING_REQUEST`, `PARKING_VIEW_OWN` (own flats only) | | | ✓ | |
| `GATE_VEHICLE_OPERATE` | | | | ✓ |

Guards additionally need the per-guard toggle **`VEHICLE_ENTRY`** (returned in `/users/me` → `guard.permissions`).
Writes at the gate (verify, entry, exit, gate payment) also require: on duty (`403 GUARD_OFF_DUTY`) and assigned to an
active gate (`403 GUARD_NO_GATE`). Missing toggle → `403 GUARD_PERMISSION_DENIED`. Reads (dashboard, search, slot map)
do not require duty.

## 2. Enums

| Enum | Values |
| --- | --- |
| `VehicleType` | `CAR`, `BIKE`, `SCOOTER`, `OTHER` (BIKE/SCOOTER share two-wheeler slots) |
| `VehicleCategory` (vehicle `category`, session `category`, `entryType`) | `RESIDENT`, `VISITOR`, `PAID`, `SOCIETY`, `STAFF`, `SERVICE`, `EMERGENCY` |
| `RegistrationStatus` | `NOT_SUBMITTED`, `PENDING`, `APPROVED`, `REJECTED`, `SUSPENDED` |
| `NumberFormat` | `INDIA` (state/RTO/series/number + Bharat `BH`), `GENERIC` (4–13 letters/digits) |
| `ParkingUsage` | `RESIDENT`, `VISITOR`, `PAID`, `SOCIETY`, `STAFF`, `SERVICE`, `EMERGENCY`, `COMMON`, `RESERVED` |
| Slot `operationalStatus` | `ACTIVE`, `OUT_OF_SERVICE` |
| Slot `status` (display) | `AVAILABLE`, `OCCUPIED`, `RESERVED`, `BLOCKED` |
| `AllocationType` | `PERMANENT`, `TEMPORARY`, `VISITOR`, `PAID`, `SOCIETY` |
| `AllocationStatus` | `ACTIVE`, `PENDING` (paid permit awaiting payment), `SUSPENDED`, `EXPIRED`, `REVOKED` |
| `PermitType` | `RESIDENT`, `TEMPORARY`, `VISITOR`, `PAID_MONTHLY`, `SOCIETY`, `STAFF`, `SERVICE`, `EMERGENCY` |
| `PermitStatus` | `ACTIVE`, `PENDING_PAYMENT`, `EXPIRED`, `REVOKED`, `SUSPENDED` |
| `RequestType` | `ADDITIONAL_SLOT`, `TEMPORARY_PERMIT`, `VISITOR_SLOT`, `PAID_PERMIT` |
| `RequestStatus` | `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `CANCELLED` |
| `VisitorParkingType` | `VISITOR`, `PAID` |
| Session `status` | `ACTIVE`, `OVERSTAY` (derived: active and past `validUntil` + grace), `COMPLETED`, `CANCELLED` |
| `charges.paymentStatus` | `NOT_APPLICABLE`, `PENDING`, `PAID`, `WAIVED`, `FAILED` |
| `VerificationDecision` | `ALLOWED`, `ALREADY_INSIDE`, `NEEDS_VISITOR_APPROVAL`, `NOT_REGISTERED`, `DENIED` |
| `SessionScope` | `MINE`, `ACTIVE`, `OVERSTAY`, `HISTORY` |
| `SearchField` | `VEHICLE`, `FLAT`, `SLOT`, `PERMIT` |
| `BillingUnit` | `HOURLY`, `DAILY`, `MONTHLY` (30 days) |
| `RateAppliesTo` | `VISITOR`, `RESIDENT`, `PUBLIC`, `ALL` |
| `PaymentStatus` | `PENDING`, `PAID`, `FAILED`, `REFUNDED`, `WAIVED` |
| `PaymentMethod` | `CASH`, `UPI_AT_GATE`, `CARD_AT_GATE` (gate); `UPI`, `BANK_TRANSFER` (office); `ONLINE` (not available); `WAIVER` |
| `PaymentPurpose` | `SESSION`, `PERMIT` |
| Alert `type` / `severity` | `PERMIT_EXPIRING`, `OVERSTAY`, `PAYMENT_DUE`, `REQUEST_UPDATE`, `VEHICLE_UPDATE`, `GENERAL` / `INFO`, `WARNING`, `CRITICAL` |

## 3. Endpoints

R = resident, G = guard, A = society admin / super admin.

### Society and settings

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET | `/societies/my` | R G A | Current society + towers/wings |
| GET | `/societies/{id}/parking/settings` | A | Full settings + derived `capacity` |
| PUT | `/societies/{id}/parking/settings` | A | Full replacement (audited) |
| GET | `/parking/config` | R G A | Switches, limits, rules, areas with live counts |

### Vehicles

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET | `/vehicles?q&category&status&flatId&active&page&size&sort` | R A | R: own flats; A: whole society |
| GET | `/vehicles/{id}` | R A | |
| POST | `/vehicles` | R A | R: RESIDENT vehicle of own flat (PENDING or NOT_SUBMITTED); A: any category, approved immediately |
| PATCH | `/vehicles/{id}` | R A | Edit / `active:false`. R changing number/type of an approved vehicle → PENDING + slot/permits SUSPENDED |
| POST | `/vehicles/{id}/submit` | R | NOT_SUBMITTED/REJECTED → PENDING |
| POST | `/vehicles/{id}/approve` | A | `{remarks?}`; reinstates suspended slot/permits |
| POST | `/vehicles/{id}/reject` | A | `{remarks}` required |
| POST | `/vehicles/{id}/suspend` | A | `{remarks}` required; suspends slot/permits |
| GET | `/vehicles/search?q&by&category` | G A | *array*; same as `/parking/vehicles/search` |

### Inventory and availability

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET | `/parking/areas?includeInactive` | R G A | *array* with `totalSlots`, `availableSlots`, `occupiedSlots`, `outOfServiceSlots` |
| GET | `/parking/areas/{id}` | R G A | |
| POST | `/parking/areas` | A | `{code, name, level, description, usage, vehicleTypes[]}` |
| PATCH | `/parking/areas/{id}` | A | name, level, description, usage, vehicleTypes, `status` |
| GET | `/parking/slots?areaId&usage&operationalStatus&vehicleType&q&page&size` | G A | page; with `areaId` the default page holds 500 |
| GET | `/parking/slots/{id}` | G A | |
| POST | `/parking/slots` | A | `{areaId, number, vehicleType, usage?, notes?}` |
| POST | `/parking/slots/bulk` | A | `{areaId, prefix, from, to, padding, vehicleType, usage?}` (≤ 500) → *array* |
| PATCH | `/parking/slots/{id}` | A | number, vehicleType, usage (`RESERVED` reserves it), `operationalStatus`, `outOfServiceReason` |
| GET | `/parking/availability?usage&vehicleType&from&until` | R G A | Defaults VISITOR, CAR, now, +4 h; window ≤ 31 days |

### Allocations and permits

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET | `/parking/allocations/mine` | R | *array*: live + ended in the last 30 days |
| GET | `/parking/allocations?status&type&flatId&slotId&vehicleId` | A | page |
| GET | `/parking/allocations/{id}` | R(own) A | |
| POST | `/parking/allocations` | A | `{slotId, allocationType, flatId?, vehicleId?, validFrom?, validUntil?, notes?, issuePermit?}` |
| PATCH | `/parking/allocations/{id}` | A | `{validUntil?, vehicleId?, status?: REVOKED/SUSPENDED/ACTIVE, reason?, notes?}` |
| POST | `/parking/allocations/{id}/transfer` | A | `{slotId, flatId?, vehicleId?, reason?}` |
| GET | `/parking/permits/mine` | R | *array* |
| GET | `/parking/permits?status&type&q` | A | page |
| GET | `/parking/permits/{id}` | R(own) G A | |
| POST | `/parking/permits` | A | `{type, vehicleId \| vehicleNumber+vehicleType, flatId?, slotId?, areaId?, holderName?, validFrom?, validUntil?, conditions[]}`; with `slotId` the slot is allocated |
| POST | `/parking/permits/{id}/revoke` | A | `{reason}` |

### Requests and visitor parking

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET | `/parking/requests/mine?status&type` | R | page |
| GET | `/parking/requests?status&type` | R A | A: society; R: own |
| GET | `/parking/requests/{id}` | R(own) A | |
| POST | `/parking/requests` | R | `{type, flatId, vehicleId?, vehicleNumber?, vehicleType?, preferredAreaId?, rateId?, validFrom?, validUntil?, reason?}` |
| POST | `/parking/requests/{id}/cancel` | R | Pending, or approved unused visitor parking (releases the slot) |
| POST | `/parking/requests/{id}/approve` | A | `{slotId?, remarks?}`; slot auto-selected when omitted |
| POST | `/parking/requests/{id}/reject` | A | `{remarks}` |
| POST | `/parking/visitor-requests` | R | `{flatId, visitorId?, visitorName, visitorMobile?, vehicleNumber, vehicleType, expectedArrival, expectedDeparture, purpose?, parkingType?, rateId?}` |
| GET | `/parking/visitor-requests?status` | R A | page |
| GET | `/parking/visitor-requests/{id}` | R(own) A | |
| POST | `/parking/visitor-requests/{id}/approve` · `/reject` | A | Same as requests |

### Gate and sessions

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| POST | `/parking/verifications` | G (on duty) | `{vehicleNumber, purpose?, vehicleType?}` |
| POST | `/parking/sessions` | G (on duty) | Entry. `{verificationId, vehicleNumber, entryType, visitorId?, parkingRequestId?, rateId?, slotId?, vehicleType?, overrideReason?}` + `Idempotency-Key` → 201 |
| POST | `/parking/sessions/{id}/close` | G (on duty) | Exit, `{}` + `Idempotency-Key` |
| POST | `/parking/sessions/{id}/cancel` | A | `{reason}` — correction of a wrong entry |
| GET | `/parking/sessions?scope&vehicleId&category&q&paid&from&to` | R G A | R: own flats |
| GET | `/parking/sessions/{id}` | R(own) G A | Includes `history` (movements) |
| GET | `/parking/vehicles/search?q&by&category` | G A | *array*, max 20; owner as role label, no contact data. `q` and the stored numbers are compared without spaces, hyphens and dots (`B1-042` = `B1042`); 2–13 characters for `VEHICLE`, up to 40 for `FLAT`, `SLOT`, `PERMIT` |
| GET | `/guards/parking/dashboard` | G A | |
| GET | `/parking/summary` | R | Counts, unpaid amount, alerts |

### Pricing and payments

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| GET | `/parking/rates?vehicleType&billingUnit&appliesTo&includeInactive` | R G A | *array* (inactive only for admins) |
| GET | `/parking/rates/{id}` | R G A | |
| POST | `/parking/rates` | A | `{code, name, description?, areaId?, vehicleType?, appliesTo, billingUnit, amount, freeMinutes?, gracePeriodMinutes?, maxPerDay?, overstayHourlyRate?, taxPercent?, taxLabel?, effectiveFrom?, effectiveUntil?}` |
| PATCH | `/parking/rates/{id}` | A | Running sessions keep their snapshot |
| POST | `/parking/quotes` | R G A | `{rateId, vehicleType?, from?, until}` estimate, or `{sessionId}` charge to close now; valid 10 min |
| POST | `/parking/sessions/{id}/payments` | G (on duty) | `{quoteId, method: CASH\|UPI_AT_GATE\|CARD_AT_GATE, reference?}` + `Idempotency-Key` → 201 with receipt |
| POST | `/parking/payments/manual` | A | `{sessionId \| permitId, method, reference?, amount?, payerName?, notes?, confirm?}` |
| POST | `/parking/payments/{id}/confirm` | A | `{method?, reference?}` PENDING → PAID + receipt |
| POST | `/parking/payments/{id}/fail` | A | `{reason}` |
| POST | `/parking/payments/{id}/refund` | A | `{reason}` PAID → REFUNDED |
| POST | `/parking/payments/waivers` | A | `{sessionId \| permitId, reason}` |
| GET | `/parking/payments?status&purpose&sessionId&permitId&from&to` | R A | R: own flats |
| GET | `/parking/payments/{id}` | R(own) G(session payments) A | Includes `receipt` and `history` |
| GET | `/parking/receipts/{id}` | R(own) G A | Receipt id or payment id |

### Reports (admins, `PARKING_REPORT_VIEW`)

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/parking/reports/overview` | Slot counts (allocated/unallocated, occupied/vacant, reserved, available, out of service), vehicles, permits, overstays, today's entries/exits, pending payments, revenue today/month |
| GET | `/parking/reports/occupancy` | Live counts per area and per usage, `occupancyPercent` |
| GET | `/parking/reports/sessions?from&to&category&status&page&size` | Totals by category, paid sessions, overrides, overstays, average duration + page of sessions |
| GET | `/parking/reports/revenue?from&to` | Collected, refunded, waived, net, pending; by method, purpose, day, month |

`from`/`to` are local dates (inclusive, society time zone, default last 7 days, max 366 days).

## 4. Gate rules

Verification decides, entry re-checks and records:

1. Pedestrian gate → `DENIED`. Active session for the number → `ALREADY_INSIDE` (`activeSession` set).
2. **Registered vehicle**: valid permit → `ALLOWED`; approved with an active allocation → `ALLOWED` (slot); approved
   resident vehicle without permit → `ALLOWED` only when `residentEntryRequiresPermit=false` (warning "No parking
   slot…"), else `DENIED`; not approved → `DENIED` with the registration reason.
3. **Approved visitor parking** for the number (window open or opening within 30 min, unused): linked visitor
   `PENDING` → `NEEDS_VISITOR_APPROVAL`; rejected/closed → `DENIED`; otherwise `ALLOWED` (`VISITOR`, or `PAID` for paid
   visitor parking) with the reserved slot.
4. **Permit for the number** (temporary/society permits of unregistered numbers) → `ALLOWED`.
5. **Visitor record** with this vehicle (last 24 h or valid invite): `PENDING` → `NEEDS_VISITOR_APPROVAL`; approved
   and the society auto-approves visitor parking and a visitor slot is free → `ALLOWED` for
   `maxVisitorParkingHours`; otherwise `DENIED`.
6. Otherwise `NOT_REGISTERED`.

`paidParkingOffered` (+ `rates[]`) when paid parking is enabled, a paid slot is free, a rate applies and — for
unknown vehicles — `externalPaidParkingAllowed`.

Entry: the verification must be unexpired (5 min), unused, of the same guard and society and for the same number
(else `409 VERIFICATION_EXPIRED`). `entryType` must equal the verified category; `PAID` is allowed when paid parking
was offered (`rateId` required); `EMERGENCY` with `overrideReason` is allowed only when the society enables
`emergencyOverrideAllowed` (flagged, audited, admins notified). Otherwise `403 ENTRY_NOT_AUTHORIZED`. Slot: the
guard's `slotId` (must suit the category and vehicle, not be out of service, occupied or reserved for someone else),
else the authorised slot, else a free paid/visitor slot (`409 PARKING_UNAVAILABLE`). The vehicle type comes from the
registration/request/permit, else the request, the rate, the slot, and finally defaults to `CAR`. Entry of a linked,
approved visitor also records the visitor's check-in (if the guard has `VISITOR_ENTRY`).

Exit: active session only (`409 SESSION_ALREADY_CLOSED`, `404` for unknown ids — exit before entry is impossible).
Paid sessions: final charge computed now; outstanding and `paymentRequiredBeforeExit` → `409 PAYMENT_PENDING`
(within `paidUntil` after a gate payment the vehicle may leave). If the society allows leaving unpaid, a `PENDING`
payment is created. Visitor allocations and visitor permits are released; the linked visitor's exit is recorded.

## 5. Pricing

`PricingCalculator` (BigDecimal, 2 decimals, HALF_UP):

1. Stay ≤ society `freeGraceMinutes` → free.
2. Rate `freeMinutes` are deducted.
3. Billable time in units (60 min / 1 day / 30 days); a started unit counts only when it exceeds the rate's
   `gracePeriodMinutes`.
4. Hourly plans with `maxPerDay`: each full or partial 24 h block costs at most the cap.
5. Time after `validUntil` (beyond the grace) at `overstayHourlyRate` per started hour.
6. Tax = subtotal × `taxPercent` (rounded); total = subtotal + tax.

Example — ₹30/h, grace 15 min, GST 18 %, stay 2 h 5 min: 2 h → subtotal 60.00, tax 10.80, total 70.80.

## 6. Samples

### Verification

```json
POST /api/v1/parking/verifications  {"vehicleNumber":"mh 12 ab 1234"}
{ "verificationId":"0f6c…","vehicleNumber":"MH12AB1234","vehicleType":"CAR","decision":"ALLOWED","category":"RESIDENT",
  "summary":"Registered resident vehicle · A-101",
  "vehicle":{"id":"…","registrationNumber":"MH12AB1234","vehicleType":"CAR","make":"Hyundai","model":"Creta","color":"White"},
  "flat":{"id":"…","tower":"A","wing":null,"number":"101","label":"A-101"},
  "permit":{"id":"…","permitNumber":"GREENWOOD-RES-1001","type":"RESIDENT","status":"ACTIVE","validUntil":null},
  "slot":{"id":"…","number":"B1-042","area":{"id":"…","name":"Basement 1","level":"B1"},"vehicleTypes":["CAR"]},
  "validUntil":null,"visitor":null,"parkingRequestId":null,"activeSession":null,"paidParkingOffered":false,"rates":[],
  "reasons":[],"warnings":[],"emergencyOverrideAllowed":false,"expiresAt":"2026-11-12T10:05:00Z" }
```

### Session (paid, before payment)

```json
{ "id":"…","vehicleNumber":"MH01PD1234","vehicleType":"CAR","category":"PAID","vehicle":null,"visitorId":null,
  "visitorName":null,"flat":null,"slot":{"id":"…","number":"P-01","area":{…},"vehicleTypes":["CAR"]},"permit":null,
  "entryGate":{"id":"…","name":"Main Gate"},"entryAt":"…","exitGate":null,"exitAt":null,"validUntil":null,"status":"ACTIVE",
  "charges":{"rate":{"id":"…","code":"PUB-CAR-HR","billingUnit":"HOURLY","amount":{"amount":"30.00","currency":"INR"},…},
             "estimated":{"amount":"70.80","currency":"INR"},"final":null,"paymentStatus":"PENDING","paymentId":null,
             "paidUntil":null,"paid":{"amount":"0.00","currency":"INR"},"outstanding":{"amount":"70.80","currency":"INR"}},
  "exitAllowed":false,"exitBlockedReason":"Collect 70.80 INR before exit.","recordedBy":"Ramesh Yadav",
  "emergencyOverride":false,"overrideReason":null,"parkingRequestId":null,"history":[…] }
```

### Quote → gate payment

```json
POST /api/v1/parking/quotes  {"sessionId":"…"}
{ "id":"…","sessionId":"…","from":"…","until":"…","billableMinutes":125,
  "lines":[{"label":"2 h × ₹30.00","amount":{"amount":"60.00","currency":"INR"}}],
  "subtotal":{"amount":"60.00","currency":"INR"},"tax":{"amount":"10.80","currency":"INR"},"taxLabel":"GST 18%",
  "alreadyPaid":{"amount":"0.00","currency":"INR"},"total":{"amount":"70.80","currency":"INR"},
  "calculatedAt":"…","expiresAt":"…" }

POST /api/v1/parking/sessions/{id}/payments  Idempotency-Key: 6f1…  {"quoteId":"…","method":"CASH"}
201 { "id":"…","status":"PAID","amount":{"amount":"70.80","currency":"INR"},"method":"CASH","purpose":"SESSION",
      "receipt":{"receiptNumber":"GREENWOOD-RC-2026-1001","total":{"amount":"70.80","currency":"INR"},…},
      "history":[{"type":"CREATED",…},{"type":"CONFIRMED","actor":"Ramesh Yadav",…}] }
```

## 7. Error codes (new)

| Code | HTTP | When |
| --- | --- | --- |
| `VEHICLE_NOT_FOUND` | 404 | Unknown, other society, or another flat's vehicle (residents) |
| `INVALID_VEHICLE_NUMBER` | 400 | Number fails the society / chosen format (field error) |
| `VEHICLE_ALREADY_REGISTERED` | 409 | Number held by another active vehicle in the society (field error) |
| `VEHICLE_LIMIT_REACHED` | 422 | Above `maxVehiclesPerFlat` |
| `VEHICLE_TYPE_NOT_ALLOWED` | 400 | Type not in `allowedVehicleTypes` |
| `VEHICLE_NOT_SUBMITTABLE` / `VEHICLE_NOT_PENDING` / `VEHICLE_NOT_APPROVED` / `VEHICLE_INACTIVE` | 409 | Wrong registration state |
| `PARKING_FEATURE_DISABLED` | 409 | Visitor / paid parking switched off |
| `PARKING_AREA_NOT_FOUND`, `PARKING_SLOT_NOT_FOUND`, `ALLOCATION_NOT_FOUND`, `PERMIT_NOT_FOUND`, `PARKING_REQUEST_NOT_FOUND`, `PARKING_SESSION_NOT_FOUND`, `RATE_NOT_FOUND`, `QUOTE_NOT_FOUND`, `PAYMENT_NOT_FOUND`, `RECEIPT_NOT_FOUND` | 404 | |
| `PARKING_AREA_TAKEN`, `PARKING_SLOT_TAKEN`, `RATE_CODE_TAKEN` | 409 | Duplicate code / name / slot number |
| `PARKING_AREA_INACTIVE`, `SLOT_OUT_OF_SERVICE`, `SLOT_NOT_SUITABLE`, `SLOT_OCCUPIED`, `SLOT_RESERVED` | 409 | Slot cannot be used |
| `INVALID_PARKING_WINDOW` | 400 | Bad or too long time window |
| `ALLOCATION_CONFLICT` | 409 | Overlapping allocation of the slot or the vehicle |
| `ALLOCATION_NOT_ACTIVE`, `PERMIT_NOT_ACTIVE` | 409 | |
| `REQUEST_ALREADY_PENDING`, `REQUEST_ALREADY_DECIDED`, `REQUEST_NOT_CANCELLABLE` | 409 | |
| `PARKING_UNAVAILABLE` | 409 | No suitable slot free for the window |
| `SESSION_ALREADY_ACTIVE`, `SESSION_ALREADY_CLOSED` | 409 | Duplicate entry / exit |
| `VERIFICATION_EXPIRED` | 409 | Expired, used, other guard or other number |
| `ENTRY_NOT_AUTHORIZED` | 403 | Decision does not allow this entry type |
| `GATE_NOT_FOR_VEHICLES` | 403 | Reserved (pedestrian gates are reported as `DENIED` at verification) |
| `PAYMENT_PENDING` | 409 | Exit with charges outstanding |
| `RATE_NOT_APPLICABLE` | 409 | Inactive rate, wrong vehicle type, audience or unit |
| `QUOTE_EXPIRED` | 409 | Quote older than 10 min, total no longer matches, or already paid |
| `PAYMENT_AMOUNT_MISMATCH` | 409 | Client amount ≠ server outstanding amount |
| `PAYMENT_METHOD_NOT_ACCEPTED` | 400 | Method not allowed at the gate / by the society |
| `PAYMENT_INVALID_STATE`, `NOTHING_TO_PAY` | 409 | |
| `ONLINE_PAYMENT_UNAVAILABLE` | 503 | Reserved: no online provider is configured |

## 8. Notifications

Type `PARKING_UPDATE` (in-app + push through the existing outbox), `referenceType` one of `VEHICLE`,
`PARKING_REQUEST`, `PARKING_ALLOCATION`, `PARKING_PERMIT`, `PARKING_SESSION`, `PARKING_PAYMENT`.

| Event | Recipients |
| --- | --- |
| Vehicle pending review / changed | Society admins |
| Vehicle approved / rejected / suspended | Residents of the flat |
| Slot allocated / changed / cancelled; permit issued / revoked / expiring (3 days) | Residents of the flat |
| Request created (pending) | Society admins |
| Request approved (incl. auto) / rejected | Residents of the flat |
| Visitor vehicle arrived, paid session started, session closed | Residents of the flat |
| Overstay detected | Residents of the flat and on-duty guards |
| Payment received / confirmed / refunded | Residents of the flat (no amounts or references in the text) |
| Emergency override entry | Society admins |

Push delivery uses `PUSH_PROVIDER` (log in development). Real FCM delivery is still untested (no credentials).

## 9. Background jobs (`ParkingJobs`)

| Job | Schedule | Action |
| --- | --- | --- |
| Expiry, overstay, reminders | every minute (`app.jobs.parking-delay`) | Pending requests past their window and unused approved visitor parking → `EXPIRED` (slot released); allocations and permits past `validUntil` → `EXPIRED`; overstays reported once; permit expiry reminders 3 days ahead |
| Retention | daily 03:45 | Visitor name/mobile removed from visitor parking requests after `visitor_retention_days`; unused verifications and quotes older than 7 days deleted |
