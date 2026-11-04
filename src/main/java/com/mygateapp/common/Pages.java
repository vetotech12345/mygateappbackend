package com.mygateapp.common;

import com.mygateapp.exception.ApiException;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

/** Builds bounded {@link Pageable}s from request parameters. Sorting is restricted to whitelisted fields. */
public final class Pages {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private Pages() {
    }

    public static Pageable of(Integer page, Integer size) {
        return PageRequest.of(page(page), size(size));
    }

    public static Pageable of(Integer page, Integer size, Sort sort) {
        return PageRequest.of(page(page), size(size), sort);
    }

    /**
     * Parses {@code sort=field,dir}. Unknown fields are rejected with 400 so clients cannot sort on arbitrary
     * (potentially sensitive or unindexed) columns.
     */
    public static Pageable of(Integer page, Integer size, String sort, Set<String> allowedFields, Sort defaultSort) {
        if (!StringUtils.hasText(sort)) {
            return of(page, size, defaultSort);
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!allowedFields.contains(field)) {
            throw ApiException.field("sort", "Sorting by '" + field + "' is not supported. Allowed: " + allowedFields);
        }
        Sort.Direction direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return of(page, size, Sort.by(direction, field));
    }

    private static int page(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    private static int size(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
