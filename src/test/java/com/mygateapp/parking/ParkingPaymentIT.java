package com.mygateapp.parking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mygateapp.support.World;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Paid parking: rates, server quotes, gate and office payments, receipts, refunds, waivers, paid permits, reports. */
class ParkingPaymentIT extends ParkingTestSupport {

    World w;
    String hourlyRateId;

    @BeforeEach
    void setUp() throws Exception {
        w = worlds().build("Paid Society");
        putSettings(w, Map.of("paidParkingEnabled", true, "externalPaidParkingAllowed", true));
        UUID paidArea = area(w, "P", "PAID", "CAR");
        slot(w, paidArea, "P-01", "CAR");
        slot(w, paidArea, "P-02", "CAR");
        hourlyRateId = sendOk("POST", "/api/v1/parking/rates", Map.of("code", "PUB-CAR-HR", "name", "Public car hourly",
                "appliesTo", "ALL", "billingUnit", "HOURLY", "amount", "30.00", "gracePeriodMinutes", 15,
                "taxPercent", "18.00", "taxLabel", "GST 18%", "vehicleType", "CAR"), w.adminToken()).get("id").asString();
    }

    private JsonNode startPaidSession(String number) throws Exception {
        JsonNode check = verify(w.guardToken(), number);
        assertThat(text(check, "decision")).isEqualTo("NOT_REGISTERED");
        assertThat(check.get("paidParkingOffered").asBoolean()).isTrue();
        assertThat(check.get("rates").valueStream().map(r -> r.get("id").asString())).contains(hourlyRateId);
        // Same body as the Flutter app: no vehicleType; the server derives it from the CAR rate.
        Map<String, Object> body = entryBody(check, "PAID");
        body.put("rateId", hourlyRateId);
        JsonNode session = sendOk("POST", "/api/v1/parking/sessions", body, w.guardToken());
        assertThat(text(session, "vehicleType")).isEqualTo("CAR");
        return session;
    }

    private JsonNode quote(String sessionId, String token) throws Exception {
        return sendOk("POST", "/api/v1/parking/quotes", Map.of("sessionId", sessionId), token);
    }

