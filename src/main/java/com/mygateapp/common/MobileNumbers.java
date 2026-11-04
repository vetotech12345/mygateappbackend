package com.mygateapp.common;

import com.mygateapp.exception.ApiException;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** Mobile number normalisation and validation. Numbers are stored as national digits plus a country code. */
public final class MobileNumbers {

    public static final String DEFAULT_COUNTRY_CODE = "+91";
    /** Indian mobile numbers: 10 digits starting with 6-9 (same rule as the Flutter app). */
    private static final Pattern INDIA = Pattern.compile("^[6-9]\\d{9}$");
    private static final Pattern GENERIC = Pattern.compile("^\\d{6,15}$");
    private static final Pattern COUNTRY = Pattern.compile("^\\+\\d{1,4}$");

    private MobileNumbers() {
    }

    public static String normalizeCountryCode(String countryCode) {
        if (!StringUtils.hasText(countryCode)) {
            return DEFAULT_COUNTRY_CODE;
        }
        String code = countryCode.trim();
        if (!code.startsWith("+")) {
            code = "+" + code;
        }
        if (!COUNTRY.matcher(code).matches()) {
            throw ApiException.field("countryCode", "Enter a valid country code");
        }
        return code;
    }

    /** Strips formatting and validates. Throws a field error named {@code field} when invalid. */
    public static String normalize(String countryCode, String mobile, String field) {
        String digits = mobile == null ? "" : mobile.replaceAll("\\D", "");
        if (DEFAULT_COUNTRY_CODE.equals(countryCode)) {
            if (digits.length() == 12 && digits.startsWith("91")) {
                digits = digits.substring(2);
            }
            if (!INDIA.matcher(digits).matches()) {
                throw ApiException.field(field, "Enter a valid 10-digit mobile number");
            }
            return digits;
        }
        if (!GENERIC.matcher(digits).matches()) {
            throw ApiException.field(field, "Enter a valid mobile number");
        }
        return digits;
    }

    /** Normalises an optional number (visitor / agent mobile); blank becomes null. */
    public static String normalizeOptional(String mobile, String field) {
        if (!StringUtils.hasText(mobile)) {
            return null;
        }
        return normalize(DEFAULT_COUNTRY_CODE, mobile, field);
    }

    /** "******3210" — for logs. */
    public static String mask(String mobile) {
        if (mobile == null || mobile.length() < 4) {
            return "****";
        }
        return "*".repeat(mobile.length() - 4) + mobile.substring(mobile.length() - 4);
    }
}
