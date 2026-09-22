package com.tradepass.module.trade.service.inventory;

import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;

import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.storage.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesOrderSignatureServiceTest {
    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a
    };

    private JdbcTemplate jdbc;
    private ObjectStorageService storage;
    private StorageProperties properties;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        storage = mock(ObjectStorageService.class);
        properties = new StorageProperties();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    @Test
    void savesPngSignatureWithSignerSnapshot() {
        when(storage.isEnabled()).thenReturn(false);
        SalesOrderSignatureService service = new SalesOrderSignatureServiceImpl(jdbc, storage, properties);

        SalesOrderSignatureService.Confirmation saved = service.save(
                4L, 31L, 60L, "张采购", "签名.png", PNG);

        assertThat(saved.signerName()).isEqualTo("张采购");
        assertThat(saved.contentType()).isEqualTo("image/png");
        assertThat(saved.data()).containsExactly(PNG);
        verify(jdbc).update(argThat(sql -> sql.contains("signature_data = ?")), any(Object[].class));
    }

    @Test
    void storesSignatureAsImmutableObjectWhenStorageIsEnabled() {
        when(storage.isEnabled()).thenReturn(true);
        String sha256 = FileTypeInspector.sha256(PNG);
        when(storage.putImmutable(anyString(), eq(PNG), eq("image/png"), eq(sha256)))
                .thenReturn(new ObjectStorageService.StoredObject(
                        "CLOUDBASE_COS", "bucket", "tradepass/file/4/31/customer-signature/a.png",
                        "v1", "etag", "CLOUDBASE_MANAGED", PNG.length, sha256));
        SalesOrderSignatureService service = new SalesOrderSignatureServiceImpl(jdbc, storage, properties);

        service.save(4L, 31L, 60L, "张采购", "签名.png", PNG);

        verify(storage).putImmutable(argThat(key -> key.startsWith(
                "tradepass/file/4/31/customer-signature/")), eq(PNG), eq("image/png"), eq(sha256));
        verify(jdbc).update(argThat(sql -> sql.contains("signature_data = NULL")), any(Object[].class));
    }

    @Test
    void rejectsMissingOrUnsupportedSignature() {
        SalesOrderSignatureService service = new SalesOrderSignatureServiceImpl(jdbc, storage, properties);

        assertThatThrownBy(() -> service.save(4L, 31L, 60L, "张采购", "", new byte[0]))
                .isInstanceOf(BusinessException.class).hasMessage("请先完成手写签名");
        assertThatThrownBy(() -> service.save(
                4L, 31L, 60L, "张采购", "签名.pdf", "%PDF-1.7".getBytes()))
                .isInstanceOf(BusinessException.class).hasMessage("签名仅支持 PNG 或 JPG 图片");
    }
}
