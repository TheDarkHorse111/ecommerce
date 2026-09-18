package com.thedarkhorse.catalog.service;

import com.thedarkhorse.catalog.exception.CategoryCycleException;
import com.thedarkhorse.catalog.exception.CategoryHasChildrenException;
import com.thedarkhorse.catalog.exception.CategoryNotFoundException;
import com.thedarkhorse.catalog.model.Category;
import com.thedarkhorse.catalog.repository.CategoryRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoryServiceImplTest {

    private static final String ROOT_ID = "01920000-0000-7000-8000-000000000001";
    private static final String CHILD_ID = "01920000-0000-7000-8000-000000000002";
    private static final String GRANDCHILD_ID = "01920000-0000-7000-8000-000000000003";
    private static final String NEW_PARENT_ID = "01920000-0000-7000-8000-000000000004";
    private static final String MISSING_ID = "01920000-0000-7000-8000-0000000000ff";

    private static final String ROOT_SLUG = "keyboards";
    private static final String CHILD_SLUG = "accessories";
    private static final String GRANDCHILD_SLUG = "cables";
    private static final String NEW_PARENT_SLUG = "peripherals";
    private static final String RENAMED_SLUG = "boards";

    private static final String ROOT_PATH = "keyboards";
    private static final String CHILD_PATH = "keyboards/accessories";
    private static final String GRANDCHILD_PATH = "keyboards/accessories/cables";
    private static final String MOVED_PATH = "peripherals/keyboards";
    private static final String MISSING_PATH = "mice";

    private final CategoryRepository repository = mock(CategoryRepository.class);
    private final CategoryServiceImpl service = new CategoryServiceImpl(repository);

    @Test
    void givenAParent_whenCreateCategory_thenThePathIsTheParentPathAndTheSlug() {
        when(repository.findById(ROOT_ID)).thenReturn(Optional.of(root()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category created = service.createCategory(new Category(null, ROOT_ID, CHILD_SLUG, null, 0, true));

        assertThat(created.getPath()).isEqualTo(CHILD_PATH);
    }

    @Test
    void givenANestedParent_whenCreateCategory_thenThePathIsBuiltByWalkingEveryAncestor() {
        when(repository.findById(CHILD_ID)).thenReturn(Optional.of(child()));
        when(repository.findById(ROOT_ID)).thenReturn(Optional.of(root()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category created = service.createCategory(new Category(null, CHILD_ID, GRANDCHILD_SLUG, null, 0, true));

        assertThat(created.getPath()).isEqualTo(GRANDCHILD_PATH);
    }

    @Test
    void givenNoParent_whenCreateCategory_thenThePathIsTheSlugAloneAndNoAncestorIsRead() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category created = service.createCategory(new Category(null, null, ROOT_SLUG, null, 0, true));

        assertThat(created.getPath()).isEqualTo(ROOT_PATH);
        verify(repository, never()).findById(any());
    }

    @Test
    void givenAnUnknownParent_whenCreateCategory_thenCategoryNotFound() {
        when(repository.findById(MISSING_ID)).thenReturn(Optional.empty());

        ThrowingCallable throwingCallable =
                () -> service.createCategory(new Category(null, MISSING_ID, CHILD_SLUG, null, 0, true));

        assertThatThrownBy(throwingCallable).isInstanceOf(CategoryNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void givenNoSortOrderAndNoActive_whenCreateCategory_thenTheDdlDefaultsAreApplied() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category created = service.createCategory(new Category(null, null, ROOT_SLUG, null, null, null));

        assertThat(created.getSortOrder()).isZero();
        assertThat(created.getActive()).isTrue();
    }

    @Test
    void givenANodeWithDescendants_whenFindSubtree_thenEveryPathIsRebuiltFromTheParentChain() {
        when(repository.findByParentIdAndSlug(null, ROOT_SLUG)).thenReturn(Optional.of(root()));
        when(repository.findSubtree(ROOT_ID)).thenReturn(List.of(root(), child(), grandchild()));

        List<Category> subtree = service.findSubtree(ROOT_PATH);

        assertThat(subtree).extracting(Category::getPath)
                .containsExactly(ROOT_PATH, CHILD_PATH, GRANDCHILD_PATH);
    }

    @Test
    void givenANestedPath_whenFindSubtree_thenEachSegmentResolvesAgainstItsParent() {
        when(repository.findByParentIdAndSlug(null, ROOT_SLUG)).thenReturn(Optional.of(root()));
        when(repository.findByParentIdAndSlug(ROOT_ID, CHILD_SLUG)).thenReturn(Optional.of(child()));
        when(repository.findSubtree(CHILD_ID)).thenReturn(List.of(child(), grandchild()));

        List<Category> subtree = service.findSubtree(CHILD_PATH);

        assertThat(subtree).extracting(Category::getPath).containsExactly(CHILD_PATH, GRANDCHILD_PATH);
        verify(repository).findSubtree(CHILD_ID);
    }

    @Test
    void givenALeaf_whenFindSubtree_thenOnlyTheNodeIsReturned() {
        when(repository.findByParentIdAndSlug(null, ROOT_SLUG)).thenReturn(Optional.of(root()));
        when(repository.findSubtree(ROOT_ID)).thenReturn(List.of(root()));

        assertThat(service.findSubtree(ROOT_PATH)).extracting(Category::getPath).containsExactly(ROOT_PATH);
    }

    @Test
    void givenAnUnknownRootSlug_whenFindSubtree_thenCategoryNotFound() {
        when(repository.findByParentIdAndSlug(null, MISSING_PATH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findSubtree(MISSING_PATH))
                .isInstanceOf(CategoryNotFoundException.class);
        verify(repository, never()).findSubtree(any());
    }

    @Test
    void givenAnUnknownSegmentUnderAKnownRoot_whenFindSubtree_thenCategoryNotFound() {
        when(repository.findByParentIdAndSlug(null, ROOT_SLUG)).thenReturn(Optional.of(root()));
        when(repository.findByParentIdAndSlug(ROOT_ID, CHILD_SLUG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findSubtree(CHILD_PATH))
                .isInstanceOf(CategoryNotFoundException.class);
        verify(repository, never()).findSubtree(any());
    }

    @Test
    void givenACategoryWithChildren_whenDeleteCategory_thenCategoryHasChildren() {
        when(repository.findById(ROOT_ID)).thenReturn(Optional.of(root()));
        when(repository.existsByParentId(ROOT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteCategory(ROOT_ID))
                .isInstanceOf(CategoryHasChildrenException.class);
        verify(repository, never()).deleteById(ROOT_ID);
    }

    @Test
    void givenALeaf_whenDeleteCategory_thenItIsDeleted() {
        when(repository.findById(ROOT_ID)).thenReturn(Optional.of(root()));
        when(repository.existsByParentId(ROOT_ID)).thenReturn(false);

        service.deleteCategory(ROOT_ID);

        verify(repository).deleteById(ROOT_ID);
    }

    @Test
    void givenAnUnknownId_whenDeleteCategory_thenCategoryNotFound() {
        when(repository.findById(MISSING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteCategory(MISSING_ID))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void givenANewParent_whenUpdateCategory_thenOnlyTheMovedRowIsWritten() {
        when(repository.findById(ROOT_ID)).thenReturn(Optional.of(root()));
        when(repository.findById(NEW_PARENT_ID)).thenReturn(Optional.of(newParent()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category moved = service.updateCategory(
                ROOT_ID, new Category(null, NEW_PARENT_ID, ROOT_SLUG, null, 0, true));

        assertThat(moved.getId()).isEqualTo(ROOT_ID);
        assertThat(moved.getParentId()).isEqualTo(NEW_PARENT_ID);
        assertThat(moved.getPath()).isEqualTo(MOVED_PATH);
        verify(repository, times(1)).save(any());
    }

    @Test
    void givenANewSlug_whenUpdateCategory_thenOnlyTheRenamedRowIsWritten() {
        when(repository.findById(ROOT_ID)).thenReturn(Optional.of(root()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category renamed = service.updateCategory(
                ROOT_ID, new Category(null, null, RENAMED_SLUG, null, 0, true));

        assertThat(renamed.getPath()).isEqualTo(RENAMED_SLUG);
        assertThat(renamed.getSlug()).isEqualTo(RENAMED_SLUG);
        verify(repository, times(1)).save(any());
        verify(repository, never()).findSubtree(any());
    }

    @Test
    void givenNoSortOrderAndNoActive_whenUpdateCategory_thenTheDdlDefaultsAreApplied() {
        when(repository.findById(ROOT_ID)).thenReturn(Optional.of(root()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category updated = service.updateCategory(
                ROOT_ID, new Category(null, null, ROOT_SLUG, null, null, null));

        assertThat(updated.getSortOrder()).isZero();
        assertThat(updated.getActive()).isTrue();
    }

    @Test
    void givenAnUnknownId_whenUpdateCategory_thenCategoryNotFound() {
        when(repository.findById(MISSING_ID)).thenReturn(Optional.empty());

        ThrowingCallable throwingCallable = () -> service.updateCategory(
                MISSING_ID, new Category(null, null, ROOT_SLUG, null, 0, true));

        assertThatThrownBy(throwingCallable).isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void givenAnUnknownNewParent_whenUpdateCategory_thenCategoryNotFound() {
        when(repository.findById(ROOT_ID)).thenReturn(Optional.of(root()));
        when(repository.findById(MISSING_ID)).thenReturn(Optional.empty());

        ThrowingCallable throwingCallable = () -> service.updateCategory(
                ROOT_ID, new Category(null, MISSING_ID, ROOT_SLUG, null, 0, true));

        assertThatThrownBy(throwingCallable).isInstanceOf(CategoryNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void givenItselfAsTheNewParent_whenUpdateCategory_thenCategoryCycle() {
        when(repository.findById(ROOT_ID)).thenReturn(Optional.of(root()));

        ThrowingCallable throwingCallable = () -> service.updateCategory(
                ROOT_ID, new Category(null, ROOT_ID, ROOT_SLUG, null, 0, true));

        assertThatThrownBy(throwingCallable).isInstanceOf(CategoryCycleException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void givenAChildAsTheNewParent_whenUpdateCategory_thenCategoryCycle() {
        when(repository.findById(ROOT_ID)).thenReturn(Optional.of(root()));
        when(repository.findById(CHILD_ID)).thenReturn(Optional.of(child()));

        ThrowingCallable throwingCallable = () -> service.updateCategory(
                ROOT_ID, new Category(null, CHILD_ID, ROOT_SLUG, null, 0, true));

        assertThatThrownBy(throwingCallable).isInstanceOf(CategoryCycleException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void givenADeepDescendantAsTheNewParent_whenUpdateCategory_thenCategoryCycle() {
        when(repository.findById(ROOT_ID)).thenReturn(Optional.of(root()));
        when(repository.findById(CHILD_ID)).thenReturn(Optional.of(child()));
        when(repository.findById(GRANDCHILD_ID)).thenReturn(Optional.of(grandchild()));

        ThrowingCallable throwingCallable = () -> service.updateCategory(
                ROOT_ID, new Category(null, GRANDCHILD_ID, ROOT_SLUG, null, 0, true));

        assertThatThrownBy(throwingCallable).isInstanceOf(CategoryCycleException.class);
        verify(repository, never()).save(any());
    }

    private Category root() {
        return new Category(ROOT_ID, null, ROOT_SLUG, null, 0, true);
    }

    private Category child() {
        return new Category(CHILD_ID, ROOT_ID, CHILD_SLUG, null, 0, true);
    }

    private Category grandchild() {
        return new Category(GRANDCHILD_ID, CHILD_ID, GRANDCHILD_SLUG, null, 0, true);
    }

    private Category newParent() {
        return new Category(NEW_PARENT_ID, null, NEW_PARENT_SLUG, null, 0, true);
    }
}
