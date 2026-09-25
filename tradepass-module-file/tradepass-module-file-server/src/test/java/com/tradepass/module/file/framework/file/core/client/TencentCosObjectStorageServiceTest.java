package com.tradepass.module.file.framework.file.core.client;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.auth.COSSessionCredentials;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.*;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.framework.storage.config.StorageProperties;
import com.tradepass.module.file.api.file.ObjectStorageService;
import com.tradepass.module.file.framework.file.config.CosStorageProperties;
import com.tradepass.module.file.framework.file.config.ObjectStorageConfiguration;
import org.apache.http.client.methods.HttpRequestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TencentCosObjectStorageServiceTest {
    final COSClient client = mock(COSClient.class);
    final byte[] bytes = {0, -1, 127, -128, 4};
    final String sha = FileTypeInspector.sha256(bytes);
    final String key = "tradepass/contracts/1/" + sha + ".pdf";
    StorageProperties storage;
    TencentCosObjectStorageService service;

    @BeforeEach void setup() {
        storage = new StorageProperties();
        storage.setEnabled(true);
        storage.setBucket("test-bucket-1250000000");
        storage.setRegion("ap-shanghai");
        service = new TencentCosObjectStorageService(storage, client);
        when(client.getBucketVersioningConfiguration(storage.getBucket())).thenReturn(new BucketVersioningConfiguration());
        PutObjectResult result = new PutObjectResult(); result.setETag("etag");
        when(client.putObject(any(PutObjectRequest.class))).thenReturn(result);
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(ignored -> object(bytes));
    }

    @Test void uploadsPrivateEncryptedImmutableBytesWithoutCloudBaseMetadataAndVerifiesThem() throws Exception {
        var stored = service.putImmutable(key, bytes, "application/pdf", sha);
        assertThat(stored.provider()).isEqualTo("TENCENT_COS");
        assertThat(stored.encryptionAlgorithm()).isEqualTo("SSE_COS_AES256");
        assertThat(stored.sha256()).isEqualTo(sha);
        assertThat(stored.fileSize()).isEqualTo(bytes.length);
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture());
        assertThat(request.getValue().getInputStream().readAllBytes()).isEqualTo(bytes);
        assertThat(request.getValue().getCannedAcl()).isEqualTo(CannedAccessControlList.Private);
        assertThat(request.getValue().getCustomRequestHeaders()).containsEntry("x-cos-forbid-overwrite", "true");
        assertThat(request.getValue().getMetadata().getServerSideEncryption()).isEqualTo("AES256");
        assertThat(request.getValue().getMetadata().getUserMetadata())
                .containsEntry("sha256", sha).containsEntry("storage-provider", "TENCENT_COS").doesNotContainKey("fileid");
        verify(client).getObject(any(GetObjectRequest.class));
    }

    @Test void rejectsInvalidKeysDigestAndEmptyFilesBeforeAnyNetworkCall() {
        for (String bad : new String[]{"/tradepass/a", "tradepass/../a", "other/a", "tradepass/a\\b", ""}) {
            assertThatThrownBy(() -> service.putImmutable(bad, bytes, "application/pdf", sha)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.putImmutable(key, bytes, "application/pdf", "bad")).hasMessageContaining("摘要");
        assertThatThrownBy(() -> service.putImmutable(key, new byte[0], "application/pdf", sha)).hasMessageContaining("空文件");
        verifyNoInteractions(client);
    }

    @Test void rejectsVersionedSuspendedOrUnknownBucketsBeforeUpload() {
        for (String state : new String[]{"Enabled", "Suspended", "unknown", null}) {
            when(client.getBucketVersioningConfiguration(storage.getBucket())).thenReturn(new BucketVersioningConfiguration(state));
            assertThatThrownBy(() -> service.putImmutable(key, bytes, "application/pdf", sha)).hasMessageContaining("从未开启版本控制");
        }
        verify(client, never()).putObject(any(PutObjectRequest.class));
    }

    @Test void versioningPermissionFailurePreventsUpload() {
        when(client.getBucketVersioningConfiguration(storage.getBucket())).thenThrow(new CosServiceException("access denied"));
        assertThatThrownBy(() -> service.putImmutable(key, bytes, "application/pdf", sha)).hasMessageContaining("安全存储失败");
        verify(client, never()).putObject(any(PutObjectRequest.class));
    }

    @Test void duplicateUploadVerifiesActualBytesEvenWithAWarmCache() {
        service.get(reference());
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(error("FileAlreadyExists", 409));
        ObjectMetadata metadata = new ObjectMetadata(); metadata.setETag("existing");
        when(client.getObjectMetadata(storage.getBucket(), key)).thenReturn(metadata);
        assertThat(service.putImmutable(key, bytes, "application/pdf", sha).etag()).isEqualTo("existing");
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(object(new byte[]{1, 2, 3, 4, 5}));
        assertThatThrownBy(() -> service.putImmutable(key, bytes, "application/pdf", sha)).hasMessageContaining("完整性");
        verify(client, times(3)).getObject(any(GetObjectRequest.class));
    }

    @Test void successfulUploadAlsoBypassesCacheForVerification() {
        service.get(reference());
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(object(new byte[]{1, 2, 3, 4, 5}));
        assertThatThrownBy(() -> service.putImmutable(key, bytes, "application/pdf", sha)).hasMessageContaining("完整性");
        verify(client, times(2)).getObject(any(GetObjectRequest.class));
    }

    @Test void arbitrary409IsNotMistakenForAnExistingFile() {
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(error("OtherConflict", 409));
        assertThatThrownBy(() -> service.putImmutable(key, bytes, "application/pdf", sha)).hasMessageContaining("安全存储失败");
        verify(client, never()).getObjectMetadata(anyString(), anyString());
        verify(client, never()).getObject(any(GetObjectRequest.class));
    }

    @Test void keepsVersionsAndVerifiesLengthAndDigestOnDownload() {
        var ref = new ObjectStorageService.ObjectReference(storage.getBucket(), key, "version-1", 5L, sha);
        assertThat(service.get(ref)).isEqualTo(bytes);
        assertThat(service.get(ref)).isEqualTo(bytes);
        var request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(request.capture());
        assertThat(request.getValue().getVersionId()).isEqualTo("version-1");
        assertThatThrownBy(() -> service.get(new ObjectStorageService.ObjectReference(storage.getBucket(), key, null, 6L, sha)))
                .hasMessageContaining("长度");
        assertThatThrownBy(() -> service.get(new ObjectStorageService.ObjectReference(storage.getBucket(), key, null, 5L, "bad")))
                .hasMessageContaining("完整性");
    }

    @Test void onlyConfiguredBucketAndPrefixAreReadable() {
        assertThatThrownBy(() -> service.get(new ObjectStorageService.ObjectReference("foreign", key, null, 5L, sha)))
                .hasMessageContaining("存储位置");
        assertThatThrownBy(() -> service.get(new ObjectStorageService.ObjectReference(storage.getBucket(), "other/key", null, 5L, sha)))
                .hasMessageContaining("不属于当前应用");
        verifyNoInteractions(client);
    }

    @Test void failedReadAndWriteAreNotReportedAsSuccess() {
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(new CosServiceException("unavailable"));
        assertThatThrownBy(() -> service.get(reference())).hasMessage("文件读取失败，请稍后重试");
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(new CosServiceException("unavailable"));
        assertThatThrownBy(() -> service.putImmutable(key, bytes, "application/pdf", sha)).hasMessage("文件安全存储失败，请稍后重试");
        verify(client, times(1)).putObject(any(PutObjectRequest.class));
    }

    @Test void disabledModeNeedsNoSecretsButRequiredStorageFailsFast() {
        storage.setEnabled(false);
        var disabled = new TencentCosObjectStorageService(storage, new CosStorageProperties());
        assertThat(disabled.isEnabled()).isFalse();
        assertThatThrownBy(() -> disabled.get(reference())).hasMessageContaining("尚未启用");
        storage.setRequired(true);
        assertThatThrownBy(() -> new TencentCosObjectStorageService(storage, new CosStorageProperties()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void validatesLocationAndCredentialsAndSupportsExplicitSessionTokens() {
        var config = new CosStorageProperties();
        assertThatThrownBy(() -> new TencentCosObjectStorageService(storage, config)).hasMessageContaining("secret-id");
        config.setSecretId("test-id"); config.setSecretKey("test-secret");
        assertThat(TencentCosObjectStorageService.credentials(config).getCOSAccessKeyId()).isEqualTo("test-id");
        config.setSessionToken("test-token");
        assertThat(((COSSessionCredentials) TencentCosObjectStorageService.credentials(config)).getSessionToken()).isEqualTo("test-token");
        storage.setBucket("");
        assertThatThrownBy(() -> new TencentCosObjectStorageService(storage, config)).hasMessageContaining("bucket 或 region");
    }

    @Test void springSelectsOnlyTencentProviderAndBindsNativeProperties() {
        new ApplicationContextRunner().withUserConfiguration(StorageProperties.class, ObjectStorageConfiguration.class)
                .withPropertyValues("tradepass.storage.provider=tencent-cos", "tradepass.storage.enabled=true",
                        "tradepass.storage.bucket=test-bucket-1250000000", "tradepass.storage.region=ap-shanghai",
                        "tradepass.storage.cos.secret-id=test-id", "tradepass.storage.cos.secret-key=test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ObjectStorageService.class);
                    assertThat(context.getBean(ObjectStorageService.class)).isInstanceOf(TencentCosObjectStorageService.class);
                    assertThat(context.getBean(CosStorageProperties.class).getSecretId()).isEqualTo("test-id");
                    assertThat(context.getBean(StorageProperties.class).getBucket()).isEqualTo("test-bucket-1250000000");
                });
    }

    @Test void closesOwnedClient() { service.shutdown(); verify(client).shutdown(); }

    private ObjectStorageService.ObjectReference reference() {
        return new ObjectStorageService.ObjectReference(storage.getBucket(), key, null, 5L, sha);
    }
    private static COSObject object(byte[] data) {
        var result = new COSObject();
        var metadata = new ObjectMetadata(); metadata.setContentLength(data.length);
        result.setObjectMetadata(metadata);
        result.setObjectContent(new COSObjectInputStream(new ByteArrayInputStream(data), mock(HttpRequestBase.class)));
        return result;
    }
    private static CosServiceException error(String code, int status) {
        var error = new CosServiceException("test error"); error.setErrorCode(code); error.setStatusCode(status); return error;
    }
}
