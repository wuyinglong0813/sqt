package com.tradepass.module.file.framework.file.core.client;
import com.tradepass.framework.common.util.FileTypeInspector;
import com.tradepass.module.file.api.file.ObjectStorageService;
import com.tradepass.module.file.framework.file.config.OssStorageProperties;

import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.*;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.storage.config.StorageProperties;
import com.tradepass.framework.cache.core.BoundedBinaryCache;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/** Server storage adapter. The original persisted bucket/key/version/hash contract is unchanged. */
@Service
@ConditionalOnProperty(name = "tradepass.storage.provider", havingValue = "aliyun-oss")
public class AliyunOssObjectStorageService implements ObjectStorageService {
    private static final Logger log = LoggerFactory.getLogger(AliyunOssObjectStorageService.class);
    private final StorageProperties storage;
    private final OssStorageProperties properties;
    private final OSS client;
    private final CloudBaseCosObjectStorageService legacy;
    private final BoundedBinaryCache cache = new BoundedBinaryCache(Duration.ofMinutes(5), 64L * 1024 * 1024);

    @Autowired
    public AliyunOssObjectStorageService(StorageProperties storage, OssStorageProperties properties) throws Exception {
        this.storage = storage;
        this.properties = properties;
        if (storage.isRequired() && !storage.isEnabled()) throw new IllegalStateException("生产环境必须启用对象存储");
        if (!storage.isEnabled()) { client = null; legacy = null; return; }
        validateConfiguration(properties);
        ClientBuilderConfiguration config = new ClientBuilderConfiguration();
        config.setSignatureVersion(SignVersion.V4);
        config.setConnectionTimeout(Math.max(1000, storage.getConnectionTimeoutMillis()));
        config.setSocketTimeout(Math.max(1000, storage.getSocketTimeoutMillis()));
        config.setMaxErrorRetry(0);
        client = OSSClientBuilder.create().endpoint(properties.getEndpoint()).region(properties.getRegion())
                .credentialsProvider(CredentialsProviderFactory.newEnvironmentVariableCredentialsProvider())
                .clientConfiguration(config).build();
        try { legacy = legacyReader(storage, properties); }
        catch (RuntimeException error) { client.shutdown(); throw error; }
    }

    AliyunOssObjectStorageService(StorageProperties storage, OssStorageProperties properties, OSS client,
                                 CloudBaseCosObjectStorageService legacy) {
        validateConfiguration(properties);
        this.storage = storage; this.properties = properties; this.client = client; this.legacy = legacy;
    }

    @Override public boolean isEnabled() { return client != null; }

    @Override public StoredObject putImmutable(String key, byte[] data, String type, String sha256) {
        requireEnabled();
        validateKey(key);
        if (data == null || data.length == 0) throw new BusinessException("不能上传空文件");
        String sha = FileTypeInspector.sha256(data);
        if (!sha.equalsIgnoreCase(sha256)) throw new BusinessException("文件摘要校验失败");
        try {
            // OSS ignores forbid-overwrite on versioned/suspended buckets. Check before every write.
            String versioning = client.getBucketVersioning(properties.getBucket()).getStatus();
            if (hasText(versioning) && !"Off".equals(versioning)) {
                throw new BusinessException("文件存储桶必须关闭版本控制，以保证同名文件不可覆盖");
            }
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(data.length);
            metadata.setContentType(type);
            metadata.setServerSideEncryption("AES256");
            metadata.setHeader("x-oss-forbid-overwrite", "true");
            metadata.setHeader("x-oss-object-acl", "private");
            metadata.addUserMetadata("sha256", sha);
            metadata.addUserMetadata("storage-provider", "ALIYUN_OSS");
            PutObjectResult result = client.putObject(new PutObjectRequest(properties.getBucket(), key,
                    new ByteArrayInputStream(data), metadata));
            StoredObject stored = stored(key, result.getVersionId(), result.getETag(), data.length, sha);
            // Bypass cache: successful writes and conflicts must be verified against the actual object.
            download(stored.reference());
            return stored;
        } catch (OSSException error) {
            if ("FileAlreadyExists".equals(error.getErrorCode())) return existing(key, data.length, sha);
            logFailure("put", error);
            throw new BusinessException("文件安全存储失败，请稍后重试");
        } catch (BusinessException error) { throw error; }
        catch (RuntimeException error) {
            logFailure("put", error);
            throw new BusinessException("文件安全存储失败，请稍后重试");
        }
    }

    @Override public byte[] get(ObjectReference reference) {
        requireEnabled();
        if (reference == null) throw new BusinessException("文件存储位置不正确");
        if (legacy != null && properties.getLegacyCosBucket().equals(reference.bucket())) return legacy.get(reference);
        if (!properties.getBucket().equals(reference.bucket())) throw new BusinessException("文件存储位置不正确");
        validateKey(reference.objectKey());
        String cacheKey = reference.bucket() + "\n" + reference.objectKey() + "\n" + reference.versionId()
                + "\n" + reference.fileSize() + "\n" + reference.sha256();
        return cache.get(cacheKey, () -> download(reference));
    }

