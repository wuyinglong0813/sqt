package com.tradepass.framework.common.pojo;

import java.util.List;

public record PagePayload<T>(
        List<T> items,
        long total,
        int page,
        int size,
        boolean hasMore
) {
    public static <T> PagePayload<T> of(List<T> items, long total, int page, int size) {
        return new PagePayload<>(items, total, page, size, (long) page * size < total);
    }
}
