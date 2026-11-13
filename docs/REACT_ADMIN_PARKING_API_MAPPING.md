# React admin ↔ backend: parking mapping

The React admin (`C:\Users\hp\Desktop\mygateweb`, package `mygateapp-admin`, read only) has **no** parking or vehicle
screens yet. This document maps the screens the society admin needs to the backend API added in V9, and lists the
changes the admin app needs. Full contract: [PARKING_API_CONTRACTS.md](PARKING_API_CONTRACTS.md). Nothing in the
React project was changed.

Conventions match the existing admin code: `api` axios instance (`src/services/apiClient.ts`), `X-Society-Id` from
the society switcher, `Page<T>` (`src/types/api.ts`), `ApiErrorBody` → form errors (`serverErrors.ts`). Money comes
as `{ amount: "120.00", currency: "INR" }` strings — format, never compute.

## 1. Required changes to existing React code (before any parking screen)

| File | Change | Why |
| --- | --- | --- |
| `src/features/guards/types.ts`, `src/features/guards/schemas.ts`, `src/types/labels.ts` | Add `'VEHICLE_ENTRY'` to `GuardPermission`, `ALL_GUARD_PERMISSIONS`, the `z.enum` in `schemas.ts` and `GUARD_PERMISSION_LABELS` (label "Vehicles & parking") | Current behaviour (found by reading the code, not run): the guard list and detail show an **empty badge** for `VEHICLE_ENTRY`; the **Edit guard form of a guard that has it cannot be saved** (the zod enum allows only three values); guards **created from React never get it** (the create form sends an explicit three-toggle list, which overrides the backend default of all toggles) |
| `src/types/labels.ts` | Add `PARKING_UPDATE` to `NOTIFICATION_TYPE_LABELS` | Parking notifications currently show without a type label (no crash) |
| `src/utils/permissions.ts` | Add the V9 society-admin permissions to `SOCIETY_ADMIN_PERMISSIONS`: `PARKING_VIEW`, `PARKING_SETTINGS_MANAGE`, `PARKING_INVENTORY_MANAGE`, `PARKING_ALLOCATION_MANAGE`, `VEHICLE_MANAGE`, `PARKING_RATE_MANAGE`, `PARKING_PAYMENT_MANAGE`, `PARKING_LOG_VIEW`, `PARKING_REPORT_VIEW` | Navigation gating (UI hint only; the backend checks every call) |
| `src/features/notifications/links.ts` | Route `PARKING_UPDATE` by `referenceType` (`VEHICLE`, `PARKING_REQUEST`, `PARKING_ALLOCATION`, `PARKING_PERMIT`, `PARKING_SESSION`, `PARKING_PAYMENT`) | Admins receive "vehicle waiting for approval", "parking request", "emergency entry" notifications |
| `src/components/layout/navigation.ts` | New "Parking" section (below) | |
| CORS | none | Already allowed via `CORS_ALLOWED_ORIGINS` |

## 2. Screens → endpoints

