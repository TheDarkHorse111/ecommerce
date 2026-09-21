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
    private static final String CANONICAL_PARENT_ID = "01920000-0000-7000-8000-00000000000a";
    private static final String UPPERCASE_PARENT_ID = "01920000-0000-7000-8000-00000000000A";
    private static final String MALFORMED_PARENT_ID = "banana";
    private static final String SHORT_GROUP_PARENT_ID = "1920000-0000-7000-8000-00000000000a";
    private static final String BRACED_PARENT_ID = "{01920000-0000-7000-8000-00000000000a}";
    private static final String PARENT_ID = "parentId";
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
    void givenACanonicalParentId_whenValidate_thenNoViolation() {
        assertThat(validator.validate(requestWithParentId(CANONICAL_PARENT_ID))).isEmpty();
    }

    @Test
    void givenAnUppercaseParentId_whenValidate_thenNoViolation() {
        assertThat(validator.validate(requestWithParentId(UPPERCASE_PARENT_ID))).isEmpty();
    }

    @Test
    void givenNoParentId_whenValidate_thenNoViolation() {
        assertThat(validator.validate(requestWithParentId(null))).isEmpty();
    }

    @Test
    void givenAMalformedParentId_whenValidate_thenTheParentIdIsRejected() {
        assertThat(validator.validate(requestWithParentId(MALFORMED_PARENT_ID)))
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString(PARENT_ID));
    }

    @Test
    void givenAParentIdWithADroppedLeadingZero_whenValidate_thenTheParentIdIsRejected() {
        assertThat(validator.validate(requestWithParentId(SHORT_GROUP_PARENT_ID)))
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString(PARENT_ID));
    }

    @Test
    void givenABracedParentId_whenValidate_thenTheParentIdIsRejected() {
        assertThat(validator.validate(requestWithParentId(BRACED_PARENT_ID)))
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString(PARENT_ID));
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

    private CategoryRequest requestWithParentId(String parentId) {
        return new CategoryRequest(parentId, VALID_SLUG, 0, true);
    }
}
