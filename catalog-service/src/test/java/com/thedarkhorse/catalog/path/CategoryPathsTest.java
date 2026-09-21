package com.thedarkhorse.catalog.path;

import com.thedarkhorse.catalog.model.Category;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryPathsTest {

    private static final String ROOT_SLUG = "keyboards";
    private static final String CHILD_SLUG = "accessories";
    private static final String GRANDCHILD_SLUG = "cables";
    private static final String CHILD_PATH = "keyboards/accessories";
    private static final String GRANDCHILD_PATH = "keyboards/accessories/cables";

    private final CategoryPaths paths = new CategoryPaths();

    @Test
    void givenANestedPath_whenFindSlugs_thenEverySegmentIsReturnedInOrder() {
        assertThat(paths.findSlugs(GRANDCHILD_PATH)).containsExactly(ROOT_SLUG, CHILD_SLUG, GRANDCHILD_SLUG);
    }

    @Test
    void givenATrailingSeparator_whenFindSlugs_thenTheEmptySegmentIsKept() {
        assertThat(paths.findSlugs(ROOT_SLUG + "/")).containsExactly(ROOT_SLUG, "");
    }

    @Test
    void givenAnEmptyPath_whenFindSlugs_thenASingleEmptySegmentIsReturned() {
        assertThat(paths.findSlugs("")).containsExactly("");
    }

    @Test
    void givenAncestors_whenFindPathOf_thenTheirSlugsPrefixTheSlug() {
        List<Category> ancestors = List.of(root(), child());

        assertThat(paths.findPathOf(ancestors, GRANDCHILD_SLUG)).isEqualTo(GRANDCHILD_PATH);
    }

    @Test
    void givenNoAncestors_whenFindPathOf_thenThePathIsTheSlugAlone() {
        assertThat(paths.findPathOf(List.of(), ROOT_SLUG)).isEqualTo(ROOT_SLUG);
    }

    @Test
    void givenAParentPath_whenFindPathUnder_thenTheSlugIsAppended() {
        assertThat(paths.findPathUnder(ROOT_SLUG, CHILD_SLUG)).isEqualTo(CHILD_PATH);
    }

    @Test
    void givenALeadingSeparator_whenWithoutLeadingSeparator_thenItIsStripped() {
        assertThat(paths.withoutLeadingSeparator("/" + CHILD_PATH)).isEqualTo(CHILD_PATH);
    }

    @Test
    void givenNoLeadingSeparator_whenWithoutLeadingSeparator_thenThePathIsUnchanged() {
        assertThat(paths.withoutLeadingSeparator(CHILD_PATH)).isEqualTo(CHILD_PATH);
    }

    private Category root() {
        return new Category(null, null, ROOT_SLUG, null, 0, true, null);
    }

    private Category child() {
        return new Category(null, null, CHILD_SLUG, null, 0, true, null);
    }
}
