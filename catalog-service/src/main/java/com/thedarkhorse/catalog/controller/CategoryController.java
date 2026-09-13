package com.thedarkhorse.catalog.controller;

import com.thedarkhorse.catalog.mapper.CategoryMapper;
import com.thedarkhorse.catalog.service.CategoryService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private static final String SEPARATOR = "/";

    private final CategoryService service;
    private final CategoryMapper mapper;

    public CategoryController(CategoryService service, CategoryMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/{*path}")
    public ResponseEntity<List<CategoryResponse>> findSubtree(@PathVariable String path) {
        return ResponseEntity.ok(mapper.toResponses(service.findSubtree(withoutLeadingSeparator(path))));
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResponse(service.createCategory(mapper.toModel(request))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> updateCategory(
            @PathVariable String id, @Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.ok(mapper.toResponse(service.updateCategory(id, mapper.toModel(request))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable String id) {
        service.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }

    private String withoutLeadingSeparator(String path) {
        return path.startsWith(SEPARATOR) ? path.substring(SEPARATOR.length()) : path;
    }
}