| Screen (suggested route) | Permission | Endpoints |
| --- | --- | --- |
| **Parking settings** (`/society/parking/settings`) | `PARKING_SETTINGS_MANAGE` | `GET` / `PUT /societies/{societyId}/parking/settings` (full replacement; response includes read-only `capacity`) |
| **Areas & slots** (`/society/parking/areas`, `/areas/:id`) | `PARKING_INVENTORY_MANAGE` | `GET /parking/areas?includeInactive=true`, `POST /parking/areas`, `PATCH /parking/areas/{id}`; `GET /parking/slots?areaId&usage&operationalStatus&vehicleType&q&page&size`, `POST /parking/slots`, `POST /parking/slots/bulk`, `PATCH /parking/slots/{id}` (reserve = `usage: RESERVED`; out of service = `operationalStatus: OUT_OF_SERVICE` + reason) |
| **Vehicles** (`/society/parking/vehicles`) — approval queue | `VEHICLE_MANAGE` | `GET /vehicles?status=PENDING&category&flatId&q&active&page&size&sort`, `GET /vehicles/{id}`, `POST /vehicles/{id}/approve` `{remarks?}`, `/reject` `{remarks}`, `/suspend` `{remarks}`, `PATCH /vehicles/{id}` |
| **Society vehicles** (society, staff, service, emergency) | `VEHICLE_MANAGE` | `POST /vehicles` `{registrationNumber, vehicleType, category, ownerName, make, model, color, numberFormat?}` (approved immediately), then **Issue permit** |
| **Requests** (`/society/parking/requests`) | `PARKING_ALLOCATION_MANAGE` | `GET /parking/requests?status=PENDING&type`, `GET /parking/visitor-requests?status`, `POST /parking/requests/{id}/approve` `{slotId?, remarks?}`, `/reject` `{remarks}`. Show `quote` for `PAID_PERMIT` (the permit waits for payment) |
| **Allocations** (`/society/parking/allocations`) | `PARKING_ALLOCATION_MANAGE` | `GET /parking/allocations?status&type&flatId&slotId&vehicleId`, `POST /parking/allocations`, `PATCH /parking/allocations/{id}` (extend, change vehicle, suspend/resume, `REVOKED` = cancel), `POST /parking/allocations/{id}/transfer` |
| **Permits** (`/society/parking/permits`) | `PARKING_ALLOCATION_MANAGE` | `GET /parking/permits?status&type&q`, `POST /parking/permits`, `POST /parking/permits/{id}/revoke` `{reason}` |
| **Gate log / sessions** (`/society/parking/sessions`) | `PARKING_LOG_VIEW` | `GET /parking/sessions?scope=ACTIVE\|OVERSTAY\|HISTORY&category&q&paid&from&to`, `GET /parking/sessions/{id}` (timeline in `history`), `POST /parking/sessions/{id}/cancel` `{reason}` (correct a wrong entry), `GET /parking/vehicles/search?q&by` |
| **Rates** (`/society/parking/rates`) | `PARKING_RATE_MANAGE` | `GET /parking/rates?includeInactive=true`, `POST /parking/rates`, `PATCH /parking/rates/{id}` (running sessions keep their price), `POST /parking/quotes` (preview) |
| **Payments** (`/society/parking/payments`) | `PARKING_PAYMENT_MANAGE` | `GET /parking/payments?status&purpose&sessionId&permitId&from&to`, `GET /parking/payments/{id}` (receipt + history), `POST /parking/payments/manual`, `/{id}/confirm` `{method, reference}`, `/{id}/fail` `{reason}`, `/{id}/refund` `{reason}`, `POST /parking/payments/waivers` `{sessionId \| permitId, reason}`, `GET /parking/receipts/{id}` |
| **Parking reports** (`/society/parking/reports`) | `PARKING_REPORT_VIEW` | `GET /parking/reports/overview`, `/occupancy`, `/sessions?from&to&category&status&page&size`, `/revenue?from&to` (local dates; reuse `DateRangeFilter`) |
| **Guard form** (existing) | `GUARD_MANAGE` | `VEHICLE_ENTRY` checkbox (see §1) |
| **Audit log** (existing) | `AUDIT_VIEW` | New actions: `VEHICLE_*`, `PARKING_AREA_*`, `PARKING_SLOT*`, `PARKING_ALLOCATION_*`, `PARKING_PERMIT_*`, `PARKING_REQUEST_*`, `VISITOR_PARKING_REQUESTED`, `PARKING_ENTRY`, `PARKING_ENTRY_OVERRIDE`, `PARKING_EXIT`, `PARKING_SESSION_CANCELLED`, `PARKING_RATE_*`, `PARKING_PAYMENT_*`, `PARKING_SETTINGS_UPDATED` |

Super admins use the same screens with a society selected (`X-Society-Id`); without it the backend answers
`400 SOCIETY_CONTEXT_REQUIRED`.

## 3. Suggested TypeScript types (field names = Java records)

