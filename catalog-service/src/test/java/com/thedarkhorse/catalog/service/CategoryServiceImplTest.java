package com.thedarkhorse.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thedarkhorse.catalog.exception.CategoryNotFoundException;
import com.thedarkhorse.catalog.model.Category;
import com.thedarkhorse.catalog.repository.CategoryRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CategoryServiceImplTest {

    private static final String PARENT_ID = "01920000-0000-7000-8000-000000000001";
    private static final String CHILD_ID = "01920000-0000-7000-8000-000000000002";
    private static final String MISSING_ID = "01920000-0000-7000-8000-0000000000ff";
    private static final String PARENT_SLUG = "keyboards";
    private static final String CHILD_SLUG = "accessories";
    private static final String PARENT_PATH = "keyboards";
    private static final String CHILD_PATH = "keyboards/accessories";

    private final CategoryRepository repository = mock(CategoryRepository.class);
    private final CategoryServiceImpl service = new CategoryServiceImpl(repository);

    @Test
    void givenAParent_whenCreateCategory_thenThePathIsTheParentPathAndTheSlug() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category created = service.createCategory(new Category(null, PARENT_ID, CHILD_SLUG, null, 0, true));

        assertThat(created.getPath()).isEqualTo(CHILD_PATH);
    }

    @Test
    void givenNoParent_whenCreateCategory_thenThePathIsTheSlugAlone() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category created = service.createCategory(new Category(null, null, PARENT_SLUG, null, 0, true));

        assertThat(created.getPath()).isEqualTo(PARENT_PATH);
    }

    @Test
    void givenAnUnknownParent_whenCreateCategory_thenCategoryNotFound() {
        when(repository.findById(MISSING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createCategory(new Category(null, MISSING_ID, CHILD_SLUG, null, 0, true)))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void givenNoSortOrderAndNoActive_whenCreateCategory_thenTheDdlDefaultsAreApplied() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category created = service.createCategory(new Category(null, null, PARENT_SLUG, null, null, null));

        assertThat(created.getSortOrder()).isZero();
        assertThat(created.getActive()).isTrue();
    }

    @Test
    void givenAnIdOnTheIncomingModel_whenCreateCategory_thenItIsNotCarriedIntoTheSavedCategory() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createCategory(new Category(CHILD_ID, null, PARENT_SLUG, null, 0, true));

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
    }

    private Category parent() {
        return new Category(PARENT_ID, null, PARENT_SLUG, PARENT_PATH, 0, true);
    }
}
