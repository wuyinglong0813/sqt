package com.tradepass.module.file.framework.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Native Spring properties, including values imported from Nacos. */
@ConfigurationProperties("tradepass.storage.cos")
public class CosStorageProperties {
    private String secretId = "";
    private String secretKey = "";
    private String sessionToken = "";

    public String getSecretId() { return secretId; }
    public void setSecretId(String value) { secretId = value; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String value) { secretKey = value; }
    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String value) { sessionToken = value; }
}
