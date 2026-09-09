package com.thedarkhorse.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.HttpServerErrorException;

class GatewayExceptionHandlerTest {

    private static final String NO_INSTANCE = "Unable to find instance for catalog-service";

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

    @Test
    void findsTheStatusOfTheUpstreamFailure() {
        HttpServerErrorException exception =
                new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE, NO_INSTANCE);

        ProblemDetail problemDetail = handler.handleUpstreamFailure(exception);

        assertThat(problemDetail.getStatus()).isEqualTo(503);
        assertThat(problemDetail.getDetail()).isEqualTo(NO_INSTANCE);
    }
}
