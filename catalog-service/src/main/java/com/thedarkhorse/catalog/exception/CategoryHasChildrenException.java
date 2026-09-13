package com.thedarkhorse.catalog.exception;

public class CategoryHasChildrenException extends RuntimeException {

    public CategoryHasChildrenException(String message) {
        super(message);
    }
}
