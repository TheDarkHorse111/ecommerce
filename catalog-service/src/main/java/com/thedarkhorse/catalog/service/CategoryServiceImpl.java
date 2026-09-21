package com.thedarkhorse.catalog.service;

import com.thedarkhorse.catalog.exception.CategoryCycleException;
import com.thedarkhorse.catalog.exception.CategoryHasChildrenException;
import com.thedarkhorse.catalog.exception.CategoryNotFoundException;
import com.thedarkhorse.catalog.model.Category;
import com.thedarkhorse.catalog.repository.CategoryRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
        List<Category> subtree = repository.findSubtree(node.getId());
        List<Category> roots = subtree.stream()
                .filter(category -> category.getId().equals(node.getId()))
                .toList();
        roots.forEach(root -> {
            root.setPath(path);
            root.setEffectiveActive(node.getEffectiveActive());
        });
        return withDerivedFields(subtree, roots);
    }

    @Override
    @Transactional
    public Category createCategory(Category category) {
        List<Category> ancestors = findAncestors(null, category.getParentId());
        category.setSortOrder(category.getSortOrder() == null ? DEFAULT_SORT_ORDER : category.getSortOrder());
        category.setActive(category.getActive() == null || category.getActive());
        Category created = repository.save(category);
        created.setPath(findPathUnder(ancestors, created.getSlug()));
        created.setEffectiveActive(findEffectiveActive(ancestors, created.getActive()));
        return created;
    }

    @Override
    @Transactional
    public Category updateCategory(String id, Category category) {
        Category existing = findCategory(id);
        List<Category> ancestors = findAncestors(existing.getId(), category.getParentId());
        existing.setParentId(category.getParentId());
        existing.setSlug(category.getSlug());
        existing.setSortOrder(category.getSortOrder() == null ? DEFAULT_SORT_ORDER : category.getSortOrder());
        existing.setActive(category.getActive() == null || category.getActive());
        Category updated = repository.save(existing);
        updated.setPath(findPathUnder(ancestors, updated.getSlug()));
        updated.setEffectiveActive(findEffectiveActive(ancestors, updated.getActive()));
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
        boolean effectiveActive = true;
        for (String slug : SEPARATOR_PATTERN.split(path, -1)) {
            node = repository.findByParentIdAndSlug(parentId, slug)
                    .orElseThrow(() -> new CategoryNotFoundException(NOT_FOUND_PATH + path));
            effectiveActive = effectiveActive && node.getActive();
            parentId = node.getId();
        }
        node.setEffectiveActive(effectiveActive);
        return node;
    }

    private List<Category> findAncestors(String movingId, String parentId) {
        Deque<Category> ancestors = new ArrayDeque<>();
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
            ancestors.addFirst(ancestor);
            ancestorId = ancestor.getParentId();
        }
        return List.copyOf(ancestors);
    }

    private String findPathUnder(List<Category> ancestors, String slug) {
        StringBuilder path = new StringBuilder();
        ancestors.forEach(ancestor -> path.append(ancestor.getSlug()).append(SEPARATOR));
        return path.append(slug).toString();
    }

    private boolean findEffectiveActive(List<Category> ancestors, Boolean active) {
        return active && ancestors.stream().allMatch(Category::getActive);
    }

    private List<Category> withDerivedFields(List<Category> categories, List<Category> roots) {
        Map<String, List<Category>> childrenByParent = new HashMap<>();
        categories.forEach(category -> childrenByParent
                .computeIfAbsent(category.getParentId(), parentId -> new ArrayList<>())
                .add(category));
        List<Category> ordered = new ArrayList<>(categories.size());
        Deque<Category> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            Category current = pending.removeFirst();
            ordered.add(current);
            childrenByParent.getOrDefault(current.getId(), List.of()).forEach(child -> {
                child.setPath(current.getPath() + SEPARATOR + child.getSlug());
                child.setEffectiveActive(current.getEffectiveActive() && child.getActive());
                pending.addLast(child);
            });
        }
        return ordered;
    }
}
