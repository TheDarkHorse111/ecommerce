package com.thedarkhorse.catalog.exception;

public class CategoryCycleException extends RuntimeException {

    public CategoryCycleException(String message) {
        super(message);
    }
}
