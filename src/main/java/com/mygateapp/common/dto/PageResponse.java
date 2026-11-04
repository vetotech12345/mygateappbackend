package com.mygateapp.common.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Stable JSON shape for paginated lists, identical to Spring Data's {@code Page} serialisation that the Flutter
 * app parses ({@code content}, {@code number}, {@code size}, {@code totalElements}, {@code totalPages}, {@code last}).
 */
public record PageResponse<T>(
        List<T> content,
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        int numberOfElements,
        boolean empty) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<? super E, ? extends T> mapper) {
        List<T> items = page.getContent().stream().<T>map(mapper).toList();
        return new PageResponse<>(items, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                page.isFirst(), page.isLast(), items.size(), items.isEmpty());
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        boolean last = (long) (page + 1) * size >= total;
        return new PageResponse<>(items, page, size, total, totalPages, page == 0, last, items.size(), items.isEmpty());
    }
}
