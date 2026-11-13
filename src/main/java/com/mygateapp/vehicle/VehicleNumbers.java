package com.mygateapp.vehicle;

import com.mygateapp.exception.ApiException;
import com.mygateapp.exception.ErrorCode;
import com.mygateapp.vehicle.enums.VehicleEnums.NumberFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalisation and validation of registration numbers. The stored form is upper case without spaces, hyphens or dots
 * ({@code mh 12-ab 1234} → {@code MH12AB1234}); the same rule as the Flutter {@code VehicleNumber.normalize}.
 *
 * <ul>
 *   <li>{@link NumberFormat#INDIA}: state code, RTO district (1–2 digits), series (0–3 letters), 4-digit number, or the
 *       Bharat series {@code 22BH1234AA}.</li>
 *   <li>{@link NumberFormat#GENERIC}: 4–13 letters or digits (other jurisdictions, temporary registrations).</li>
 * </ul>
 */
public final class VehicleNumbers {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-.]");
    private static final Pattern INDIA = Pattern.compile("^[A-Z]{2}\\d{1,2}[A-Z]{0,3}\\d{4}$");
    private static final Pattern BHARAT = Pattern.compile("^\\d{2}BH\\d{4}[A-Z]{1,2}$");
    private static final Pattern GENERIC = Pattern.compile("^[A-Z0-9]{4,13}$");
    private static final Pattern SEARCH = Pattern.compile("^[A-Z0-9]{2,13}$");

    private VehicleNumbers() {
    }

    public static String normalize(String raw) {
        return raw == null ? "" : SEPARATORS.matcher(raw.toUpperCase(Locale.ROOT)).replaceAll("");
    }

    public static boolean isValid(String normalized, NumberFormat format) {
        if (normalized == null || !GENERIC.matcher(normalized).matches()) {
            return false;
        }
        return format == NumberFormat.GENERIC || INDIA.matcher(normalized).matches() || BHARAT.matcher(normalized).matches();
    }

    /** Normalises and validates a number for registration; throws a field error otherwise. */
    public static String requireValid(String raw, NumberFormat format, String field) {
        String value = normalize(raw);
        if (value.isEmpty()) {
            throw ApiException.field(ErrorCode.INVALID_VEHICLE_NUMBER, field, "Enter the vehicle number.");
        }
        if (!isValid(value, format)) {
            throw ApiException.field(ErrorCode.INVALID_VEHICLE_NUMBER, field, format == NumberFormat.INDIA
                    ? "Enter a valid Indian number, e.g. MH 12 AB 1234 or 22 BH 1234 AA."
                    : "Enter a valid vehicle number (4-13 letters or digits).");
        }
        return value;
    }

    /** Lenient check for numbers typed at the gate (any jurisdiction). */
    public static String requireLookup(String raw, String field) {
        return requireValid(raw, NumberFormat.GENERIC, field);
    }

    /** Search term: at least 2 letters/digits after normalisation. */
    public static String requireSearchTerm(String raw, String field) {
        String value = normalize(raw);
        if (!SEARCH.matcher(value).matches()) {
            throw ApiException.field(field, "Enter at least 2 letters or digits.");
        }
        return value;
    }
}
