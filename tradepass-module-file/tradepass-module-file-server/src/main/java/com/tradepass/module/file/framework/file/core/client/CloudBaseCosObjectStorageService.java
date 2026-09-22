package com.tradepass.module.file.framework.file.core.client;
import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;

import com.tradepass.framework.cache.core.BoundedBinaryCache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.COSCredentialsProvider;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.region.Region;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.storage.config.StorageProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "tradepass.storage.provider", havingValue = "cloudbase-cos", matchIfMissing = true)
public class CloudBaseCosObjectStorageService implements ObjectStorageService {
    private static final Logger log = LoggerFactory.getLogger(CloudBaseCosObjectStorageService.class);
    private static final String PROVIDER = "CLOUDBASE_COS";
    private static final String ENCRYPTION = "SSE_COS_AES256";
    private static final String FORBID_OVERWRITE_HEADER = "x-cos-forbid-overwrite";

    private final StorageProperties properties;
    private final CloudBaseOpenApiClient openApiClient;
    private final COSClient cosClient;
    private final BoundedBinaryCache downloadCache;

    @Autowired
    public CloudBaseCosObjectStorageService(StorageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        if (properties.isRequired() && !properties.isEnabled()) {
            throw new IllegalStateException("生产环境必须启用微信云托管对象存储");
        }
        if (!properties.isEnabled()) {
            this.openApiClient = null;
            this.cosClient = null;
            this.downloadCache = new BoundedBinaryCache(Duration.ofMinutes(5), 64L * 1024 * 1024);
            log.warn("微信云托管对象存储未启用，文件将继续使用仅限开发环境的 MySQL BLOB 兼容路径");
            return;
        }
        validateConfiguration(properties);
        this.openApiClient = new CloudBaseOpenApiClient(objectMapper, properties);
        COSCredentialsProvider credentialsProvider = new CloudBaseCosCredentialsProvider(
                openApiClient, properties.getCredentialRefreshSkewSeconds());
        this.cosClient = createClient(properties, credentialsProvider);
        this.downloadCache = new BoundedBinaryCache(Duration.ofMinutes(5), 64L * 1024 * 1024);
    }

    CloudBaseCosObjectStorageService(StorageProperties properties, COSClient cosClient,
                                     CloudBaseOpenApiClient openApiClient) {
        this.properties = properties;
        validateConfiguration(properties);
        this.cosClient = cosClient;
        this.openApiClient = openApiClient;
        this.downloadCache = new BoundedBinaryCache(Duration.ofMinutes(5), 64L * 1024 * 1024);
    }

    @Override
    public boolean isEnabled() {
        return cosClient != null;
    }

    @Override
    public StoredObject putImmutable(String objectKey, byte[] data, String contentType, String sha256) {
        requireEnabled();
        validateObjectKey(objectKey);
        if (data == null || data.length == 0) {
            throw new BusinessException("不能上传空文件");
        }
        String actualSha = FileTypeInspector.sha256(data);
        if (sha256 == null || !actualSha.equalsIgnoreCase(sha256)) {
            throw new BusinessException("文件摘要校验失败");
        }

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(data.length);
        metadata.setContentType(contentType);
        metadata.setServerSideEncryption("AES256");
        metadata.addUserMetadata("sha256", actualSha);
        metadata.addUserMetadata("storage-provider", PROVIDER);
        metadata.addUserMetadata("fileid", openApiClient.encodeFileMetadata(bucket(), objectKey));

        PutObjectRequest request = new PutObjectRequest(bucket(), objectKey,
                new ByteArrayInputStream(data), metadata);
        request.putCustomRequestHeader(FORBID_OVERWRITE_HEADER, "true");
        try {
            PutObjectResult result = cosClient.putObject(request);
            StoredObject stored = stored(objectKey, blankToNull(result.getVersionId()),
                    blankToNull(result.getETag()), data.length, actualSha);
            get(stored.reference());
            return stored;
        } catch (CosServiceException exception) {
            if ("FileAlreadyExists".equals(exception.getErrorCode()) || exception.getStatusCode() == 409) {
                return existingImmutableObject(objectKey, data.length, actualSha);
            }
            logCosError("上传", objectKey, exception);
            throw new BusinessException("文件安全存储失败，请稍后重试");
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.error("微信云托管对象存储上传失败，objectKey={}", objectKey, exception);
            throw new BusinessException("文件安全存储失败，请稍后重试");
        }
    }

    @Override
    public byte[] get(ObjectReference reference) {
        requireEnabled();
        if (reference == null || !bucket().equals(reference.bucket())) {
            throw new BusinessException("文件存储位置不正确");
        }
        validateObjectKey(reference.objectKey());
        String cacheKey = String.join("\n",
                safe(reference.bucket()), safe(reference.objectKey()), safe(reference.versionId()),
                String.valueOf(reference.fileSize()), safe(reference.sha256()));
        return downloadCache.get(cacheKey, () -> downloadAndVerify(reference));
    }

