package com.example.campusmarket.unit.api;

import com.example.campusmarket.api.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {
    @Test
    void mapsMissingMvcResourcesToTheNotFoundBoundary() throws Exception {
        var method = ApiExceptionHandler.class.getMethod("notFound", Exception.class);
        var handledTypes = method.getAnnotation(ExceptionHandler.class).value();

        assertThat(Arrays.asList(handledTypes)).contains(NoResourceFoundException.class);

        var response = new ApiExceptionHandler().notFound(
            new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/api/private", ""));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
    }
}
