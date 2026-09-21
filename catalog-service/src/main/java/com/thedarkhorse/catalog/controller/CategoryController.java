package com.thedarkhorse.catalog.controller;

import com.thedarkhorse.catalog.mapper.CategoryMapper;
import com.thedarkhorse.catalog.path.CategoryPaths;
import com.thedarkhorse.catalog.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService service;
    private final CategoryMapper mapper;
    private final CategoryPaths paths;

    public CategoryController(CategoryService service, CategoryMapper mapper, CategoryPaths paths) {
        this.service = service;
        this.mapper = mapper;
        this.paths = paths;
    }

    @GetMapping
    public ResponseEntity<List<CategoryResponse>> findCategories() {
        return ResponseEntity.ok(mapper.toResponses(service.findCategories()));
    }

    @GetMapping("/{*path}")
    public ResponseEntity<List<CategoryResponse>> findSubtree(@PathVariable String path) {
        return ResponseEntity.ok(mapper.toResponses(service.findSubtree(paths.withoutLeadingSeparator(path))));
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResponse(service.createCategory(mapper.toModel(request))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> updateCategory(@PathVariable String id,
                                                           @Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.ok(mapper.toResponse(service.updateCategory(id, mapper.toModel(request))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable String id) {
        service.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }
}
