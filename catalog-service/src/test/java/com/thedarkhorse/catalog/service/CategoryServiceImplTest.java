package com.thedarkhorse.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thedarkhorse.catalog.exception.CategoryHasChildrenException;
import com.thedarkhorse.catalog.exception.CategoryNotFoundException;
import com.thedarkhorse.catalog.model.Category;
import com.thedarkhorse.catalog.repository.CategoryRepository;
import java.util.List;
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
    private static final String GRANDCHILD_PATH = "keyboards/accessories/cables";
    private static final String SIBLING_PATH = "keyboards-2";
    private static final String DESCENDANT_PREFIX = "keyboards/";
    private static final String MISSING_PATH = "mice";
    private static final String NEW_PARENT_ID = "01920000-0000-7000-8000-000000000003";
    private static final String NEW_PARENT_PATH = "peripherals";
    private static final String MOVED_PATH = "peripherals/keyboards";
    private static final String MOVED_CHILD_PATH = "peripherals/keyboards/accessories";
    private static final String MOVED_GRANDCHILD_PATH = "peripherals/keyboards/accessories/cables";
    private static final String RENAMED_SLUG = "boards";
    private static final String RENAMED_PATH = "boards";
    private static final String RENAMED_CHILD_PATH = "boards/accessories";

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

    @Test
    void givenANodeWithDescendants_whenFindSubtree_thenTheNodeComesFirstAndEveryDescendantFollows() {
        when(repository.findByPath(PARENT_PATH)).thenReturn(Optional.of(parent()));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX))
                .thenReturn(List.of(at(CHILD_PATH), at(GRANDCHILD_PATH)));

        List<Category> subtree = service.findSubtree(PARENT_PATH);

        assertThat(subtree).extracting(Category::getPath)
                .containsExactly(PARENT_PATH, CHILD_PATH, GRANDCHILD_PATH);
    }

    @Test
    void givenALeaf_whenFindSubtree_thenOnlyTheNodeIsReturned() {
        when(repository.findByPath(GRANDCHILD_PATH)).thenReturn(Optional.of(at(GRANDCHILD_PATH)));
        when(repository.findByPathStartingWith(GRANDCHILD_PATH + "/")).thenReturn(List.of());

        assertThat(service.findSubtree(GRANDCHILD_PATH)).extracting(Category::getPath)
                .containsExactly(GRANDCHILD_PATH);
    }

    @Test
    void givenASiblingSharingThePrefix_whenFindSubtree_thenTheSiblingIsNotADescendant() {
        when(repository.findByPath(PARENT_PATH)).thenReturn(Optional.of(parent()));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX)).thenReturn(List.of(at(CHILD_PATH)));

        List<Category> subtree = service.findSubtree(PARENT_PATH);

        assertThat(subtree).extracting(Category::getPath).doesNotContain(SIBLING_PATH);
        verify(repository).findByPathStartingWith(DESCENDANT_PREFIX);
        verify(repository, never()).findByPathStartingWith(PARENT_PATH);
    }

    @Test
    void givenAnUnknownPath_whenFindSubtree_thenCategoryNotFound() {
        when(repository.findByPath(MISSING_PATH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findSubtree(MISSING_PATH))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void givenACategoryWithDescendants_whenDeleteCategory_thenCategoryHasChildren() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX)).thenReturn(List.of(at(CHILD_PATH)));

        assertThatThrownBy(() -> service.deleteCategory(PARENT_ID))
                .isInstanceOf(CategoryHasChildrenException.class);
        verify(repository, never()).deleteById(PARENT_ID);
    }

    @Test
    void givenALeaf_whenDeleteCategory_thenItIsDeleted() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX)).thenReturn(List.of());

        service.deleteCategory(PARENT_ID);

        verify(repository).deleteById(PARENT_ID);
    }

    @Test
    void givenAnUnknownId_whenDeleteCategory_thenCategoryNotFound() {
        when(repository.findById(MISSING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteCategory(MISSING_ID))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void givenANewParent_whenUpdateCategory_thenTheMovedNodePathIsRecomputed() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.findById(NEW_PARENT_ID))
                .thenReturn(Optional.of(new Category(NEW_PARENT_ID, null, NEW_PARENT_PATH, NEW_PARENT_PATH, 0, true)));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX)).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category moved = service.updateCategory(
                PARENT_ID, new Category(null, NEW_PARENT_ID, PARENT_SLUG, null, 0, true));

        assertThat(moved.getPath()).isEqualTo(MOVED_PATH);
        assertThat(moved.getId()).isEqualTo(PARENT_ID);
        assertThat(moved.getParentId()).isEqualTo(NEW_PARENT_ID);
    }

    @Test
    void givenANewParent_whenUpdateCategory_thenEveryDescendantPathIsRecomputed() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.findById(NEW_PARENT_ID))
                .thenReturn(Optional.of(new Category(NEW_PARENT_ID, null, NEW_PARENT_PATH, NEW_PARENT_PATH, 0, true)));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX))
                .thenReturn(List.of(at(CHILD_PATH), at(GRANDCHILD_PATH)));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateCategory(PARENT_ID, new Category(null, NEW_PARENT_ID, PARENT_SLUG, null, 0, true));

        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.captor();
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(Category::getPath)
                .containsExactly(MOVED_CHILD_PATH, MOVED_GRANDCHILD_PATH);
    }

    @Test
    void givenASiblingSharingThePrefix_whenUpdateCategory_thenOnlyRealDescendantsAreRead() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.findById(NEW_PARENT_ID))
                .thenReturn(Optional.of(new Category(NEW_PARENT_ID, null, NEW_PARENT_PATH, NEW_PARENT_PATH, 0, true)));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX)).thenReturn(List.of(at(CHILD_PATH)));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateCategory(PARENT_ID, new Category(null, NEW_PARENT_ID, PARENT_SLUG, null, 0, true));

        verify(repository).findByPathStartingWith(DESCENDANT_PREFIX);
        verify(repository, never()).findByPathStartingWith(PARENT_PATH);
    }

    @Test
    void givenANewSlug_whenUpdateCategory_thenTheNodeAndItsDescendantsAreRenamed() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX)).thenReturn(List.of(at(CHILD_PATH)));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category renamed = service.updateCategory(
                PARENT_ID, new Category(null, null, RENAMED_SLUG, null, 0, true));

        assertThat(renamed.getPath()).isEqualTo(RENAMED_PATH);
        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.captor();
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(Category::getPath).containsExactly(RENAMED_CHILD_PATH);
    }

    @Test
    void givenAnUnchangedPath_whenUpdateCategory_thenNoDescendantIsSaved() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateCategory(PARENT_ID, new Category(null, null, PARENT_SLUG, null, 0, true));

        verify(repository, never()).saveAll(any());
    }

    @Test
    void givenAnUnknownId_whenUpdateCategory_thenCategoryNotFound() {
        when(repository.findById(MISSING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCategory(
                        MISSING_ID, new Category(null, null, PARENT_SLUG, null, 0, true)))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    private Category parent() {
        return new Category(PARENT_ID, null, PARENT_SLUG, PARENT_PATH, 0, true);
    }

    private Category at(String path) {
        return new Category(CHILD_ID, PARENT_ID, CHILD_SLUG, path, 0, true);
    }
}