    @Test
    void paidSessionIsChargedOnTheServerAndPaidBeforeExit() throws Exception {
        JsonNode session = startPaidSession("MH01PD1234");
        String id = session.get("id").asString();
        assertThat(text(session, "category")).isEqualTo("PAID");
        assertThat(text(session, "slot.number")).startsWith("P-0");
        assertThat(text(session, "charges.paymentStatus")).isEqualTo("PENDING");
        assertThat(text(session, "charges.rate.amount.amount")).isEqualTo("30.00");

        clock.advance(Duration.ofMinutes(125)); // 2 h 5 min: second hour's 5 min are within the 15 min grace
        send("POST", "/api/v1/parking/sessions/" + id + "/close", Map.of(), w.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_PENDING"));
        JsonNode blocked = sendOk("GET", "/api/v1/parking/sessions/" + id, null, w.guardToken());
        assertThat(blocked.get("exitAllowed").asBoolean()).isFalse();
        assertThat(text(blocked, "charges.outstanding.amount")).isEqualTo("70.80");

        JsonNode quote = quote(id, w.guardToken());
        assertThat(text(quote, "subtotal.amount")).isEqualTo("60.00");
        assertThat(text(quote, "tax.amount")).isEqualTo("10.80");
        assertThat(text(quote, "total.amount")).isEqualTo("70.80");
        assertThat(text(quote, "total.currency")).isEqualTo("INR");
        assertThat(text(quote, "taxLabel")).isEqualTo("GST 18%");
        assertThat(quote.get("lines").get(0).get("label").asString()).isEqualTo("2 h × ₹30.00");

        String key = UUID.randomUUID().toString();
        Map<String, Object> pay = Map.of("quoteId", quote.get("id").asString(), "method", "CASH");
        JsonNode payment = objectMapper.readTree(postWithKey("/api/v1/parking/sessions/" + id + "/payments", pay,
                w.guardToken(), key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(text(payment, "status")).isEqualTo("PAID");
        assertThat(text(payment, "amount.amount")).isEqualTo("70.80");
        assertThat(text(payment, "receipt.receiptNumber")).contains("-RC-");
        assertThat(text(payment, "receipt.total.amount")).isEqualTo("70.80");
        assertThat(text(payment, "recordedBy")).isEqualTo("Ramesh Yadav");
        // A retried request with the same key returns the same payment; a new key cannot pay twice.
        JsonNode replay = objectMapper.readTree(postWithKey("/api/v1/parking/sessions/" + id + "/payments", pay,
                w.guardToken(), key).andReturn().getResponse().getContentAsString());
        assertThat(replay.get("id")).isEqualTo(payment.get("id"));
        send("POST", "/api/v1/parking/sessions/" + id + "/payments", pay, w.guardToken()).andExpect(status().isConflict());

        JsonNode closed = close(w.guardToken(), id);
        assertThat(text(closed, "status")).isEqualTo("COMPLETED");
        assertThat(text(closed, "charges.final.amount")).isEqualTo("70.80");
        assertThat(text(closed, "charges.paymentStatus")).isEqualTo("PAID");
        JsonNode receipt = sendOk("GET", "/api/v1/parking/receipts/" + text(payment, "receipt.id"), null, w.adminToken());
        assertThat(text(receipt, "societyName")).isEqualTo("Paid Society");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parking_payments WHERE session_id = ?::uuid", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void staleQuotesAndClientAmountsAreRejected() throws Exception {
        JsonNode session = startPaidSession("MH01PD2222");
        String id = session.get("id").asString();
        clock.advance(Duration.ofMinutes(125));
        JsonNode quote = quote(id, w.guardToken());
        clock.advance(Duration.ofMinutes(15)); // 2 h 20 min -> a third hour is now due
        send("POST", "/api/v1/parking/sessions/" + id + "/payments", Map.of("quoteId", quote.get("id").asString(),
                "method", "CASH"), w.guardToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
        // Only gate methods at the gate.
        JsonNode fresh = quote(id, w.guardToken());
        assertThat(text(fresh, "total.amount")).isEqualTo("106.20");
        send("POST", "/api/v1/parking/sessions/" + id + "/payments", Map.of("quoteId", fresh.get("id").asString(),
                "method", "BANK_TRANSFER"), w.guardToken())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_METHOD_NOT_ACCEPTED"));
        // The office cannot record an amount different from the server's.
        send("POST", "/api/v1/parking/payments/manual", Map.of("sessionId", id, "method", "UPI", "amount", "50.00"),
                w.adminToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
    }

    @Test
    void manualPaymentsNeedAnAdministratorAndKeepTheirHistory() throws Exception {
        JsonNode session = startPaidSession("MH01PD3333");
        String id = session.get("id").asString();
        clock.advance(Duration.ofMinutes(61));
        Map<String, Object> body = Map.of("sessionId", id, "method", "UPI", "reference", "UPI-REF-42");
        send("POST", "/api/v1/parking/payments/manual", body, w.resident101Token()).andExpect(status().isForbidden());
        send("POST", "/api/v1/parking/payments/manual", body, w.guardToken()).andExpect(status().isForbidden());
        JsonNode payment = sendOk("POST", "/api/v1/parking/payments/manual", body, w.adminToken());
        assertThat(text(payment, "status")).isEqualTo("PAID");
        assertThat(text(payment, "amount.amount")).isEqualTo("35.40");
        assertThat(text(payment, "recordedBy")).isEqualTo("Society Admin");
        assertThat(text(payment, "confirmedBy")).isEqualTo("Society Admin");
        assertThat(payment.get("history").valueStream().map(e -> e.get("type").asString()))
                .containsExactly("CREATED", "CONFIRMED");
        assertThat(payment.get("history").get(1).get("actor").asString()).isEqualTo("Society Admin");
        // Payment events cannot be rewritten.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "UPDATE parking_payment_events SET amount = 0 WHERE payment_id = ?::uuid", payment.get("id").asString()))
                .hasMessageContaining("immutable");
        JsonNode audit = sendOk("GET", "/api/v1/audit-logs?action=PARKING_PAYMENT_CONFIRMED", null, w.adminToken());
        assertThat(audit.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void societyMayLetVehiclesLeaveAndCollectLater() throws Exception {
        putSettings(w, Map.of("paidParkingEnabled", true, "externalPaidParkingAllowed", true,
                "paymentRequiredBeforeExit", false));
        JsonNode session = startPaidSession("MH01PD4444");
        String id = session.get("id").asString();
        clock.advance(Duration.ofMinutes(50));
        JsonNode closed = close(w.guardToken(), id);
        assertThat(text(closed, "status")).isEqualTo("COMPLETED");
        assertThat(text(closed, "charges.paymentStatus")).isEqualTo("PENDING");
        assertThat(text(closed, "charges.final.amount")).isEqualTo("35.40");

        JsonNode pending = sendOk("GET", "/api/v1/parking/payments?status=PENDING", null, w.adminToken());
        assertThat(pending.get("totalElements").asLong()).isEqualTo(1);
        String paymentId = pending.get("content").get(0).get("id").asString();
        // A second manual payment is refused while one is pending.
        send("POST", "/api/v1/parking/payments/manual", Map.of("sessionId", id, "method", "CASH"), w.adminToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_INVALID_STATE"));
        JsonNode confirmed = sendOk("POST", "/api/v1/parking/payments/" + paymentId + "/confirm",
                Map.of("method", "BANK_TRANSFER", "reference", "UTR0001"), w.adminToken());
        assertThat(text(confirmed, "status")).isEqualTo("PAID");
        assertThat(text(sendOk("GET", "/api/v1/parking/sessions/" + id, null, w.adminToken()), "charges.paymentStatus"))
                .isEqualTo("PAID");

        // Refund (with reason) and the revenue report.
        JsonNode refunded = sendOk("POST", "/api/v1/parking/payments/" + paymentId + "/refund",
                Map.of("reason", "Charged by mistake"), w.adminToken());
        assertThat(text(refunded, "status")).isEqualTo("REFUNDED");
        assertThat(text(sendOk("GET", "/api/v1/parking/sessions/" + id, null, w.adminToken()), "charges.paymentStatus"))
                .isEqualTo("PENDING");
        JsonNode waived = sendOk("POST", "/api/v1/parking/payments/waivers", Map.of("sessionId", id,
                "reason", "Goodwill"), w.adminToken());
        assertThat(text(waived, "status")).isEqualTo("WAIVED");
        assertThat(text(waived, "method")).isEqualTo("WAIVER");
        assertThat(text(sendOk("GET", "/api/v1/parking/sessions/" + id, null, w.adminToken()), "charges.paymentStatus"))
                .isEqualTo("WAIVED");

        JsonNode revenue = sendOk("GET", "/api/v1/parking/reports/revenue", null, w.adminToken());
        assertThat(text(revenue, "collected.amount")).isEqualTo("35.40");
        assertThat(text(revenue, "refunded.amount")).isEqualTo("35.40");
        assertThat(text(revenue, "net.amount")).isEqualTo("0.00");
        assertThat(text(revenue, "waived.amount")).isEqualTo("35.40");
    }

    @Test
    void paidMonthlyPermitActivatesOnlyAfterPayment() throws Exception {
        String monthly = sendOk("POST", "/api/v1/parking/rates", Map.of("code", "RES-MONTHLY", "name", "Resident monthly",
                "appliesTo", "RESIDENT", "billingUnit", "MONTHLY", "amount", "1500.00"), w.adminToken()).get("id").asString();
        UUID vehicle = approvedVehicle(w, w.resident101Token(), w.flat101(), "MH12PM0001");
        JsonNode request = sendOk("POST", "/api/v1/parking/requests", Map.of("type", "PAID_PERMIT", "flatId", w.flat101(),
                "vehicleId", vehicle, "rateId", monthly, "reason", "Second car"), w.resident101Token());
        assertThat(text(request, "status")).isEqualTo("PENDING");
        send("POST", "/api/v1/parking/requests", Map.of("type", "PAID_PERMIT", "flatId", w.flat101(),
                "vehicleId", vehicle, "rateId", monthly), w.resident101Token())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_ALREADY_PENDING"));
        // Hourly rates are not monthly permits.
        send("POST", "/api/v1/parking/requests", Map.of("type", "PAID_PERMIT", "flatId", w.flat101(),
                "vehicleId", vehicle, "rateId", hourlyRateId), w.resident101Token()).andExpect(status().isConflict());

        String requestId = request.get("id").asString();
        send("POST", "/api/v1/parking/requests/" + requestId + "/approve", Map.of(), w.resident101Token())
                .andExpect(status().isForbidden());
        JsonNode approved = sendOk("POST", "/api/v1/parking/requests/" + requestId + "/approve", Map.of(), w.adminToken());
        assertThat(text(approved, "status")).isEqualTo("APPROVED");
        assertThat(text(approved, "permit.status")).isEqualTo("PENDING_PAYMENT");
        assertThat(text(approved, "quote.total.amount")).isEqualTo("1500.00");
        assertThat(text(approved, "assignedSlot.number")).startsWith("P-0");

        assertThat(text(verify(w.guardToken(), "MH12PM0001"), "decision")).isEqualTo("DENIED");
        JsonNode summary = sendOk("GET", "/api/v1/parking/summary", null, w.resident101Token());
        assertThat(text(summary, "unpaidAmount.amount")).isEqualTo("1500.00");
        assertThat(summary.get("alerts").valueStream().map(a -> a.get("type").asString())).contains("PAYMENT_DUE");

        JsonNode payments = sendOk("GET", "/api/v1/parking/payments", null, w.resident101Token());
        assertThat(payments.get("totalElements").asLong()).isEqualTo(1);
        String paymentId = payments.get("content").get(0).get("id").asString();
        send("GET", "/api/v1/parking/payments/" + paymentId, null, w.resident102Token()).andExpect(status().isNotFound());
        sendOk("POST", "/api/v1/parking/payments/" + paymentId + "/confirm", Map.of("method", "CASH"), w.adminToken());

        JsonNode check = verify(w.guardToken(), "MH12PM0001");
        assertThat(text(check, "decision")).isEqualTo("ALLOWED");
        assertThat(text(check, "permit.type")).isEqualTo("PAID_MONTHLY");
        assertThat(text(check, "slot.number")).startsWith("P-0");
        JsonNode permits = sendOk("GET", "/api/v1/parking/permits/mine", null, w.resident101Token());
        assertThat(permits.valueStream().anyMatch(p -> p.get("status").asString().equals("ACTIVE")
                && p.get("type").asString().equals("PAID_MONTHLY"))).isTrue();
        assertThat(text(sendOk("GET", "/api/v1/parking/payments/" + paymentId, null, w.resident101Token()),
                "receipt.payerName")).isNotNull();
    }

    @Test
    void ratesAreAdminManagedAndVisibleToMembers() throws Exception {
        send("POST", "/api/v1/parking/rates", Map.of("code", "XX", "name", "X", "appliesTo", "ALL",
                "billingUnit", "HOURLY", "amount", "10.00"), w.resident101Token()).andExpect(status().isForbidden());
        send("POST", "/api/v1/parking/rates", Map.of("code", "PUB-CAR-HR", "name", "Duplicate", "appliesTo", "ALL",
                "billingUnit", "HOURLY", "amount", "10.00"), w.adminToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RATE_CODE_TAKEN"));
        send("POST", "/api/v1/parking/rates", Map.of("code", "NEG", "name", "Negative", "appliesTo", "ALL",
                "billingUnit", "HOURLY", "amount", "-1"), w.adminToken()).andExpect(status().isBadRequest());
        JsonNode rates = sendOk("GET", "/api/v1/parking/rates?vehicleType=CAR", null, w.resident101Token());
        assertThat(rates.size()).isEqualTo(1);
        assertThat(text(rates.get(0), "amount.amount")).isEqualTo("30.00");
        assertThat(text(rates.get(0), "maxPerDay")).isNull();

        // An estimate is computed by the server; a rate change does not touch running sessions.
        JsonNode session = startPaidSession("MH01PD5555");
        sendOk("PATCH", "/api/v1/parking/rates/" + hourlyRateId, Map.of("amount", "100.00"), w.adminToken());
        clock.advance(Duration.ofMinutes(61));
        assertThat(text(quote(session.get("id").asString(), w.guardToken()), "subtotal.amount")).isEqualTo("30.00");
        Map<String, Object> estimate = new HashMap<>();
        estimate.put("rateId", hourlyRateId);
        estimate.put("vehicleType", "CAR");
        estimate.put("from", "2026-12-01T10:00:00Z");
        estimate.put("until", "2026-12-01T13:00:00Z");
        JsonNode quote = sendOk("POST", "/api/v1/parking/quotes", estimate, w.resident101Token());
        assertThat(text(quote, "subtotal.amount")).isEqualTo("300.00");
        assertThat(quote.get("billableMinutes").asInt()).isEqualTo(180);
    }

    @Test
    void reportsAreSocietyScopedAndAdminOnly() throws Exception {
        World other = worlds().build("Report Neighbour");
        JsonNode session = startPaidSession("MH01PD6666");
        clock.advance(Duration.ofMinutes(61));
        sendOk("POST", "/api/v1/parking/payments/manual", Map.of("sessionId", session.get("id").asString(),
                "method", "CASH"), w.adminToken());

        JsonNode overview = sendOk("GET", "/api/v1/parking/reports/overview", null, w.adminToken());
        assertThat(overview.get("slots").get("total").asInt()).isEqualTo(2);
        assertThat(overview.get("slots").get("occupied").asInt()).isEqualTo(1);
        assertThat(overview.get("vehiclesInside").asLong()).isEqualTo(1);
        assertThat(text(overview, "revenueThisMonth.amount")).isEqualTo("35.40");
        JsonNode occupancy = sendOk("GET", "/api/v1/parking/reports/occupancy", null, w.adminToken());
        assertThat(occupancy.get("areas").get(0).get("occupancyPercent").asDouble()).isEqualTo(50.0);
        JsonNode sessions = sendOk("GET", "/api/v1/parking/reports/sessions", null, w.adminToken());
        assertThat(sessions.get("totalSessions").asLong()).isEqualTo(1);
        assertThat(sessions.get("byCategory").get("PAID").asLong()).isEqualTo(1);
        assertThat(sessions.get("paidSessions").asLong()).isEqualTo(1);
        JsonNode revenue = sendOk("GET", "/api/v1/parking/reports/revenue", null, w.adminToken());
        assertThat(revenue.get("byMethod").get(0).get("key").asString()).isEqualTo("CASH");
        assertThat(revenue.get("daily").size()).isEqualTo(1);

        send("GET", "/api/v1/parking/reports/revenue", null, w.resident101Token()).andExpect(status().isForbidden());
        send("GET", "/api/v1/parking/reports/overview", null, w.guardToken()).andExpect(status().isForbidden());
        JsonNode otherOverview = sendOk("GET", "/api/v1/parking/reports/overview", null, other.adminToken());
        assertThat(otherOverview.get("vehiclesInside").asLong()).isZero();
        assertThat(text(otherOverview, "revenueThisMonth.amount")).isEqualTo("0.00");
        assertThat(sendOk("GET", "/api/v1/parking/payments", null, other.adminToken()).get("totalElements").asLong()).isZero();
        send("GET", "/api/v1/parking/payments/" + UUID.randomUUID(), null, w.adminToken()).andExpect(status().isNotFound());
        assertThat(List.of(sessions.get("from").asString(), sessions.get("to").asString())).doesNotContainNull();
    }
}
