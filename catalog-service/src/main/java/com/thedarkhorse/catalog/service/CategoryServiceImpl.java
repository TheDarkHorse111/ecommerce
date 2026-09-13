package com.thedarkhorse.catalog.service;

import com.thedarkhorse.catalog.exception.CategoryHasChildrenException;
import com.thedarkhorse.catalog.exception.CategoryNotFoundException;
import com.thedarkhorse.catalog.model.Category;
import com.thedarkhorse.catalog.repository.CategoryRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

public class CategoryServiceImpl implements CategoryService {

    private static final String SEPARATOR = "/";
    private static final String NOT_FOUND = "No category with id ";
    private static final String NOT_FOUND_PATH = "No category at path ";
    private static final String HAS_CHILDREN = "Category has descendants at path ";
    private static final int DEFAULT_SORT_ORDER = 0;

    private final CategoryRepository repository;

    public CategoryServiceImpl(CategoryRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> findSubtree(String path) {
        Category node = repository.findByPath(path)
                .orElseThrow(() -> new CategoryNotFoundException(NOT_FOUND_PATH + path));
        List<Category> subtree = new ArrayList<>();
        subtree.add(node);
        subtree.addAll(repository.findByPathStartingWith(path + SEPARATOR));
        return subtree;
    }

    @Override
    @Transactional
    public Category createCategory(Category category) {
        category.setId(null);
        category.setPath(findPathUnder(category.getParentId(), category.getSlug()));
        category.setSortOrder(category.getSortOrder() == null ? DEFAULT_SORT_ORDER : category.getSortOrder());
        category.setActive(category.getActive() == null || category.getActive());
        return repository.save(category);
    }

    @Override
    @Transactional
    public Category updateCategory(String id, Category category) {
        Category existing = findCategory(id);
        String oldPath = existing.getPath();
        String newPath = findPathUnder(category.getParentId(), category.getSlug());
        if (!newPath.equals(oldPath)) {
            moveDescendants(oldPath, newPath);
        }
        existing.setParentId(category.getParentId());
        existing.setSlug(category.getSlug());
        existing.setPath(newPath);
        existing.setSortOrder(category.getSortOrder() == null ? DEFAULT_SORT_ORDER : category.getSortOrder());
        existing.setActive(category.getActive() == null || category.getActive());
        return repository.save(existing);
    }

    @Override
    @Transactional
    public void deleteCategory(String id) {
        Category category = findCategory(id);
        if (!repository.findByPathStartingWith(category.getPath() + SEPARATOR).isEmpty()) {
            throw new CategoryHasChildrenException(HAS_CHILDREN + category.getPath());
        }
        repository.deleteById(id);
    }

    private String findPathUnder(String parentId, String slug) {
        if (parentId == null) {
            return slug;
        }
        return findCategory(parentId).getPath() + SEPARATOR + slug;
    }

    private Category findCategory(String id) {
        return repository.findById(id).orElseThrow(() -> new CategoryNotFoundException(NOT_FOUND + id));
    }

    private void moveDescendants(String oldPath, String newPath) {
        List<Category> descendants = repository.findByPathStartingWith(oldPath + SEPARATOR);
        if (descendants.isEmpty()) {
            return;
        }
        descendants.forEach(descendant ->
                descendant.setPath(newPath + descendant.getPath().substring(oldPath.length())));
        repository.saveAll(descendants);
    }
}
