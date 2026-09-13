package com.thedarkhorse.catalog.config;

import com.thedarkhorse.catalog.jpa.CategoryJpaRepository;
import com.thedarkhorse.catalog.mapper.CategoryMapper;
import com.thedarkhorse.catalog.mapper.CategoryMapperImpl;
import com.thedarkhorse.catalog.repository.CategoryRepository;
import com.thedarkhorse.catalog.repository.CategoryRepositoryImpl;
import com.thedarkhorse.catalog.service.CategoryService;
import com.thedarkhorse.catalog.service.CategoryServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CatalogConfiguration {

    @Bean
    public CategoryMapper categoryMapper() {
        return new CategoryMapperImpl();
    }

    @Bean
    public CategoryRepository categoryRepository(CategoryJpaRepository jpaRepository, CategoryMapper mapper) {
        return new CategoryRepositoryImpl(jpaRepository, mapper);
    }

    @Bean
    public CategoryService categoryService(CategoryRepository repository) {
        return new CategoryServiceImpl(repository);
    }
}
