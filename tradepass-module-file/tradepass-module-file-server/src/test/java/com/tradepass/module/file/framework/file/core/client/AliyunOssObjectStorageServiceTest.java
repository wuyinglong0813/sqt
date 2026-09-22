package com.tradepass.module.file.framework.file.core.client;
import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;
import com.tradepass.module.file.framework.file.config.OssStorageProperties;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.*;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.storage.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AliyunOssObjectStorageServiceTest {
    final OSS client = mock(OSS.class);
    final CloudBaseCosObjectStorageService legacy = mock(CloudBaseCosObjectStorageService.class);
    final byte[] bytes = {0, -1, 127, -128, 4};
    final String sha = FileTypeInspector.sha256(bytes);
    final String key = "tradepass/contracts/1/" + sha + ".pdf";
    StorageProperties storage;
    OssStorageProperties properties;
    AliyunOssObjectStorageService service;

    @BeforeEach void setup() {
        storage = new StorageProperties(); storage.setEnabled(true);
        properties = new OssStorageProperties();
        properties.setBucket("new-oss"); properties.setRegion("cn-shanghai");
        properties.setEndpoint("https://oss-cn-shanghai.aliyuncs.com");
        properties.setLegacyCosBucket("old-cos");
        service = new AliyunOssObjectStorageService(storage, properties, client, legacy);
        when(client.getBucketVersioning("new-oss")).thenReturn(new BucketVersioningConfiguration());
        PutObjectResult result = new PutObjectResult(); result.setETag("etag");
        when(client.putObject(any(PutObjectRequest.class))).thenReturn(result);
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(ignored -> object(bytes));
    }

    @Test void uploadsPrivateEncryptedImmutableBytesAndReadsBackTheResult() throws Exception {
        var stored = service.putImmutable(key, bytes, "application/pdf", sha);
        assertThat(stored.provider()).isEqualTo("ALIYUN_OSS");
        assertThat(stored.encryptionAlgorithm()).isEqualTo("SSE_OSS_AES256");
        assertThat(stored.sha256()).isEqualTo(sha);
        assertThat(stored.fileSize()).isEqualTo(bytes.length);
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture());
        assertThat(request.getValue().getInputStream().readAllBytes()).isEqualTo(bytes);
        var metadata = request.getValue().getMetadata();
        assertThat(metadata.getRawMetadata()).containsEntry("x-oss-forbid-overwrite", "true")
                .containsEntry("x-oss-object-acl", "private");
        assertThat(metadata.getServerSideEncryption()).isEqualTo("AES256");
        assertThat(metadata.getUserMetadata()).containsEntry("sha256", sha);
        verify(client).getObject(any(GetObjectRequest.class));
        verifyNoInteractions(legacy);
    }

    @Test void rejectsInvalidKeysDigestAndEmptyFilesBeforeAnyNetworkCall() {
        for (String bad : new String[]{"/tradepass/a", "tradepass/../a", "other/a", "tradepass/a\\b", ""}) {
            assertThatThrownBy(() -> service.putImmutable(bad, bytes, "application/pdf", sha)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.putImmutable(key, bytes, "application/pdf", "bad")).hasMessageContaining("摘要");
        assertThatThrownBy(() -> service.putImmutable(key, new byte[0], "application/pdf", sha)).hasMessageContaining("空文件");
        verifyNoInteractions(client);
    }

    @Test void rejectsVersionedBucketsWhereOssIgnoresForbidOverwrite() {
        for (String state : new String[]{"Enabled", "Suspended"}) {
            var versioning = new BucketVersioningConfiguration(); versioning.setStatus(state);
            when(client.getBucketVersioning("new-oss")).thenReturn(versioning);
            assertThatThrownBy(() -> service.putImmutable(key, bytes, "application/pdf", sha)).hasMessageContaining("关闭版本控制");
        }
        verify(client, never()).putObject(any(PutObjectRequest.class));
    }

    @Test void duplicateUploadRequiresActualByteVerificationAndDoesNotTrustTheCache() {
        service.get(reference());
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(exists());
        ObjectMetadata metadata = mock(ObjectMetadata.class); when(metadata.getETag()).thenReturn("existing");
        when(client.getObjectMetadata("new-oss", key)).thenReturn(metadata);
        assertThat(service.putImmutable(key, bytes, "application/pdf", sha).etag()).isEqualTo("existing");
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(object(new byte[]{1, 2, 3, 4, 5}));
        assertThatThrownBy(() -> service.putImmutable(key, bytes, "application/pdf", sha)).hasMessageContaining("完整性");
        verify(client, times(3)).getObject(any(GetObjectRequest.class));
    }

    @Test void keepsVersionsAndVerifiesLengthAndDigestOnDownload() {
        var ref = new ObjectStorageService.ObjectReference("new-oss", key, "historical-version", 5L, sha);
        assertThat(service.get(ref)).isEqualTo(bytes);
        var request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(request.capture());
        assertThat(request.getValue().getVersionId()).isEqualTo("historical-version");
        assertThatThrownBy(() -> service.get(new ObjectStorageService.ObjectReference("new-oss", key, null, 6L, sha)))
                .hasMessageContaining("长度");
        assertThatThrownBy(() -> service.get(new ObjectStorageService.ObjectReference("new-oss", key, null, 5L, "bad")))
                .hasMessageContaining("完整性");
    }

    @Test void onlyExplicitLegacyBucketUsesTheReadOnlyCosAdapter() {
        var ref = new ObjectStorageService.ObjectReference("old-cos", "old-prefix/doc", "old-version", 5L, sha);
        when(legacy.get(ref)).thenReturn(bytes);
        assertThat(service.get(ref)).isEqualTo(bytes);
        verify(legacy).get(ref); verifyNoInteractions(client);
        assertThatThrownBy(() -> service.get(new ObjectStorageService.ObjectReference("foreign", key, null, 5L, sha)))
                .hasMessageContaining("存储位置");
        verifyNoMoreInteractions(legacy);
    }

    @Test void failedOssReadNeverFallsBackToAnotherBucket() {
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(new OSSException("unavailable"));
        assertThatThrownBy(() -> service.get(reference())).hasMessage("文件读取失败，请稍后重试");
        verifyNoInteractions(legacy);
    }

    @Test void failedWriteIsNotRetriedOrReportedAsSuccess() {
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(new OSSException("unavailable"));
        assertThatThrownBy(() -> service.putImmutable(key, bytes, "application/pdf", sha)).hasMessage("文件安全存储失败，请稍后重试");
        verify(client, times(1)).putObject(any(PutObjectRequest.class));
        verify(client, never()).getObject(any(GetObjectRequest.class));
    }

    @Test void disabledModeNeedsNoCloudCredentialsAndRequiredStorageFailsFast() throws Exception {
        storage.setEnabled(false);
        var disabled = new AliyunOssObjectStorageService(storage, properties);
        assertThat(disabled.isEnabled()).isFalse();
        assertThatThrownBy(() -> disabled.get(reference())).hasMessageContaining("尚未启用");
        storage.setRequired(true);
        assertThatThrownBy(() -> new AliyunOssObjectStorageService(storage, properties)).isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsInsecureEndpointsAndAmbiguousBucketRouting() {
        properties.setEndpoint("http://oss-cn-shanghai.aliyuncs.com");
        assertThatThrownBy(() -> new AliyunOssObjectStorageService(storage, properties, client, legacy)).hasMessageContaining("HTTPS");
        properties.setEndpoint("https://oss-cn-shanghai.aliyuncs.com"); properties.setLegacyCosBucket("new-oss");
        assertThatThrownBy(() -> new AliyunOssObjectStorageService(storage, properties, client, legacy)).hasMessageContaining("不能重名");
    }

    @Test void closesBothOwnedClients() {
        service.shutdown(); verify(client).shutdown(); verify(legacy).shutdown();
    }

    private ObjectStorageService.ObjectReference reference() { return new ObjectStorageService.ObjectReference("new-oss", key, null, 5L, sha); }
    private static OSSObject object(byte[] data) {
        OSSObject result = new OSSObject(); result.setObjectContent(new ByteArrayInputStream(data)); return result;
    }
    private static OSSException exists() {
        return new OSSException("exists", "FileAlreadyExists", "request-id", "host", "header", "resource", "method");
    }
}
