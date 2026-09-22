package com.tradepass.module.file.framework.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tradepass.storage.oss")
public class OssStorageProperties {
    private String endpoint = "";
    private String region = "";
    private String bucket = "";
    private String legacyCosBucket = "";
    private String legacyCosRegion = "ap-shanghai";
    private String legacyCosKeyPrefix = "tradepass";

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getLegacyCosBucket() { return legacyCosBucket; }
    public void setLegacyCosBucket(String bucket) { this.legacyCosBucket = bucket; }
    public String getLegacyCosRegion() { return legacyCosRegion; }
    public void setLegacyCosRegion(String region) { this.legacyCosRegion = region; }
    public String getLegacyCosKeyPrefix() { return legacyCosKeyPrefix; }
    public void setLegacyCosKeyPrefix(String prefix) { this.legacyCosKeyPrefix = prefix; }
}
