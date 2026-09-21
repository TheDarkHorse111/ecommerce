package com.thedarkhorse.catalog.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CategoryRequest(
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        String parentId,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9-]+") String slug,
        @PositiveOrZero Integer sortOrder,
        Boolean active) {
}
