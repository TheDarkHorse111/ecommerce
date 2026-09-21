package com.thedarkhorse.catalog.path;

import com.thedarkhorse.catalog.model.Category;

import java.util.List;
import java.util.regex.Pattern;

public class CategoryPaths {

    private static final String SEPARATOR = "/";
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile(SEPARATOR);

    public List<String> findSlugs(String path) {
        return List.of(SEPARATOR_PATTERN.split(path, -1));
    }

    public String findPathOf(List<Category> ancestors, String slug) {
        StringBuilder path = new StringBuilder();
        ancestors.forEach(ancestor -> path.append(ancestor.getSlug()).append(SEPARATOR));
        return path.append(slug).toString();
    }

    public String findPathUnder(String parentPath, String slug) {
        return parentPath + SEPARATOR + slug;
    }

    public String withoutLeadingSeparator(String path) {
        return path.startsWith(SEPARATOR) ? path.substring(SEPARATOR.length()) : path;
    }
}
