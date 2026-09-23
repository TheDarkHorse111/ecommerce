package com.thedarkhorse.catalog.controller;

import java.util.List;

public record CategoryResponse(
        String id,
        String parentId,
        String slug,
        String path,
        Integer sortOrder,
        Boolean active,
        Boolean effectiveActive,
        List<CategoryResponse> children
) {
}
