package com.thedarkhorse.catalog.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryJpaRepository extends JpaRepository<CategoryEntity, UUID> {

    Optional<CategoryEntity> findByParentIdIsNullAndSlug(String slug);

    Optional<CategoryEntity> findByParentIdAndSlug(UUID parentId, String slug);

    boolean existsByParentId(UUID parentId);

    @Query(value = """
            with recursive subtree as (select c.id,
                                              c.parent_id,
                                              c.slug,
                                              c.sort_order,
                                              c.active,
                                              c.created_at,
                                              c.updated_at,
                                              0 as depth
                                       from category c
                                       where c.id = :id
                                       union all
                                       select c.id,
                                              c.parent_id,
                                              c.slug,
                                              c.sort_order,
                                              c.active,
                                              c.created_at,
                                              c.updated_at,
                                              s.depth + 1
                                       from category c
                                                join subtree s on c.parent_id = s.id)
            select id, parent_id, slug, sort_order, active, created_at, updated_at
            from subtree
            order by depth, sort_order, slug
            """, nativeQuery = true)
    List<CategoryEntity> findSubtree(@Param("id") UUID id);

    @Query(value = """
            with recursive forest as (select c.id,
                                             c.parent_id,
                                             c.slug,
                                             c.sort_order,
                                             c.active,
                                             c.created_at,
                                             c.updated_at,
                                             0 as depth
                                      from category c
                                      where c.parent_id is null
                                      union all
                                      select c.id,
                                             c.parent_id,
                                             c.slug,
                                             c.sort_order,
                                             c.active,
                                             c.created_at,
                                             c.updated_at,
                                             f.depth + 1
                                      from category c
                                               join forest f on c.parent_id = f.id)
            select id, parent_id, slug, sort_order, active, created_at, updated_at
            from forest
            order by depth, sort_order, slug
            """, nativeQuery = true)
    List<CategoryEntity> findForest();
}
