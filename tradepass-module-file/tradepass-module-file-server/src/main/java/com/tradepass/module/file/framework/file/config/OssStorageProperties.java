package com.tradepass.module.file.framework.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradepass.storage.oss")
public class OssStorageProperties {
    private String endpoint = "";
    private String region = "";
    private String bucket = "";
    private String accessKeyId = "";
    private String accessKeySecret = "";
    private String sessionToken = "";
    private String legacyCosBucket = "";
    private String legacyCosRegion = "ap-shanghai";
    private String legacyCosKeyPrefix = "tradepass";
    private String legacyCosSecretId = "";
    private String legacyCosSecretKey = "";
    private String legacyCosSessionToken = "";

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String value) { this.accessKeyId = value; }
    public String getAccessKeySecret() { return accessKeySecret; }
    public void setAccessKeySecret(String value) { this.accessKeySecret = value; }
    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String value) { this.sessionToken = value; }
    public String getLegacyCosBucket() { return legacyCosBucket; }
    public void setLegacyCosBucket(String bucket) { this.legacyCosBucket = bucket; }
    public String getLegacyCosRegion() { return legacyCosRegion; }
    public void setLegacyCosRegion(String region) { this.legacyCosRegion = region; }
    public String getLegacyCosKeyPrefix() { return legacyCosKeyPrefix; }
    public void setLegacyCosKeyPrefix(String prefix) { this.legacyCosKeyPrefix = prefix; }
    public String getLegacyCosSecretId() { return legacyCosSecretId; }
    public void setLegacyCosSecretId(String value) { this.legacyCosSecretId = value; }
    public String getLegacyCosSecretKey() { return legacyCosSecretKey; }
    public void setLegacyCosSecretKey(String value) { this.legacyCosSecretKey = value; }
    public String getLegacyCosSessionToken() { return legacyCosSessionToken; }
    public void setLegacyCosSessionToken(String value) { this.legacyCosSessionToken = value; }
}
