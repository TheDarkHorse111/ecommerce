package com.thedarkhorse.catalog.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class Category {

    private String id;
    private String parentId;
    private String slug;
    private String path;
    private Integer sortOrder;
    private Boolean active;
    private Boolean effectiveActive;
    private final List<Category> children = new ArrayList<>();
}
