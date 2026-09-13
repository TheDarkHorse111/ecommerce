package com.thedarkhorse.catalog.service;

import com.thedarkhorse.catalog.model.Category;
import java.util.List;

public interface CategoryService {

    List<Category> findSubtree(String path);

    Category createCategory(Category category);

    Category updateCategory(String id, Category category);

    void deleteCategory(String id);
}