    private byte[] download(ObjectReference reference) {
        GetObjectRequest request = new GetObjectRequest(reference.bucket(), reference.objectKey());
        if (hasText(reference.versionId())) request.setVersionId(reference.versionId());
        try (OSSObject object = client.getObject(request)) {
            byte[] data = object.getObjectContent().readAllBytes();
            if (reference.fileSize() != null && reference.fileSize() >= 0 && data.length != reference.fileSize()) {
                throw new BusinessException("文件长度校验失败，请联系管理员");
            }
            if (!FileTypeInspector.sha256(data).equalsIgnoreCase(reference.sha256())) {
                throw new BusinessException("文件完整性校验失败，请联系管理员");
            }
            return data;
        } catch (BusinessException error) { throw error; }
        catch (IOException | RuntimeException error) {
            logFailure("get", error);
            throw new BusinessException("文件读取失败，请稍后重试");
        }
    }

    private StoredObject existing(String key, long size, String sha) {
        try {
            ObjectMetadata metadata = client.getObjectMetadata(properties.getBucket(), key);
            StoredObject stored = stored(key, metadata.getVersionId(), metadata.getETag(), size, sha);
            download(stored.reference());
            return stored;
        } catch (BusinessException error) { throw error; }
        catch (RuntimeException error) {
            logFailure("verify", error);
            throw new BusinessException("文件安全存储冲突，请联系管理员");
        }
    }

    private StoredObject stored(String key, String version, String etag, long size, String sha) {
        return new StoredObject("ALIYUN_OSS", properties.getBucket(), key, version, etag, "SSE_OSS_AES256", size, sha);
    }

    private void validateKey(String key) {
        if (!hasText(key) || key.startsWith("/") || key.contains("\\") || key.contains("..") || key.length() > 900) {
            throw new BusinessException("文件对象标识不正确");
        }
        String prefix = storage.getKeyPrefix() == null ? "" : storage.getKeyPrefix().trim().replaceAll("^/+|/+$", "");
        if (!key.startsWith((prefix.isBlank() ? "tradepass" : prefix) + "/")) {
            throw new BusinessException("文件对象不属于当前应用");
        }
    }

    private static void validateConfiguration(OssStorageProperties properties) {
        URI endpoint = URI.create(properties.getEndpoint());
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || !hasText(properties.getRegion()) || !hasText(properties.getBucket())) {
            throw new IllegalStateException("OSS 必须配置 HTTPS endpoint、region 和 bucket");
        }
        if (properties.getBucket().equals(properties.getLegacyCosBucket())) {
            throw new IllegalStateException("OSS 和历史 COS 的 bucket 不能重名");
        }
    }

    private static CloudBaseCosObjectStorageService legacyReader(StorageProperties storage, OssStorageProperties properties) {
        if (!hasText(properties.getLegacyCosBucket())) return null;
        String id = System.getenv("TRADEPASS_LEGACY_COS_SECRET_ID");
        String secret = System.getenv("TRADEPASS_LEGACY_COS_SECRET_KEY");
        String token = System.getenv("TRADEPASS_LEGACY_COS_SESSION_TOKEN");
        if (!hasText(id) || !hasText(secret)) throw new IllegalStateException("历史 COS 读取需要独立的只读凭证");
        COSCredentials credentials = hasText(token) ? new BasicSessionCredentials(id, secret, token) : new BasicCOSCredentials(id, secret);
        StorageProperties legacyProperties = new StorageProperties();
        legacyProperties.setEnabled(true);
        legacyProperties.setBucket(properties.getLegacyCosBucket());
        legacyProperties.setRegion(properties.getLegacyCosRegion());
        legacyProperties.setKeyPrefix(properties.getLegacyCosKeyPrefix());
        ClientConfig config = new ClientConfig(new Region(properties.getLegacyCosRegion()));
        config.setHttpProtocol(HttpProtocol.https);
        config.setConnectionTimeout(Math.max(1000, storage.getConnectionTimeoutMillis()));
        config.setSocketTimeout(Math.max(1000, storage.getSocketTimeoutMillis()));
        config.setMaxErrorRetry(0);
        return new CloudBaseCosObjectStorageService(legacyProperties, new COSClient(credentials, config), null);
    }

    @PreDestroy public void shutdown() {
        if (client != null) client.shutdown();
        if (legacy != null) legacy.shutdown();
    }
    private void requireEnabled() { if (!isEnabled()) throw new BusinessException("文件安全存储服务尚未启用"); }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private void logFailure(String action, Exception error) {
        // SDK exception messages can contain request details; log only diagnostic identifiers.
        log.error("OSS {} failed: type={}, code={}, requestId={}", action, error.getClass().getSimpleName(),
                error instanceof OSSException oss ? oss.getErrorCode() : "", error instanceof OSSException oss ? oss.getRequestId() : "");
    }
}
