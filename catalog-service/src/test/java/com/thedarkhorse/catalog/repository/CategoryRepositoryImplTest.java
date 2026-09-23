package com.thedarkhorse.catalog.repository;

import com.thedarkhorse.catalog.jpa.CategoryEntity;
import com.thedarkhorse.catalog.jpa.CategoryJpaRepository;
import com.thedarkhorse.catalog.mapper.CategoryMapper;
import com.thedarkhorse.catalog.mapper.CategoryMapperImpl;
import com.thedarkhorse.catalog.model.Category;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoryRepositoryImplTest {

    private static final UUID ID = UUID.fromString("01920000-0000-7000-8000-000000000001");
    private static final UUID PARENT_ID = UUID.fromString("01920000-0000-7000-8000-000000000002");
    private static final String SLUG = "accessories";
    private static final String ROOT_SLUG = "keyboards";
    private static final int SORT_ORDER = 3;

    private final CategoryJpaRepository jpaRepository = mock(CategoryJpaRepository.class);
    private final CategoryMapper mapper = new CategoryMapperImpl();
    private final CategoryRepositoryImpl repository = new CategoryRepositoryImpl(jpaRepository, mapper);

    @Test
    void givenAStoredEntity_whenFindById_thenTheModelCarriesTheIdsAsStrings() {
        when(jpaRepository.findById(ID)).thenReturn(Optional.of(entity()));

        Optional<Category> found = repository.findById(ID.toString());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(ID.toString());
        assertThat(found.get().getParentId()).isEqualTo(PARENT_ID.toString());
        assertThat(found.get().getSlug()).isEqualTo(SLUG);
        assertThat(found.get().getSortOrder()).isEqualTo(SORT_ORDER);
        assertThat(found.get().getActive()).isTrue();
        verify(jpaRepository).findById(ID);
    }

    @Test
    void givenAStoredEntity_whenFindById_thenThePathIsLeftForTheServiceToBuild() {
        when(jpaRepository.findById(ID)).thenReturn(Optional.of(entity()));

        assertThat(repository.findById(ID.toString()).orElseThrow().getPath()).isNull();
    }

    @Test
    void givenNoParentId_whenFindByParentIdAndSlug_thenTheNullSafeDerivedQueryIsUsed() {
        when(jpaRepository.findByParentIdIsNullAndSlug(ROOT_SLUG)).thenReturn(Optional.of(entity()));

        assertThat(repository.findByParentIdAndSlug(null, ROOT_SLUG)).isPresent();
        verify(jpaRepository).findByParentIdIsNullAndSlug(ROOT_SLUG);
        verify(jpaRepository, never()).findByParentIdAndSlug(any(), any());
    }

    @Test
    void givenAParentId_whenFindByParentIdAndSlug_thenTheJpaRepositoryIsCalledWithTheUuid() {
        when(jpaRepository.findByParentIdAndSlug(PARENT_ID, SLUG)).thenReturn(Optional.of(entity()));

        assertThat(repository.findByParentIdAndSlug(PARENT_ID.toString(), SLUG)).isPresent();
        verify(jpaRepository).findByParentIdAndSlug(PARENT_ID, SLUG);
        verify(jpaRepository, never()).findByParentIdIsNullAndSlug(any());
    }

    @Test
    void givenNoRow_whenFindByParentIdAndSlug_thenEmpty() {
        when(jpaRepository.findByParentIdAndSlug(PARENT_ID, SLUG)).thenReturn(Optional.empty());

        assertThat(repository.findByParentIdAndSlug(PARENT_ID.toString(), SLUG)).isEmpty();
    }

    @Test
    void givenANodeId_whenFindSubtree_thenTheJpaRepositoryIsCalledWithTheUuid() {
        when(jpaRepository.findSubtree(ID)).thenReturn(List.of(entity()));

        List<Category> subtree = repository.findSubtree(ID.toString());

        assertThat(subtree).extracting(Category::getId).containsExactly(ID.toString());
        verify(jpaRepository).findSubtree(ID);
    }

    @Test
    void givenAParentWithChildren_whenExistsByParentId_thenTheJpaRepositoryIsCalledWithTheUuid() {
        when(jpaRepository.existsByParentId(ID)).thenReturn(true);

        assertThat(repository.existsByParentId(ID.toString())).isTrue();
        verify(jpaRepository).existsByParentId(ID);
    }

    @Test
    void givenAModelWithNoId_whenSave_thenTheEntityHasNoIdAndTheSavedModelIsReturned() {
        when(jpaRepository.save(any(CategoryEntity.class))).thenReturn(entity());

        Category saved = repository.save(model(null));

        ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
        verify(jpaRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getParentId()).isEqualTo(PARENT_ID);
        assertThat(captor.getValue().getSlug()).isEqualTo(SLUG);
        assertThat(captor.getValue().getSortOrder()).isEqualTo(SORT_ORDER);
        assertThat(captor.getValue().isActive()).isTrue();
        assertThat(saved.getId()).isEqualTo(ID.toString());
    }

    @Test
    void givenAModelWithAnId_whenSave_thenTheEntityCarriesItAsAUuid() {
        when(jpaRepository.save(any(CategoryEntity.class))).thenReturn(entity());

        repository.save(model(ID.toString()));

        ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
        verify(jpaRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(ID);
    }

    @Test
    void givenAStringId_whenDeleteById_thenTheJpaRepositoryIsCalledWithTheUuid() {
        repository.deleteById(ID.toString());

        verify(jpaRepository).deleteById(ID);
    }

    @Test
    void givenStoredEntities_whenFindAll_thenTheDepthOrderedQueryIsUsed() {
        when(jpaRepository.findForest()).thenReturn(List.of(entity()));

        List<Category> categories = repository.findAll();

        assertThat(categories).extracting(Category::getId).containsExactly(ID.toString());
        verify(jpaRepository).findForest();
    }

    @Test
    void givenAMalformedId_whenFindById_thenNothingIsFoundAndTheDatabaseIsNotQueried() {
        Optional<Category> found = repository.findById("banana");

        assertThat(found).isEmpty();
        verify(jpaRepository, never()).findById(any(UUID.class));
    }

    private CategoryEntity entity() {
        CategoryEntity entity = new CategoryEntity();
        entity.setId(ID);
        entity.setParentId(PARENT_ID);
        entity.setSlug(SLUG);
        entity.setSortOrder(SORT_ORDER);
        entity.setActive(true);
        return entity;
    }

    private Category model(String id) {
        Category model = new Category();
        model.setId(id);
        model.setParentId(PARENT_ID.toString());
        model.setSlug(SLUG);
        model.setSortOrder(SORT_ORDER);
        model.setActive(true);
        return model;
    }
}
