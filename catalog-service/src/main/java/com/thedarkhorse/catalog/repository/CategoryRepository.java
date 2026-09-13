package com.thedarkhorse.catalog.repository;

import com.thedarkhorse.catalog.model.Category;
import java.util.List;
import java.util.Optional;

public interface CategoryRepository {

    Optional<Category> findById(String id);

    Optional<Category> findByPath(String path);

    List<Category> findByPathStartingWith(String prefix);

    Category save(Category category);

    List<Category> saveAll(List<Category> categories);

    void deleteById(String id);
}
