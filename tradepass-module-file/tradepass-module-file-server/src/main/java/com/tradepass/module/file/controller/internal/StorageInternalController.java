package com.tradepass.module.file.controller.internal;
import com.tradepass.framework.runtime.core.InternalContracts;

import com.tradepass.module.file.api.file.ObjectStorageService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StorageInternalController {
    private final ObjectStorageService storage;
    public StorageInternalController(ObjectStorageService storage) { this.storage = storage; }
    @PostMapping("/internal/storage/put")
    public ObjectStorageService.StoredObject put(@RequestBody InternalContracts.PutObject request) {
        return storage.putImmutable(request.objectKey(), request.data(), request.contentType(), request.sha256());
    }
    @PostMapping(value = "/internal/storage/get", produces = "application/octet-stream")
    public byte[] get(@RequestBody ObjectStorageService.ObjectReference reference) { return storage.get(reference); }
}