    private byte[] downloadAndVerify(ObjectReference reference) {
        GetObjectRequest request = hasText(reference.versionId())
                ? new GetObjectRequest(reference.bucket(), reference.objectKey(), reference.versionId())
                : new GetObjectRequest(reference.bucket(), reference.objectKey());
        try (COSObject object = cosClient.getObject(request)) {
            long actualSize = object.getObjectMetadata().getContentLength();
            if (reference.fileSize() != null && reference.fileSize() >= 0
                    && actualSize >= 0 && actualSize != reference.fileSize()) {
                throw new BusinessException("文件长度校验失败，请联系管理员");
            }
            byte[] data = object.getObjectContent().readAllBytes();
            if (reference.fileSize() != null && reference.fileSize() >= 0
                    && data.length != reference.fileSize()) {
                throw new BusinessException("文件长度校验失败，请联系管理员");
            }
            String actualSha = FileTypeInspector.sha256(data);
            if (!hasText(reference.sha256()) || !actualSha.equalsIgnoreCase(reference.sha256())) {
                throw new BusinessException("文件完整性校验失败，请联系管理员");
            }
            return data;
        } catch (BusinessException exception) {
            throw exception;
        } catch (CosServiceException exception) {
            logCosError("下载", reference.objectKey(), exception);
            throw new BusinessException("文件读取失败，请稍后重试");
        } catch (IOException | RuntimeException exception) {
            log.error("微信云托管对象存储读取失败，objectKey={}", reference.objectKey(), exception);
            throw new BusinessException("文件读取失败，请稍后重试");
        }
    }

    public String keyPrefix() {
        String value = properties.getKeyPrefix() == null ? "" : properties.getKeyPrefix().trim();
        value = value.replaceAll("^/+|/+$", "");
        return value.isBlank() ? "tradepass" : value;
    }

    @PreDestroy
    public void shutdown() {
        if (cosClient != null) {
            cosClient.shutdown();
        }
    }

    private StoredObject existingImmutableObject(String objectKey, long fileSize, String sha256) {
        try {
            ObjectMetadata metadata = cosClient.getObjectMetadata(bucket(), objectKey);
            StoredObject stored = stored(objectKey, blankToNull(metadata.getVersionId()),
                    blankToNull(metadata.getETag()), fileSize, sha256);
            get(stored.reference());
            return stored;
        } catch (BusinessException exception) {
            throw exception;
        } catch (CosServiceException exception) {
            logCosError("校验已存在对象", objectKey, exception);
            throw new BusinessException("文件安全存储冲突，请联系管理员");
        } catch (RuntimeException exception) {
            log.error("微信云托管对象存储校验已存在对象失败，objectKey={}", objectKey, exception);
            throw new BusinessException("文件安全存储冲突，请联系管理员");
        }
    }

    private StoredObject stored(String objectKey, String versionId, String etag,
                                long fileSize, String sha256) {
        return new StoredObject(PROVIDER, bucket(), objectKey, versionId, etag,
                ENCRYPTION, fileSize, sha256);
    }

    private COSClient createClient(StorageProperties value, COSCredentialsProvider credentialsProvider) {
        ClientConfig config = new ClientConfig(new Region(value.getRegion().trim()));
        config.setHttpProtocol(HttpProtocol.https);
        config.setConnectionTimeout(Math.max(1000, value.getConnectionTimeoutMillis()));
        config.setSocketTimeout(Math.max(1000, value.getSocketTimeoutMillis()));
        config.setMaxErrorRetry(3);
        return new COSClient(credentialsProvider, config);
    }

    private void validateConfiguration(StorageProperties value) {
        if (!value.isEnabled() || !hasText(value.getBucket()) || !hasText(value.getRegion())) {
            throw new IllegalStateException("微信云托管对象存储已启用，但 bucket 或 region 未完整配置");
        }
    }

    private void validateObjectKey(String objectKey) {
        if (!hasText(objectKey) || objectKey.startsWith("/") || objectKey.contains("\\")
                || objectKey.contains("..") || objectKey.length() > 900) {
            throw new BusinessException("文件对象标识不正确");
        }
        if (!objectKey.startsWith(keyPrefix() + "/")) {
            throw new BusinessException("文件对象不属于当前应用");
        }
    }

    private String bucket() {
        return properties.getBucket().trim();
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new BusinessException("文件安全存储服务尚未启用");
        }
    }

    private void logCosError(String action, String objectKey, CosServiceException exception) {
        log.error("微信云托管对象存储{}失败，objectKey={}, errorCode={}, requestId={}",
                action, objectKey, exception.getErrorCode(), exception.getRequestId());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String blankToNull(String value) {
        return hasText(value) ? value : null;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
