package com.thedarkhorse.catalog.controller;

public record CategoryResponse(
        String id, String parentId, String slug, String path, Integer sortOrder, Boolean active) {}
