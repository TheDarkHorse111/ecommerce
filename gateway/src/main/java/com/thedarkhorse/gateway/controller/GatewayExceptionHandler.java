package com.thedarkhorse.gateway.controller;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;

@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler(HttpServerErrorException.class)
    public ProblemDetail handleUpstreamFailure(HttpServerErrorException exception) {
        return ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getStatusText());
    }
}
