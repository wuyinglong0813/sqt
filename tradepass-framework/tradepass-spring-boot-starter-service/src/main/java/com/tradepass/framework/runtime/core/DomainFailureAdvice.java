package com.tradepass.framework.runtime.core;

import com.tradepass.framework.rpc.config.DomainFeignConfiguration;

import com.tradepass.framework.common.pojo.ApiResponse;
import com.tradepass.framework.rpc.config.DomainFeignConfiguration.DomainUnavailableException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(-100)
public class DomainFailureAdvice {
    @ExceptionHandler(DomainUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> unavailable(DomainUnavailableException failure) {
        return new ApiResponse<>(503, failure.getMessage(), null);
    }

    @ExceptionHandler({feign.RetryableException.class, com.alibaba.csp.sentinel.slots.block.BlockException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResponse<Void> transportUnavailable(Exception failure) {
        return new ApiResponse<>(503, "服务暂时不可用，请稍后重试", null);
    }
}