```ts
export interface Money { amount: string; currency: string }
export interface AreaRef { id: UUID; name: string; level: string | null }
export interface SlotRef { id: UUID; number: string; area: AreaRef; vehicleTypes: VehicleType[] }
export type VehicleType = 'CAR' | 'BIKE' | 'SCOOTER' | 'OTHER'
export type VehicleCategory = 'RESIDENT' | 'VISITOR' | 'PAID' | 'SOCIETY' | 'STAFF' | 'SERVICE' | 'EMERGENCY'
export type ParkingUsage = 'RESIDENT' | 'VISITOR' | 'PAID' | 'SOCIETY' | 'STAFF' | 'SERVICE' | 'EMERGENCY' | 'COMMON' | 'RESERVED'
export type RegistrationStatus = 'NOT_SUBMITTED' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED'
export type PaymentMethod = 'CASH' | 'UPI_AT_GATE' | 'CARD_AT_GATE' | 'UPI' | 'BANK_TRANSFER' | 'ONLINE' | 'WAIVER'

export interface ParkingSettings {            // PUT body = all fields except societyId, onlinePaymentEnabled, capacity, updatedAt
  societyId: UUID
  visitorParkingEnabled: boolean; visitorParkingAutoApprove: boolean; maxVisitorParkingHours: number
  paidParkingEnabled: boolean; externalPaidParkingAllowed: boolean; freeGraceMinutes: number
  maxVehiclesPerFlat: number; maxTemporaryPermitDays: number
  residentEntryRequiresPermit: boolean; paymentRequiredBeforeExit: boolean; emergencyOverrideAllowed: boolean
  vehicleNumberFormat: 'INDIA' | 'GENERIC'
  allowedVehicleTypes: VehicleType[]           // [] = all
  acceptedPaymentMethods: PaymentMethod[]      // manual methods only
  currency: string; rules: string[]
  onlinePaymentEnabled: boolean                // always false (no provider)
  capacity: { totalSlots: number; residentSlots: number; visitorSlots: number; paidSlots: number; reservedSlots: number; outOfServiceSlots: number }
  updatedAt: Instant
}

export interface Slot {
  id: UUID; number: string; area: AreaRef; vehicleTypes: VehicleType[]; vehicleType: VehicleType; usage: ParkingUsage
  status: 'AVAILABLE' | 'OCCUPIED' | 'RESERVED' | 'BLOCKED'
  operationalStatus: 'ACTIVE' | 'OUT_OF_SERVICE'; allocated: boolean; occupied: boolean; reserved: boolean
  occupiedBy: string | null; currentAllocation: { id: UUID; allocationType: string; status: string; flat: FlatRef | null;
    vehicleNumber: string | null; validFrom: Instant; validUntil: Instant | null } | null
  outOfServiceReason: string | null; notes: string | null; createdAt: Instant; updatedAt: Instant
}
```

Other response shapes (vehicle, allocation, permit, request, session, rate, quote, payment, receipt, report DTOs)
are listed field by field in Swagger (`/v3/api-docs`, schemas `VehicleResponse`, `AllocationResponse`,
`PermitResponse`, `RequestResponse`, `SessionResponse`, `RateResponse`, `QuoteResponse`, `PaymentResponse`,
`ReceiptResponse`, `Overview`, `OccupancyReport`, `SessionsReport`, `RevenueReport`).

## 4. UI rules worth enforcing (the backend enforces them anyway)

| Rule | Error to map |
| --- | --- |
| Slot numbers unique per area; area code/name unique per society | `PARKING_SLOT_TAKEN`, `PARKING_AREA_TAKEN` |
| Slot type must be allowed by the area | `SLOT_NOT_SUITABLE` |
| No overlapping allocation of a slot or vehicle | `ALLOCATION_CONFLICT` |
| Out-of-service / reserved slots cannot be allocated for residents | `SLOT_OUT_OF_SERVICE`, `SLOT_NOT_SUITABLE` |
| Reject / suspend / refund / waive / fail need a reason | `VALIDATION_FAILED` (field `remarks` / `reason`) |
| Manual payment amount is the server's; leave it empty or show the quote | `PAYMENT_AMOUNT_MISMATCH` |
| One pending payment per session/permit: confirm it instead of recording another | `PAYMENT_INVALID_STATE` |
| `PAID_MONTHLY` permits come from requests, not "Issue permit" | `VALIDATION_FAILED` (field `type`) |
| Daily cap only on hourly rates | `VALIDATION_FAILED` (field `maxPerDay`) |
| Online payment methods cannot be accepted | `PAYMENT_METHOD_NOT_ACCEPTED` |

## 5. Navigation proposal

```ts
// societyNav, after "Deliveries"
{ label: 'Parking', to: '/society/parking/reports', icon: Car, permission: 'PARKING_REPORT_VIEW',
  matches: ['/society/parking'], keywords: 'vehicles slots permits allocations rates payments gate' }
```

Sub-navigation tabs: Overview (reports) · Vehicles · Requests · Areas & slots · Allocations · Permits · Gate log ·
Rates · Payments · Settings.

## 6. Status

| Item | State |
| --- | --- |
| Backend APIs listed here | Implemented; covered by backend integration tests (`ParkingInventoryIT`, `VehicleRegistrationIT`, `ParkingGateIT`, `ParkingPaymentIT`) |
| React screens | Not built (out of scope: React is read-only for this task) |
| React mock adapter (`src/mocks`) | Needs parking handlers if mock mode should show parking |
| End-to-end test with the admin UI | Not done |
