package com.thedarkhorse.catalog.repository;

import com.thedarkhorse.catalog.jpa.CategoryEntity;
import com.thedarkhorse.catalog.jpa.CategoryJpaRepository;
import com.thedarkhorse.catalog.mapper.CategoryMapper;
import com.thedarkhorse.catalog.model.Category;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class CategoryRepositoryImpl implements CategoryRepository {

    private final CategoryJpaRepository jpaRepository;
    private final CategoryMapper mapper;

    public CategoryRepositoryImpl(CategoryJpaRepository jpaRepository, CategoryMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<Category> findById(String id) {
        return toUuid(id).flatMap(jpaRepository::findById).map(mapper::toModel);
    }

    @Override
    public Optional<Category> findByParentIdAndSlug(String parentId, String slug) {
        return findEntityByParentIdAndSlug(parentId, slug).map(mapper::toModel);
    }

    @Override
    public List<Category> findAll() {
        return mapper.toModels(jpaRepository.findAllByOrderBySortOrderAscSlugAsc());
    }

    @Override
    public List<Category> findSubtree(String id) {
        return mapper.toModels(jpaRepository.findSubtree(UUID.fromString(id)));
    }

    @Override
    public boolean existsByParentId(String parentId) {
        return jpaRepository.existsByParentId(UUID.fromString(parentId));
    }

    @Override
    public Category save(Category category) {
        return mapper.toModel(jpaRepository.save(mapper.toEntity(category)));
    }

    @Override
    public void deleteById(String id) {
        jpaRepository.deleteById(UUID.fromString(id));
    }

    private Optional<CategoryEntity> findEntityByParentIdAndSlug(String parentId, String slug) {
        if (parentId == null) {
            return jpaRepository.findByParentIdIsNullAndSlug(slug);
        }
        return jpaRepository.findByParentIdAndSlug(UUID.fromString(parentId), slug);
    }

    private static Optional<UUID> toUuid(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
