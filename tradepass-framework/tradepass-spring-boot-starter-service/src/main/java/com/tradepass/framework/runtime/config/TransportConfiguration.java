package com.tradepass.framework.runtime.config;

import com.tradepass.framework.common.core.AuthContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.runtime.core.InternalAccessFilter;
import com.tradepass.framework.runtime.core.InternalContracts;
import com.tradepass.module.file.api.file.ObjectStorageService;
import feign.RequestInterceptor;
import feign.Retryer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = {InternalContracts.IdentityClient.class, InternalContracts.StorageClient.class})
public class TransportConfiguration {
    @Bean InternalAccessFilter internalAccessFilter(@Value("${tradepass.services.internal-key:}") String key) {
        return new InternalAccessFilter(key);
    }

    @Bean RequestInterceptor internalCredentials(InternalAccessFilter access) {
        return request -> {
            request.header(InternalAccessFilter.HEADER, access.key());
            var principal = com.tradepass.framework.common.core.AuthContext.get();
            if (principal != null) {
                request.header(InternalAccessFilter.USER_HEADER, Long.toString(principal.userId()));
                if (principal.companyId() != null) request.header(InternalAccessFilter.COMPANY_HEADER, principal.companyId().toString());
            }
        };
    }

    @Bean Retryer retryer() { return Retryer.NEVER_RETRY; }

    @Bean InternalContracts.IdentityFallbackFactory identityFallbackFactory() { return new InternalContracts.IdentityFallbackFactory(); }
    @Bean InternalContracts.StorageFallbackFactory storageFallbackFactory() { return new InternalContracts.StorageFallbackFactory(); }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${tradepass.runtime.role}' != 'file'")
    ObjectStorageService remoteStorage(InternalContracts.StorageClient client, Environment environment, ObjectMapper mapper) {
        boolean enabled = environment.getProperty("tradepass.storage.enabled", Boolean.class, false);
        if (environment.getProperty("tradepass.storage.required", Boolean.class, false) && !enabled) {
            throw new IllegalStateException("生产环境必须启用对象存储");
        }
        return new ObjectStorageService() {
            @Override public boolean isEnabled() { return enabled; }
            @Override public StoredObject putImmutable(String key, byte[] data, String type, String sha) {
                try { return client.put(new InternalContracts.PutObject(key, data, type, sha)); }
                catch (feign.FeignException error) { throw storageError(error, mapper); }
            }
            @Override public byte[] get(ObjectReference reference) {
                try { return client.get(reference); }
                catch (feign.FeignException error) { throw storageError(error, mapper); }
            }
        };
    }

    private static BusinessException storageError(feign.FeignException error, ObjectMapper mapper) {
        if (error.status() == 400) {
            try {
                String message = mapper.readTree(error.contentUTF8()).path("message").asText();
                if (!message.isBlank()) return new BusinessException(message);
            } catch (Exception ignored) { }
        }
        return new BusinessException("文件安全存储服务暂时不可用，请稍后重试");
    }
}
