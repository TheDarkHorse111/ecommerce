package com.thedarkhorse.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thedarkhorse.catalog.mapper.CategoryMapper;
import com.thedarkhorse.catalog.mapper.CategoryMapperImpl;
import com.thedarkhorse.catalog.model.Category;
import com.thedarkhorse.catalog.service.CategoryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CategoryControllerTest {

    private static final String ID = "01920000-0000-7000-8000-000000000001";
    private static final String PARENT_ID = "01920000-0000-7000-8000-000000000002";
    private static final String SLUG = "accessories";
    private static final String PATH = "keyboards/accessories";
    private static final String CAPTURED_PATH = "/keyboards/accessories";
    private static final String ROOT_PATH = "keyboards";
    private static final int SORT_ORDER = 3;

    private final CategoryService service = mock(CategoryService.class);
    private final CategoryMapper mapper = new CategoryMapperImpl();
    private final CategoryController controller = new CategoryController(service, mapper);

    @Test
    void givenACapturedPathWithALeadingSlash_whenFindSubtree_thenTheServiceIsCalledWithoutIt() {
        when(service.findSubtree(PATH)).thenReturn(List.of(model()));

        List<CategoryResponse> responses = controller.findSubtree(CAPTURED_PATH);

        assertThat(responses).extracting(CategoryResponse::path).containsExactly(PATH);
        verify(service).findSubtree(PATH);
    }

    @Test
    void givenASingleSegment_whenFindSubtree_thenTheServiceIsCalledWithoutTheSlash() {
        when(service.findSubtree(ROOT_PATH)).thenReturn(List.of(model()));

        controller.findSubtree("/" + ROOT_PATH);

        verify(service).findSubtree(ROOT_PATH);
    }

    @Test
    void givenARequest_whenCreateCategory_thenTheResponseCarriesTheStoredPath() {
        when(service.createCategory(any())).thenReturn(model());

        CategoryResponse response =
                controller.createCategory(new CategoryRequest(PARENT_ID, SLUG, SORT_ORDER, true));

        assertThat(response.id()).isEqualTo(ID);
        assertThat(response.path()).isEqualTo(PATH);
        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(service).createCategory(captor.capture());
        assertThat(captor.getValue().getParentId()).isEqualTo(PARENT_ID);
        assertThat(captor.getValue().getSlug()).isEqualTo(SLUG);
        assertThat(captor.getValue().getSortOrder()).isEqualTo(SORT_ORDER);
        assertThat(captor.getValue().getActive()).isTrue();
    }

    @Test
    void givenARequest_whenUpdateCategory_thenTheServiceReceivesTheIdAndTheModel() {
        when(service.updateCategory(eq(ID), any())).thenReturn(model());

        CategoryResponse response =
                controller.updateCategory(ID, new CategoryRequest(PARENT_ID, SLUG, SORT_ORDER, true));

        assertThat(response.path()).isEqualTo(PATH);
        verify(service).updateCategory(eq(ID), any());
    }

    @Test
    void givenAnId_whenDeleteCategory_thenTheServiceReceivesIt() {
        controller.deleteCategory(ID);

        verify(service).deleteCategory(ID);
    }

    private Category model() {
        return new Category(ID, PARENT_ID, SLUG, PATH, SORT_ORDER, true);
    }
}
