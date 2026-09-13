package com.thedarkhorse.catalog.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryJpaRepository extends JpaRepository<CategoryEntity, UUID> {

    Optional<CategoryEntity> findByPath(String path);

    List<CategoryEntity> findByPathStartingWithOrderByPathAsc(String prefix);
}
