package com.mygateapp.common;

import org.springframework.util.StringUtils;

public final class Strings {

    private Strings() {
    }

    /** Trims; returns null for blank input. */
    public static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** Escapes LIKE wildcards and lower-cases for case-insensitive "contains" searches. */
    public static String likePattern(String search) {
        String trimmed = trimToNull(search);
        if (trimmed == null) {
            return null;
        }
        String escaped = trimmed.toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
