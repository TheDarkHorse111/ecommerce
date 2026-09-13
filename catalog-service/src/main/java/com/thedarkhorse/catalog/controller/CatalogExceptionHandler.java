package com.thedarkhorse.catalog.controller;

import com.thedarkhorse.catalog.exception.CategoryHasChildrenException;
import com.thedarkhorse.catalog.exception.CategoryNotFoundException;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import static org.springframework.http.HttpStatus.*;

@RestControllerAdvice
public class CatalogExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String ERRORS = "errors";
    private static final String CONFLICT_DETAIL = "The request conflicts with the current state of the resource";
    private static final String CONFLICT_LOG = "Request rejected by a database constraint";
    private static final String UNEXPECTED_DETAIL = "The request could not be completed";
    private static final String UNEXPECTED_LOG = "Request failed unexpectedly";
    private static final String NOT_FOUND_DETAIL = "The requested resource does not exist";
    private static final String NOT_FOUND_LOG = "Request targeted a category that does not exist";
    private static final String HAS_CHILDREN_LOG = "Request rejected because the category has descendants";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {
        ProblemDetail body = exception.getBody();
        body.setProperty(
                ERRORS,
                exception.getFieldErrors().stream()
                        .map(error -> new ValidationError(error.getField(), error.getDefaultMessage()))
                        .toList());
        return handleExceptionInternal(exception, body, headers, status, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        logger.warn(CONFLICT_LOG, exception);
        return ProblemDetail.forStatusAndDetail(CONFLICT, CONFLICT_DETAIL);
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    public ProblemDetail handleCategoryNotFound(CategoryNotFoundException exception) {
        logger.warn(NOT_FOUND_LOG, exception);
        return ProblemDetail.forStatusAndDetail(NOT_FOUND, NOT_FOUND_DETAIL);
    }

    @ExceptionHandler(CategoryHasChildrenException.class)
    public ProblemDetail handleCategoryHasChildren(CategoryHasChildrenException exception) {
        logger.warn(HAS_CHILDREN_LOG, exception);
        return ProblemDetail.forStatusAndDetail(CONFLICT, CONFLICT_DETAIL);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        logger.error(UNEXPECTED_LOG, exception);
        return ProblemDetail.forStatusAndDetail(INTERNAL_SERVER_ERROR, UNEXPECTED_DETAIL);
    }
}
