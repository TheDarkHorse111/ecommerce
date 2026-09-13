package com.thedarkhorse.catalog.controller;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryRequestTest {

    private static final String VALID_SLUG = "gaming-keyboards-2";
    private static final String UPPERCASE_SLUG = "Keyboards";
    private static final String ARABIC_SLUG = "لوحات-المفاتيح";
    private static final String SPACED_SLUG = "gaming keyboards";
    private static final String SLUG = "slug";
    private static final String SORT_ORDER = "sortOrder";

    private final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final Validator validator = factory.getValidator();

    @Test
    void givenALowercaseLatinSlug_whenValidate_thenNoViolation() {
        assertThat(validator.validate(requestWithSlug(VALID_SLUG))).isEmpty();
    }

    @Test
    void givenAnUppercaseSlug_whenValidate_thenTheSlugIsRejected() {
        assertThat(validator.validate(requestWithSlug(UPPERCASE_SLUG)))
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString(SLUG));
    }

    @Test
    void givenAnArabicSlug_whenValidate_thenTheSlugIsRejected() {
        assertThat(validator.validate(requestWithSlug(ARABIC_SLUG)))
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString(SLUG));
    }

    @Test
    void givenASlugWithASpace_whenValidate_thenTheSlugIsRejected() {
        assertThat(validator.validate(requestWithSlug(SPACED_SLUG)))
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString(SLUG));
    }

    @Test
    void givenABlankSlug_whenValidate_thenTheSlugIsRejected() {
        assertThat(validator.validate(requestWithSlug(" ")))
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString(SLUG));
    }

    @Test
    void givenASlugOverAHundredCharacters_whenValidate_thenTheSlugIsRejected() {
        assertThat(validator.validate(requestWithSlug("a".repeat(101))))
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString(SLUG));
    }

    @Test
    void givenANegativeSortOrder_whenValidate_thenTheSortOrderIsRejected() {
        CategoryRequest request = new CategoryRequest(null, VALID_SLUG, -1, true);

        assertThat(validator.validate(request))
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString(SORT_ORDER));
    }

    private CategoryRequest requestWithSlug(String slug) {
        return new CategoryRequest(null, slug, 0, true);
    }
}
