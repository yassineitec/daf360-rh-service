package com.daf360.rh.common;

import java.util.List;

public record PreviewResponse<T>(
        List<T> items,
        long    total
) {}
