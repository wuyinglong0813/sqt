package com.tradepass.framework.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tradepass.storage")
public class StorageProperties {
    private boolean enabled;
    private boolean required;
    private boolean migrateLegacyBlobs;
    private String bucket = "";
    private String region = "ap-shanghai";
    private String keyPrefix = "tradepass";
    private int connectionTimeoutMillis = 5000;
    private int socketTimeoutMillis = 30000;
    private int credentialRefreshSkewSeconds = 60;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public boolean isMigrateLegacyBlobs() { return migrateLegacyBlobs; }
    public void setMigrateLegacyBlobs(boolean migrateLegacyBlobs) { this.migrateLegacyBlobs = migrateLegacyBlobs; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public int getConnectionTimeoutMillis() { return connectionTimeoutMillis; }
    public void setConnectionTimeoutMillis(int connectionTimeoutMillis) { this.connectionTimeoutMillis = connectionTimeoutMillis; }
    public int getSocketTimeoutMillis() { return socketTimeoutMillis; }
    public void setSocketTimeoutMillis(int socketTimeoutMillis) { this.socketTimeoutMillis = socketTimeoutMillis; }
    public int getCredentialRefreshSkewSeconds() { return credentialRefreshSkewSeconds; }
    public void setCredentialRefreshSkewSeconds(int credentialRefreshSkewSeconds) { this.credentialRefreshSkewSeconds = credentialRefreshSkewSeconds; }
}
