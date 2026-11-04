package com.mygateapp.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mygateapp.common.gate.GateMovementPolicy;
import com.mygateapp.complaints.entity.Complaint;
import com.mygateapp.deliveries.entity.Delivery;
import com.mygateapp.exception.ApiException;
import com.mygateapp.visitors.entity.Visitor;
import com.mygateapp.visitors.enums.VisitorEnums;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pure domain rules that do not need Spring or a database. */
class DomainRulesTest {

    @ParameterizedTest
    @CsvSource({
            "OPEN,IN_PROGRESS,true", "OPEN,RESOLVED,true", "OPEN,CLOSED,true",
            "IN_PROGRESS,RESOLVED,true", "IN_PROGRESS,OPEN,false",
            "RESOLVED,IN_PROGRESS,true", "RESOLVED,CLOSED,true", "RESOLVED,OPEN,false",
            "CLOSED,OPEN,false", "CLOSED,IN_PROGRESS,false", "CLOSED,RESOLVED,false"})
    void complaintTransitions(Complaint.Status from, Complaint.Status to, boolean allowed) {
        assertThat(Complaint.canTransition(from, to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @CsvSource({"98765 43210,9876543210", "+91 98765-43210,9876543210", "919876543210,9876543210", "6000000000,6000000000"})
    void indianMobileNumbersAreNormalised(String input, String expected) {
        assertThat(MobileNumbers.normalize("+91", input, "mobile")).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"12345", "5876543210", "98765432101", "abcdefghij"})
    void invalidIndianMobileNumbersAreRejected(String input) {
        assertThatThrownBy(() -> MobileNumbers.normalize("+91", input, "mobile"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors()).extracting("field").containsExactly("mobile"));
    }

    @Test
    void mobileMaskingKeepsOnlyTheLastFourDigits() {
        assertThat(MobileNumbers.mask("9876543210")).isEqualTo("******3210");
    }

    @Test
    void likePatternsEscapeWildcards() {
        assertThat(Strings.likePattern(" 50%_off ")).isEqualTo("%50\\%\\_off%");
        assertThat(Strings.likePattern("  ")).isNull();
    }

    @Test
    void visitorDisplayStatusCombinesApprovalMovementAndSource() {
        Visitor walkIn = new Visitor();
        walkIn.setSource(VisitorEnums.Source.WALK_IN);
        walkIn.setApprovalStatus(VisitorEnums.ApprovalStatus.APPROVED);
        assertThat(walkIn.displayStatus()).isEqualTo(VisitorEnums.DisplayStatus.APPROVED);

        Visitor invite = new Visitor();
        invite.setSource(VisitorEnums.Source.INVITATION);
        invite.setApprovalStatus(VisitorEnums.ApprovalStatus.APPROVED);
        assertThat(invite.displayStatus()).isEqualTo(VisitorEnums.DisplayStatus.PRE_APPROVED);

        GateMovementPolicy.recordEntry(invite.getMovement(), Instant.now(), UUID.randomUUID(), UUID.randomUUID(),
                GateMovementPolicy.VISITOR);
        assertThat(invite.displayStatus()).isEqualTo(VisitorEnums.DisplayStatus.CHECKED_IN);

        Visitor rejected = new Visitor();
        rejected.setSource(VisitorEnums.Source.WALK_IN);
        rejected.setApprovalStatus(VisitorEnums.ApprovalStatus.REJECTED);
        assertThat(rejected.displayStatus()).isEqualTo(VisitorEnums.DisplayStatus.REJECTED);
    }

    @Test
    void deliveryDisplayStatusFollowsMovement() {
        Delivery delivery = new Delivery();
        delivery.setApprovalStatus(Delivery.ApprovalStatus.LEFT_AT_GATE);
        assertThat(delivery.displayStatus()).isEqualTo(Delivery.DisplayStatus.LEFT_AT_GATE);
        delivery.setApprovalStatus(Delivery.ApprovalStatus.APPROVED);
        GateMovementPolicy.recordEntry(delivery.getMovement(), Instant.now(), UUID.randomUUID(), UUID.randomUUID(),
                GateMovementPolicy.DELIVERY);
        assertThat(delivery.displayStatus()).isEqualTo(Delivery.DisplayStatus.ENTERED);
    }

    @Test
    void invitationWindowIsInclusive() {
        Visitor invite = new Visitor();
        Instant from = Instant.parse("2026-11-04T10:00:00Z");
        invite.setValidFrom(from);
        invite.setValidUntil(from.plusSeconds(3600));
        assertThat(invite.isWithinWindow(from)).isTrue();
        assertThat(invite.isWithinWindow(from.plusSeconds(3600))).isTrue();
        assertThat(invite.isWithinWindow(from.minusSeconds(1))).isFalse();
        assertThat(invite.isWithinWindow(from.plusSeconds(3601))).isFalse();
    }
}
