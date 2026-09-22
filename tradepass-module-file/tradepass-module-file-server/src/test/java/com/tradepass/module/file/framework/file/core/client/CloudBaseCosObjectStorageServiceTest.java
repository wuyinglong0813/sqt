package com.tradepass.module.file.framework.file.core.client;
import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.storage.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.http.client.methods.HttpRequestBase;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class CloudBaseCosObjectStorageServiceTest {

    @Test
    void uploadsEncryptedImmutableObjectWithCloudBaseMetadataAndVerifiesIt() {
        COSClient cosClient = mock(COSClient.class);
        CloudBaseOpenApiClient openApi = mock(CloudBaseOpenApiClient.class);
        CloudBaseCosObjectStorageService service = service(cosClient, openApi);
        byte[] data = "%PDF-1.7 cloudbase".getBytes();
        String sha256 = FileTypeInspector.sha256(data);
        String key = "prod/contract/3/8/v1/" + sha256 + ".pdf";

        when(openApi.encodeFileMetadata("cloudbase-private-bucket", key)).thenReturn("signed-file-meta");
        PutObjectResult result = new PutObjectResult();
        result.setVersionId("version-1");
        result.setETag("etag-1");
        when(cosClient.putObject(any(PutObjectRequest.class))).thenReturn(result);
        when(cosClient.getObject(any(GetObjectRequest.class))).thenAnswer(ignored -> object(data));

        ObjectStorageService.StoredObject stored = service.putImmutable(
                key, data, "application/pdf", sha256);

        assertThat(stored.provider()).isEqualTo("CLOUDBASE_COS");
        assertThat(stored.bucket()).isEqualTo("cloudbase-private-bucket");
        assertThat(stored.objectKey()).isEqualTo(key);
        assertThat(stored.versionId()).isEqualTo("version-1");
        assertThat(stored.etag()).isEqualTo("etag-1");
        assertThat(stored.encryptionAlgorithm()).isEqualTo("SSE_COS_AES256");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(cosClient).putObject(request.capture());
        assertThat(request.getValue().getCustomRequestHeaders())
                .containsEntry("x-cos-forbid-overwrite", "true");
        assertThat(request.getValue().getMetadata().getContentLength()).isEqualTo(data.length);
        assertThat(request.getValue().getMetadata().getContentType()).isEqualTo("application/pdf");
        assertThat(request.getValue().getMetadata().getServerSideEncryption()).isEqualTo("AES256");
        assertThat(request.getValue().getMetadata().getUserMetadata())
                .containsEntry("fileid", "signed-file-meta")
                .containsEntry("sha256", sha256)
                .containsEntry("storage-provider", "CLOUDBASE_COS");
    }

    @Test
    void validatesReferencesContentAndObjectKeys() {
        COSClient cosClient = mock(COSClient.class);
        CloudBaseCosObjectStorageService service = service(cosClient,
                mock(CloudBaseOpenApiClient.class));
        byte[] data = new byte[]{1, 2, 3};
        String sha256 = FileTypeInspector.sha256(data);
        String key = "prod/file/3/8/logistics/2026/07/a.jpg";
        when(cosClient.getObject(any(GetObjectRequest.class))).thenAnswer(ignored -> object(data));

        assertThat(service.get(new ObjectStorageService.ObjectReference(
                "cloudbase-private-bucket", key, null, 3L, sha256))).containsExactly(data);
        assertThat(service.get(new ObjectStorageService.ObjectReference(
                "cloudbase-private-bucket", key, null, 3L, sha256))).containsExactly(data);
        verify(cosClient, times(1)).getObject(any(GetObjectRequest.class));
        assertThatThrownBy(() -> service.get(new ObjectStorageService.ObjectReference(
                "foreign", key, null, 3L, sha256)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文件存储位置不正确");
        assertThatThrownBy(() -> service.get(new ObjectStorageService.ObjectReference(
                "cloudbase-private-bucket", key, null, 3L, "0".repeat(64))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("完整性校验");
        assertThatThrownBy(() -> service.putImmutable(
                "../escape", data, "application/octet-stream", sha256))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标识不正确");
    }

    @Test
    void reusesExistingObjectOnlyAfterIntegrityVerification() {
        COSClient cosClient = mock(COSClient.class);
        CloudBaseOpenApiClient openApi = mock(CloudBaseOpenApiClient.class);
        CloudBaseCosObjectStorageService service = service(cosClient, openApi);
        byte[] data = new byte[]{4, 5, 6};
        String sha256 = FileTypeInspector.sha256(data);
        String key = "prod/file/3/8/other/2026/07/a.pdf";
        when(openApi.encodeFileMetadata("cloudbase-private-bucket", key)).thenReturn("meta");
        CosServiceException existing = new CosServiceException("exists");
        existing.setStatusCode(409);
        existing.setErrorCode("FileAlreadyExists");
        when(cosClient.putObject(any(PutObjectRequest.class))).thenThrow(existing);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setETag("existing-etag");
        when(cosClient.getObjectMetadata("cloudbase-private-bucket", key)).thenReturn(metadata);
        when(cosClient.getObject(any(GetObjectRequest.class))).thenAnswer(ignored -> object(data));

        ObjectStorageService.StoredObject stored = service.putImmutable(
                key, data, "application/pdf", sha256);

        assertThat(stored.etag()).isEqualTo("existing-etag");
        assertThat(stored.sha256()).isEqualTo(sha256);
    }

    @Test
    void failsClosedForInvalidProductionConfigurationAndSupportsDevelopmentFallback() {
        StorageProperties disabled = new StorageProperties();
        disabled.setRequired(true);
        assertThatThrownBy(() -> new CloudBaseCosObjectStorageService(disabled, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须启用");

        StorageProperties missingBucket = new StorageProperties();
        missingBucket.setEnabled(true);
        assertThatThrownBy(() -> new CloudBaseCosObjectStorageService(missingBucket, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bucket 或 region");

        CloudBaseCosObjectStorageService fallback = new CloudBaseCosObjectStorageService(
                new StorageProperties(), new ObjectMapper());
        assertThat(fallback.isEnabled()).isFalse();
    }

    private CloudBaseCosObjectStorageService service(COSClient cosClient,
                                                     CloudBaseOpenApiClient openApi) {
        return new CloudBaseCosObjectStorageService(properties(), cosClient, openApi);
    }

    private StorageProperties properties() {
        StorageProperties properties = new StorageProperties();
        properties.setEnabled(true);
        properties.setBucket("cloudbase-private-bucket");
        properties.setRegion("ap-shanghai");
        properties.setKeyPrefix("prod");
        return properties;
    }

    private COSObject object(byte[] data) {
        COSObject object = new COSObject();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(data.length);
        object.setObjectMetadata(metadata);
        object.setObjectContent(new COSObjectInputStream(
                new ByteArrayInputStream(data), mock(HttpRequestBase.class)));
        return object;
    }
}
