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
        return jpaRepository.findById(UUID.fromString(id)).map(mapper::toModel);
    }

    @Override
    public Optional<Category> findByPath(String path) {
        return jpaRepository.findByPath(path).map(mapper::toModel);
    }

    @Override
    public List<Category> findByPathStartingWith(String prefix) {
        return mapper.toModels(jpaRepository.findByPathStartingWithOrderByPathAsc(prefix));
    }

    @Override
    public Category save(Category category) {
        return mapper.toModel(jpaRepository.save(mapper.toEntity(category)));
    }

    @Override
    public List<Category> saveAll(List<Category> categories) {
        return mapper.toModels(jpaRepository.saveAll(categories.stream().map(mapper::toEntity).toList()));
    }

    @Override
    public void deleteById(String id) {
        jpaRepository.deleteById(UUID.fromString(id));
    }
}
