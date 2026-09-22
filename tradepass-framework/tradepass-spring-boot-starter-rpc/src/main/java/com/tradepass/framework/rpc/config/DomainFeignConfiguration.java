package com.tradepass.framework.rpc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.exception.BusinessException;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;

/** Preserve the business error contract when a local collaborator becomes HTTP. */
public class DomainFeignConfiguration {
    @Bean feign.codec.Decoder domainDecoder(ObjectMapper mapper) {
        // Monetary values inside legacy Map responses must not pass through Double.
        var exactMapper = mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        var converter = new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(exactMapper);
        return new org.springframework.cloud.openfeign.support.ResponseEntityDecoder(
                new org.springframework.cloud.openfeign.support.SpringDecoder(
                        () -> new org.springframework.boot.autoconfigure.http.HttpMessageConverters(converter)));
    }

    @Bean ErrorDecoder domainErrors(ObjectMapper mapper) {
        return (method, response) -> {
            String message = "服务暂时不可用，请稍后重试";
            if (response.body() != null) {
                try (var body = response.body().asInputStream()) {
                    String value = mapper.readTree(body).path("message").asText();
                    if (!value.isBlank() && (response.status() == 400 || response.status() == 409)) message = value;
                } catch (Exception ignored) { }
            }
            if (response.status() == 400) return new BusinessException(message);
            if (response.status() == 409) return new DataIntegrityViolationException(message);
            return new DomainUnavailableException();
        };
    }
    @Bean feign.Retryer domainRetryer() { return feign.Retryer.NEVER_RETRY; }

    public static class DomainUnavailableException extends RuntimeException {
        public DomainUnavailableException() { super("服务暂时不可用，请稍后重试"); }
    }
}
