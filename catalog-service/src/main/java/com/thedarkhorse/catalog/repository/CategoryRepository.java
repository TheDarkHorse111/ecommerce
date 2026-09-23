package com.thedarkhorse.catalog.repository;

import com.thedarkhorse.catalog.model.Category;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository {

    Optional<Category> findById(String id);

    Optional<Category> findByParentIdAndSlug(String parentId, String slug);

    List<Category> findAll();

    List<Category> findSubtree(String id);

    boolean existsByParentId(String parentId);

    Category save(Category category);

    void deleteById(String id);
}
