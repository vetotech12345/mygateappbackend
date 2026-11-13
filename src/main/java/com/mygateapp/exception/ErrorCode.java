package com.mygateapp.exception;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes. The Flutter app shows {@code message} as-is and branches on {@code code}
 * (e.g. {@code OTP_EXPIRED}, {@code GUARD_OFF_DUTY}), so codes are part of the public API contract.
 */
public enum ErrorCode {

    // Generic
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Please check the highlighted fields."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "The request is invalid."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "The request body could not be read."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Please sign in to continue."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "Your session has expired. Please log in again."),
    SESSION_REVOKED(HttpStatus.UNAUTHORIZED, "Your session has ended. Please log in again."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You don't have permission to do that."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "We couldn't find what you were looking for."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "This operation is not supported."),
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, "This item was updated by someone else. Refresh and try again."),
    DATA_CONFLICT(HttpStatus.CONFLICT, "This change conflicts with existing data."),
    IDEMPOTENCY_KEY_IN_USE(HttpStatus.CONFLICT, "This request is already being processed."),
    IDEMPOTENCY_KEY_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "This Idempotency-Key was used for a different request."),
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "The file is too large."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This file or content type is not supported."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please wait a moment and try again."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side. Please try again shortly."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable. Please try again shortly."),

    // Authentication
    USER_NOT_REGISTERED(HttpStatus.NOT_FOUND, "This number is not registered with any society. Please contact your society office."),
    USER_INACTIVE(HttpStatus.FORBIDDEN, "This account is inactive. Please contact your society office to reactivate it."),
    NO_ACTIVE_MEMBERSHIP(HttpStatus.FORBIDDEN, "Your account is not linked to an active society yet. Please contact your society office."),
    OTP_INVALID(HttpStatus.BAD_REQUEST, "Incorrect code."),
    OTP_EXPIRED(HttpStatus.BAD_REQUEST, "This code has expired. Request a new code."),
    OTP_LOCKED(HttpStatus.BAD_REQUEST, "Too many incorrect attempts. Request a new code."),
    OTP_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many code requests. Please try again later."),
    OTP_DELIVERY_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "We couldn't send the code right now. Please try again shortly."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Your session has expired. Please log in again."),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "Your session has ended for security reasons. Please log in again."),

    // Users
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "This user does not exist."),

    // Societies
    SOCIETY_NOT_FOUND(HttpStatus.NOT_FOUND, "This society does not exist."),
    SOCIETY_INACTIVE(HttpStatus.FORBIDDEN, "This society is not active."),
    SOCIETY_CODE_TAKEN(HttpStatus.CONFLICT, "This society code is already in use."),
    SOCIETY_CONTEXT_REQUIRED(HttpStatus.BAD_REQUEST, "Select a society (X-Society-Id header or societyId parameter)."),
    SOCIETY_ACCESS_DENIED(HttpStatus.FORBIDDEN, "You don't have access to this society."),

    // Structure
    TOWER_NOT_FOUND(HttpStatus.NOT_FOUND, "This tower does not exist."),
    TOWER_CODE_TAKEN(HttpStatus.CONFLICT, "A tower with this code already exists in the society."),
    WING_NOT_FOUND(HttpStatus.NOT_FOUND, "This wing does not exist."),
    WING_NAME_TAKEN(HttpStatus.CONFLICT, "A wing with this name already exists in the tower."),
    FLAT_NOT_FOUND(HttpStatus.NOT_FOUND, "This flat does not exist."),
    FLAT_NUMBER_TAKEN(HttpStatus.CONFLICT, "A flat with this number already exists in the tower."),
    INVALID_FLAT(HttpStatus.BAD_REQUEST, "Select a valid destination flat."),

    // Residents
    RESIDENT_NOT_FOUND(HttpStatus.NOT_FOUND, "This resident record does not exist."),
    RESIDENT_ALREADY_LINKED(HttpStatus.CONFLICT, "This person is already linked to the flat."),
    RESIDENT_NOT_PENDING(HttpStatus.CONFLICT, "This resident request has already been handled."),
    RESIDENT_NOT_ACTIVE(HttpStatus.CONFLICT, "This resident membership is not active."),
    NOT_FLAT_RESIDENT(HttpStatus.FORBIDDEN, "You can only do this for your own flat."),
    NOT_FLAT_OWNER(HttpStatus.FORBIDDEN, "Only the flat owner can add residents."),
    FAMILY_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "This family member does not exist."),

    // Guards and gates
    GUARD_NOT_FOUND(HttpStatus.NOT_FOUND, "This guard does not exist."),
    GUARD_ALREADY_EXISTS(HttpStatus.CONFLICT, "This person is already a guard in the society."),
    NOT_A_GUARD(HttpStatus.FORBIDDEN, "Only security staff can do this."),
    GUARD_OFF_DUTY(HttpStatus.FORBIDDEN, "Start your shift before recording gate entries."),
    GUARD_NO_GATE(HttpStatus.FORBIDDEN, "You are not assigned to a gate. Contact your supervisor."),
    GUARD_PERMISSION_DENIED(HttpStatus.FORBIDDEN, "You are not permitted to record this. Contact your supervisor."),
    GATE_NOT_FOUND(HttpStatus.NOT_FOUND, "This gate does not exist."),
    GATE_NAME_TAKEN(HttpStatus.CONFLICT, "A gate with this name already exists."),
    GATE_INACTIVE(HttpStatus.BAD_REQUEST, "This gate is not active."),

    // Visitors
    VISITOR_NOT_FOUND(HttpStatus.NOT_FOUND, "This visitor record no longer exists."),
    VISITOR_ALREADY_DECIDED(HttpStatus.CONFLICT, "This request has already been handled."),
    VISITOR_REJECTED(HttpStatus.FORBIDDEN, "Entry is not allowed: the resident rejected this visitor."),
    VISITOR_PENDING_APPROVAL(HttpStatus.CONFLICT, "Waiting for the resident to approve this visitor."),
    VISITOR_ALREADY_INSIDE(HttpStatus.CONFLICT, "Entry has already been recorded for this visitor."),
    VISITOR_ALREADY_EXITED(HttpStatus.CONFLICT, "Exit has already been recorded for this visitor."),
    VISITOR_NOT_INSIDE(HttpStatus.CONFLICT, "This visitor is not inside the premises."),
    VISIT_CLOSED(HttpStatus.CONFLICT, "This visit is closed. Create a new entry instead."),
    VISITOR_NOT_CANCELLABLE(HttpStatus.CONFLICT, "Only pending requests or upcoming invites can be cancelled."),
    INVITE_NOT_FOUND(HttpStatus.NOT_FOUND, "No invite matches this code. Check the code with the visitor."),
    INVITE_NOT_VALID_NOW(HttpStatus.BAD_REQUEST, "This invite is not valid at this time."),
    INVITE_EXPIRED(HttpStatus.BAD_REQUEST, "This invite has expired."),
    INVITE_CANCELLED(HttpStatus.BAD_REQUEST, "This invite was cancelled by the resident."),
    INVITE_ALREADY_USED(HttpStatus.CONFLICT, "This invite has already been used."),
    INVALID_INVITE_WINDOW(HttpStatus.BAD_REQUEST, "The invite time window is not valid."),

    // Deliveries
    DELIVERY_NOT_FOUND(HttpStatus.NOT_FOUND, "This delivery record no longer exists."),
    DELIVERY_ALREADY_DECIDED(HttpStatus.CONFLICT, "This delivery has already been handled."),
    DELIVERY_REJECTED(HttpStatus.FORBIDDEN, "Entry is not allowed: the resident rejected this delivery."),
    DELIVERY_NOT_APPROVED(HttpStatus.CONFLICT, "This delivery is not approved for entry."),
    DELIVERY_ALREADY_INSIDE(HttpStatus.CONFLICT, "Entry has already been recorded for this delivery."),
    DELIVERY_ALREADY_EXITED(HttpStatus.CONFLICT, "Exit has already been recorded for this delivery."),
    DELIVERY_NOT_INSIDE(HttpStatus.CONFLICT, "The delivery agent is not inside."),

    // Domestic staff
    STAFF_NOT_FOUND(HttpStatus.NOT_FOUND, "This staff member is not registered."),
    STAFF_INACTIVE(HttpStatus.CONFLICT, "This staff member is inactive."),
    STAFF_ALREADY_INSIDE(HttpStatus.CONFLICT, "Check-in already recorded. Check them out first."),
    STAFF_NOT_INSIDE(HttpStatus.CONFLICT, "Check-out already recorded."),
    STAFF_MOBILE_TAKEN(HttpStatus.CONFLICT, "A staff member with this mobile number is already registered."),
    STAFF_PASS_TAKEN(HttpStatus.CONFLICT, "This pass number is already in use."),

    // Complaints
    COMPLAINT_NOT_FOUND(HttpStatus.NOT_FOUND, "This complaint no longer exists."),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "This status change is not allowed."),
    COMPLAINT_NOT_EDITABLE(HttpStatus.CONFLICT, "Only open complaints can be edited."),
    INVALID_ASSIGNEE(HttpStatus.BAD_REQUEST, "Complaints can only be assigned to administrators of the society."),

    // Notices and notifications
    NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "This notice no longer exists."),
    NOTICE_ALREADY_PUBLISHED(HttpStatus.CONFLICT, "This notice is already published."),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "This notification no longer exists."),
    DEVICE_NOT_FOUND(HttpStatus.NOT_FOUND, "This device is not registered."),

    // Files
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "This file does not exist."),
    INVALID_FILE(HttpStatus.BAD_REQUEST, "The file is empty or not a valid image."),
    FILE_LINK_INVALID(HttpStatus.FORBIDDEN, "This file link is invalid or has expired."),

    // Vehicles
    VEHICLE_NOT_FOUND(HttpStatus.NOT_FOUND, "This vehicle is not registered."),
    INVALID_VEHICLE_NUMBER(HttpStatus.BAD_REQUEST, "Enter a valid vehicle number."),
    VEHICLE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "This vehicle number is already registered in the society."),
    VEHICLE_LIMIT_REACHED(HttpStatus.UNPROCESSABLE_CONTENT, "Your flat has reached the maximum number of vehicles."),
    VEHICLE_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "This vehicle type is not allowed in the society."),
    VEHICLE_NOT_SUBMITTABLE(HttpStatus.CONFLICT, "Only saved or rejected vehicles can be sent for approval."),
    VEHICLE_NOT_PENDING(HttpStatus.CONFLICT, "This vehicle is not waiting for approval."),
    VEHICLE_NOT_APPROVED(HttpStatus.CONFLICT, "This vehicle is not approved by the society office."),
    VEHICLE_INACTIVE(HttpStatus.CONFLICT, "This vehicle is inactive."),

    // Parking configuration and inventory
    PARKING_FEATURE_DISABLED(HttpStatus.CONFLICT, "This parking feature is not enabled in your society."),
    PARKING_AREA_NOT_FOUND(HttpStatus.NOT_FOUND, "This parking area does not exist."),
    PARKING_AREA_TAKEN(HttpStatus.CONFLICT, "A parking area with this code or name already exists."),
    PARKING_AREA_INACTIVE(HttpStatus.CONFLICT, "This parking area is not active."),
    PARKING_SLOT_NOT_FOUND(HttpStatus.NOT_FOUND, "This parking slot does not exist."),
    PARKING_SLOT_TAKEN(HttpStatus.CONFLICT, "A slot with this number already exists in the area."),
    SLOT_OUT_OF_SERVICE(HttpStatus.CONFLICT, "This parking slot is out of service."),
    SLOT_NOT_SUITABLE(HttpStatus.CONFLICT, "This slot is not meant for this vehicle or purpose."),
    SLOT_OCCUPIED(HttpStatus.CONFLICT, "Another vehicle is parked in this slot."),
    SLOT_RESERVED(HttpStatus.CONFLICT, "This slot is reserved for someone else."),
    INVALID_PARKING_WINDOW(HttpStatus.BAD_REQUEST, "The parking time window is not valid."),

    // Allocations, permits, requests
    ALLOCATION_NOT_FOUND(HttpStatus.NOT_FOUND, "This parking allocation does not exist."),
    ALLOCATION_CONFLICT(HttpStatus.CONFLICT, "The slot is already allocated for this period."),
    ALLOCATION_NOT_ACTIVE(HttpStatus.CONFLICT, "This allocation is no longer active."),
    PERMIT_NOT_FOUND(HttpStatus.NOT_FOUND, "This parking permit does not exist."),
    PERMIT_NOT_ACTIVE(HttpStatus.CONFLICT, "This permit is no longer active."),
    PARKING_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "This parking request does not exist."),
    REQUEST_ALREADY_PENDING(HttpStatus.CONFLICT, "A similar request is already waiting for the society office."),
    REQUEST_ALREADY_DECIDED(HttpStatus.CONFLICT, "This request has already been handled."),
    REQUEST_NOT_CANCELLABLE(HttpStatus.CONFLICT, "Only pending requests can be cancelled."),
    PARKING_UNAVAILABLE(HttpStatus.CONFLICT, "No suitable parking slot is free for this time."),

    // Gate sessions
    PARKING_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "This parking session does not exist."),
    SESSION_ALREADY_ACTIVE(HttpStatus.CONFLICT, "This vehicle is already inside."),
    SESSION_ALREADY_CLOSED(HttpStatus.CONFLICT, "This parking session is already closed."),
    VERIFICATION_EXPIRED(HttpStatus.CONFLICT, "This verification has expired or was already used. Verify the vehicle again."),
    ENTRY_NOT_AUTHORIZED(HttpStatus.FORBIDDEN, "This vehicle is not authorised to enter."),
    GATE_NOT_FOR_VEHICLES(HttpStatus.FORBIDDEN, "Vehicles cannot be recorded at this gate."),
    PAYMENT_PENDING(HttpStatus.CONFLICT, "Parking charges are outstanding. Collect the payment before exit."),

    // Rates, quotes, payments
    RATE_NOT_FOUND(HttpStatus.NOT_FOUND, "This parking rate does not exist."),
    RATE_CODE_TAKEN(HttpStatus.CONFLICT, "A rate with this code already exists."),
    RATE_NOT_APPLICABLE(HttpStatus.CONFLICT, "This rate does not apply here."),
    QUOTE_NOT_FOUND(HttpStatus.NOT_FOUND, "This charge estimate does not exist."),
    QUOTE_EXPIRED(HttpStatus.CONFLICT, "The charge has changed or expired. Refresh it and try again."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "This payment does not exist."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "The amount does not match the outstanding charge."),
    PAYMENT_METHOD_NOT_ACCEPTED(HttpStatus.BAD_REQUEST, "This payment method is not accepted here."),
    PAYMENT_INVALID_STATE(HttpStatus.CONFLICT, "This payment cannot be changed in its current state."),
    NOTHING_TO_PAY(HttpStatus.CONFLICT, "There is nothing outstanding to pay."),
    ONLINE_PAYMENT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Online payment is not available. Pay at the gate or the society office."),
    RECEIPT_NOT_FOUND(HttpStatus.NOT_FOUND, "This receipt does not exist.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
