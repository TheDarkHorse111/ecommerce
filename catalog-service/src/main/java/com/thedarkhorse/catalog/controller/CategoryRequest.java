package com.thedarkhorse.catalog.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CategoryRequest(
        String parentId,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9-]+") String slug,
        @PositiveOrZero Integer sortOrder,
        Boolean active) {}
