package com.tradepass.framework.runtime.core;

import com.tradepass.module.file.api.file.ObjectStorageService;
import com.tradepass.module.file.api.file.ObjectStorageService.ObjectReference;
import com.tradepass.module.file.api.file.ObjectStorageService.StoredObject;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

public final class InternalContracts {
    private InternalContracts() { }
    public record Principal(long userId, Long companyId) { }
    public record PutObject(String objectKey, byte[] data, String contentType, String sha256) { }

    @FeignClient(name = "tradepass-identity", url = "${tradepass.services.identity-url:}",
            fallbackFactory = IdentityFallbackFactory.class)
    public interface IdentityClient {
        @PostMapping("/internal/identity/resolve")
        Principal resolve(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @RequestHeader(value = "X-Company-Id", required = false) String companyId);
    }

    @FeignClient(name = "tradepass-file", url = "${tradepass.services.file-url:}",
            fallbackFactory = StorageFallbackFactory.class)
    public interface StorageClient {
        @PostMapping("/internal/storage/put") StoredObject put(@RequestBody PutObject object);
        @PostMapping(value = "/internal/storage/get", produces = "application/octet-stream")
        byte[] get(@RequestBody ObjectReference reference);
    }

    /** Preserve remote errors; blocked calls never fabricate a principal or a stored file. */
    public static class IdentityFallbackFactory implements org.springframework.cloud.openfeign.FallbackFactory<IdentityClient> {
        @Override public IdentityClient create(Throwable cause) {
            return (authorization, companyId) -> { throw unavailable(cause); };
        }
    }

    public static class StorageFallbackFactory implements org.springframework.cloud.openfeign.FallbackFactory<StorageClient> {
        @Override public StorageClient create(Throwable cause) {
            return new StorageClient() {
                @Override public ObjectStorageService.StoredObject put(PutObject object) { throw unavailable(cause); }
                @Override public byte[] get(ObjectStorageService.ObjectReference reference) { throw unavailable(cause); }
            };
        }
    }

    private static feign.FeignException unavailable(Throwable cause) {
        if (cause instanceof feign.FeignException remote) return remote;
        return new feign.FeignException.ServiceUnavailable("服务暂时不可用，请稍后重试",
                feign.Request.create(feign.Request.HttpMethod.POST, "http://internal/unavailable",
                        java.util.Map.of(), null, java.nio.charset.StandardCharsets.UTF_8, null), null, java.util.Map.of());
    }
}
