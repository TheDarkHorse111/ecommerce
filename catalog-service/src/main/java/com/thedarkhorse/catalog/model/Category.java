package com.thedarkhorse.catalog.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    private String id;
    private String parentId;
    private String slug;
    private String path;
    private Integer sortOrder;
    private Boolean active;
}
