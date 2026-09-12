package com.thedarkhorse.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

class CatalogExceptionHandlerTest {

    private static final String OBJECT_NAME = "categoryRequest";
    private static final String SLUG = "slug";
    private static final String SORT_ORDER = "sortOrder";
    private static final String BLANK_MESSAGE = "must not be blank";
    private static final String NEGATIVE_MESSAGE = "must be greater than or equal to 0";
    private static final String CONFLICT_DETAIL = "The request conflicts with the current state of the resource";
    private static final String CONSTRAINT_NAME = "category_path_key";
    private static final String SQL_MESSAGE =
            "duplicate key value violates unique constraint \"" + CONSTRAINT_NAME + "\"";

    private final CatalogExceptionHandler handler = new CatalogExceptionHandler();

    @Test
    void givenOneRejectedField_whenHandleMethodArgumentNotValid_thenItIsListedWithItsReason()
            throws Exception {
        ResponseEntity<Object> response =
                handle(rejecting(new FieldError(OBJECT_NAME, SLUG, BLANK_MESSAGE)));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(((ProblemDetail) response.getBody()).getStatus()).isEqualTo(400);
        assertThat(errorsOf(response)).containsExactly(new ValidationError(SLUG, BLANK_MESSAGE));
    }

    @Test
    void givenTwoRejectedFields_whenHandleMethodArgumentNotValid_thenBothAreListed() throws Exception {
        ResponseEntity<Object> response =
                handle(
                        rejecting(
                                new FieldError(OBJECT_NAME, SLUG, BLANK_MESSAGE),
                                new FieldError(OBJECT_NAME, SORT_ORDER, NEGATIVE_MESSAGE)));

        assertThat(errorsOf(response))
                .containsExactlyInAnyOrder(
                        new ValidationError(SLUG, BLANK_MESSAGE),
                        new ValidationError(SORT_ORDER, NEGATIVE_MESSAGE));
    }

    @Test
    void givenAConstraintViolation_whenHandleDataIntegrityViolation_thenConflictHidesTheSql() {
        ProblemDetail body =
                handler.handleDataIntegrityViolation(new DataIntegrityViolationException(SQL_MESSAGE));

        assertThat(body.getStatus()).isEqualTo(409);
        assertThat(body.getDetail()).isEqualTo(CONFLICT_DETAIL);
        assertThat(body.getDetail()).doesNotContain(CONSTRAINT_NAME);
    }

    private MethodArgumentNotValidException rejecting(FieldError... fieldErrors) throws Exception {
        MethodParameter parameter =
                new MethodParameter(ValidationError.class.getDeclaredMethod("field"), -1);
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), OBJECT_NAME);
        for (FieldError fieldError : fieldErrors) {
            bindingResult.addError(fieldError);
        }
        return new MethodArgumentNotValidException(parameter, bindingResult);
    }

    @SuppressWarnings("unchecked")
    private List<ValidationError> errorsOf(ResponseEntity<Object> response) {
        ProblemDetail body = (ProblemDetail) response.getBody();
        return (List<ValidationError>) body.getProperties().get("errors");
    }

    private ResponseEntity<Object> handle(MethodArgumentNotValidException exception) {
        return handler.handleMethodArgumentNotValid(
                exception,
                new HttpHeaders(),
                HttpStatus.BAD_REQUEST,
                new ServletWebRequest(new MockHttpServletRequest()));
    }
}
