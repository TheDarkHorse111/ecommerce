package com.thedarkhorse.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thedarkhorse.catalog.jpa.CategoryEntity;
import com.thedarkhorse.catalog.jpa.CategoryJpaRepository;
import com.thedarkhorse.catalog.mapper.CategoryMapper;
import com.thedarkhorse.catalog.mapper.CategoryMapperImpl;
import com.thedarkhorse.catalog.model.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CategoryRepositoryImplTest {

    private static final UUID ID = UUID.fromString("01920000-0000-7000-8000-000000000001");
    private static final UUID PARENT_ID = UUID.fromString("01920000-0000-7000-8000-000000000002");
    private static final String SLUG = "accessories";
    private static final String PATH = "keyboards/accessories";
    private static final String DESCENDANT_PREFIX = "keyboards/";
    private static final int SORT_ORDER = 3;

    private final CategoryJpaRepository jpaRepository = mock(CategoryJpaRepository.class);
    private final CategoryMapper mapper = new CategoryMapperImpl();
    private final CategoryRepositoryImpl repository = new CategoryRepositoryImpl(jpaRepository, mapper);

    @Test
    void givenAStoredEntity_whenFindByPath_thenTheModelCarriesTheIdsAsStrings() {
        when(jpaRepository.findByPath(PATH)).thenReturn(Optional.of(entity()));

        Optional<Category> found = repository.findByPath(PATH);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(ID.toString());
        assertThat(found.get().getParentId()).isEqualTo(PARENT_ID.toString());
        assertThat(found.get().getSlug()).isEqualTo(SLUG);
        assertThat(found.get().getPath()).isEqualTo(PATH);
        assertThat(found.get().getSortOrder()).isEqualTo(SORT_ORDER);
        assertThat(found.get().getActive()).isTrue();
    }

    @Test
    void givenNoRow_whenFindByPath_thenEmpty() {
        when(jpaRepository.findByPath(PATH)).thenReturn(Optional.empty());

        assertThat(repository.findByPath(PATH)).isEmpty();
    }

    @Test
    void givenAStringId_whenFindById_thenTheJpaRepositoryIsCalledWithTheUuid() {
        when(jpaRepository.findById(ID)).thenReturn(Optional.of(entity()));

        assertThat(repository.findById(ID.toString())).isPresent();
        verify(jpaRepository).findById(ID);
    }

    @Test
    void givenAPrefix_whenFindByPathStartingWith_thenTheOrderedDerivedQueryIsUsed() {
        when(jpaRepository.findByPathStartingWithOrderByPathAsc(DESCENDANT_PREFIX))
                .thenReturn(List.of(entity()));

        List<Category> found = repository.findByPathStartingWith(DESCENDANT_PREFIX);

        assertThat(found).extracting(Category::getPath).containsExactly(PATH);
        verify(jpaRepository).findByPathStartingWithOrderByPathAsc(DESCENDANT_PREFIX);
    }

    @Test
    void givenAModelWithNoId_whenSave_thenTheEntityHasNoIdAndTheSavedModelIsReturned() {
        when(jpaRepository.save(any(CategoryEntity.class))).thenReturn(entity());

        Category saved = repository.save(model(null));

        ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
        verify(jpaRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getParentId()).isEqualTo(PARENT_ID);
        assertThat(captor.getValue().getPath()).isEqualTo(PATH);
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
    void givenModels_whenSaveAll_thenEveryOneIsSavedAndMappedBack() {
        when(jpaRepository.saveAll(any())).thenReturn(List.of(entity()));

        List<Category> saved = repository.saveAll(List.of(model(ID.toString())));

        assertThat(saved).extracting(Category::getId).containsExactly(ID.toString());
        verify(jpaRepository).saveAll(any());
    }

    @Test
    void givenAStringId_whenDeleteById_thenTheJpaRepositoryIsCalledWithTheUuid() {
        repository.deleteById(ID.toString());

        verify(jpaRepository).deleteById(ID);
    }

    private CategoryEntity entity() {
        CategoryEntity entity = new CategoryEntity();
        entity.setId(ID);
        entity.setParentId(PARENT_ID);
        entity.setSlug(SLUG);
        entity.setPath(PATH);
        entity.setSortOrder(SORT_ORDER);
        entity.setActive(true);
        return entity;
    }

    private Category model(String id) {
        return new Category(id, PARENT_ID.toString(), SLUG, PATH, SORT_ORDER, true);
    }
}
