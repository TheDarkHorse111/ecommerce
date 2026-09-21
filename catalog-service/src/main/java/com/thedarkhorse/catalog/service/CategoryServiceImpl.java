package com.thedarkhorse.catalog.service;

import com.thedarkhorse.catalog.exception.CategoryCycleException;
import com.thedarkhorse.catalog.exception.CategoryHasChildrenException;
import com.thedarkhorse.catalog.exception.CategoryNotFoundException;
import com.thedarkhorse.catalog.model.Category;
import com.thedarkhorse.catalog.repository.CategoryRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class CategoryServiceImpl implements CategoryService {

    private static final String SEPARATOR = "/";
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile(SEPARATOR);
    private static final String NOT_FOUND = "No category with id ";
    private static final String NOT_FOUND_PATH = "No category at path ";
    private static final String HAS_CHILDREN = "Category has children with id ";
    private static final String CYCLE = "Category cannot move under its own descendant with id ";
    private static final String CYCLE_IN_CHAIN = "Category ancestors already contain a cycle at id ";
    private static final int DEFAULT_SORT_ORDER = 0;

    private final CategoryRepository repository;

    public CategoryServiceImpl(CategoryRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> findSubtree(String path) {
        Category node = findCategoryAt(path);
        return withPaths(repository.findSubtree(node.getId()), path);
    }

    @Override
    @Transactional
    public Category createCategory(Category category) {
        String path = findPathUnder(null, category.getParentId(), category.getSlug());
        category.setSortOrder(category.getSortOrder() == null ? DEFAULT_SORT_ORDER : category.getSortOrder());
        category.setActive(category.getActive() == null || category.getActive());
        Category created = repository.save(category);
        created.setPath(path);
        return created;
    }

    @Override
    @Transactional
    public Category updateCategory(String id, Category category) {
        Category existing = findCategory(id);
        String path = findPathUnder(existing.getId(), category.getParentId(), category.getSlug());
        existing.setParentId(category.getParentId());
        existing.setSlug(category.getSlug());
        existing.setSortOrder(category.getSortOrder() == null ? DEFAULT_SORT_ORDER : category.getSortOrder());
        existing.setActive(category.getActive() == null || category.getActive());
        Category updated = repository.save(existing);
        updated.setPath(path);
        return updated;
    }

    @Override
    @Transactional
    public void deleteCategory(String id) {
        Category category = findCategory(id);
        if (repository.existsByParentId(category.getId())) {
            throw new CategoryHasChildrenException(HAS_CHILDREN + category.getId());
        }
        repository.deleteById(category.getId());
    }

    private Category findCategory(String id) {
        return repository.findById(id).orElseThrow(() -> new CategoryNotFoundException(NOT_FOUND + id));
    }

    private Category findCategoryAt(String path) {
        Category node = null;
        String parentId = null;
        for (String slug : SEPARATOR_PATTERN.split(path, -1)) {
            node = repository.findByParentIdAndSlug(parentId, slug)
                    .orElseThrow(() -> new CategoryNotFoundException(NOT_FOUND_PATH + path));
            parentId = node.getId();
        }
        if (node == null) {
            throw new CategoryNotFoundException(NOT_FOUND_PATH + path);
        }
        return node;
    }

    private String findPathUnder(String movingId, String parentId, String slug) {
        Deque<String> slugs = new ArrayDeque<>();
        slugs.addFirst(slug);
        Set<String> visited = new HashSet<>();
        String ancestorId = parentId;
        while (ancestorId != null) {
            Category ancestor = findCategory(ancestorId);
            if (ancestor.getId().equals(movingId)) {
                throw new CategoryCycleException(CYCLE + movingId);
            }
            if (!visited.add(ancestor.getId())) {
                throw new CategoryCycleException(CYCLE_IN_CHAIN + ancestor.getId());
            }
            slugs.addFirst(ancestor.getSlug());
            ancestorId = ancestor.getParentId();
        }
        return String.join(SEPARATOR, slugs);
    }

    private List<Category> withPaths(List<Category> subtree, String rootPath) {
        Map<String, String> paths = new HashMap<>();
        subtree.forEach(category -> {
            String parentPath = paths.get(category.getParentId());
            String path = parentPath == null ? rootPath : parentPath + SEPARATOR + category.getSlug();
            paths.put(category.getId(), path);
            category.setPath(path);
        });
        return subtree;
    }
}
