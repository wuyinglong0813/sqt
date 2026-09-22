package com.tradepass.module.file.framework.config;

import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.runtime.core.InternalAccessFilter;
import com.tradepass.framework.runtime.core.InternalContracts;
import com.tradepass.module.file.api.file.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.openfeign.FeignClientBuilder;
import org.springframework.context.ApplicationContext;

import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = FileRuntimeConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"tradepass.runtime.role=file", "tradepass.services.internal-key=transport-test-private-key-123456789",
                "tradepass.services.identity-url=http://127.0.0.1:1", "tradepass.services.file-url=http://127.0.0.1:1",
                "tradepass.ids.worker-id=1", "tradepass.ids.datacenter-id=1", "management.server.port=0"})
class StorageTransportTest {
    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @MockBean ObjectStorageService storage;

    InternalContracts.StorageClient client() {
        return new FeignClientBuilder(context).forType(InternalContracts.StorageClient.class, "storage-transport-test")
                .url("http://127.0.0.1:" + port).build();
    }

    @Test void preservesBinaryBytesAndObjectMetadataOverActualHttp() {
        byte[] bytes = new byte[]{0, -1, 2, 127, -128};
        var stored = new ObjectStorageService.StoredObject("TEST", "bucket", "prefix/key", "version", "etag", "AES256", bytes.length, "sha");
        when(storage.putImmutable(eq("prefix/key"), aryEq(bytes), eq("image/png"), eq("sha"))).thenReturn(stored);
        when(storage.get(stored.reference())).thenReturn(bytes);
        assertEquals(stored, client().put(new InternalContracts.PutObject("prefix/key", bytes, "image/png", "sha")));
        assertArrayEquals(bytes, client().get(stored.reference()));
    }

    @Test void rejectsMissingOrWrongInternalCredentialBeforeCallingStorage() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        for (String key : new String[]{"", "incorrect-internal-credential"}) {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/storage/get"))
                    .header("Content-Type", "application/json");
            if (!key.isEmpty()) builder.header(InternalAccessFilter.HEADER, key);
            assertEquals(401, http.send(builder.POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        verifyNoInteractions(storage);
    }

    @Test void preservesBusinessFailureWithoutRetryingStorageWrites() {
        when(storage.putImmutable(any(), any(), any(), any())).thenThrow(new BusinessException("文件校验失败"));
        var error = assertThrows(feign.FeignException.class, () -> client().put(new InternalContracts.PutObject("key", new byte[]{1}, "image/png", "sha")));
        assertEquals(400, error.status());
        assertTrue(error.contentUTF8().contains("文件校验失败"));
        verify(storage, times(1)).putImmutable(any(), any(), any(), any());
    }

    @Test void fileContextHasNoDatabaseOrBusinessMappers() {
        assertEquals(0, context.getBeansOfType(javax.sql.DataSource.class).size());
        assertEquals(0, context.getBeansOfType(com.baomidou.mybatisplus.core.mapper.BaseMapper.class).size());
    }
}
