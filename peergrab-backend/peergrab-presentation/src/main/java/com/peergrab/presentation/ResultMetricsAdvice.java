package com.peergrab.presentation;

import com.peergrab.shared.Result;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Business outcomes use HTTP 200, so HTTP status metrics alone miss grab and funds conflicts. */
@ControllerAdvice
public class ResultMetricsAdvice implements ResponseBodyAdvice<Object> {

    private final MeterRegistry registry;

    public ResultMetricsAdvice(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return Result.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof Result<?> result) {
            registry.counter("peergrab.api.results", "code", result.code()).increment();
        }
        return body;
    }
}
