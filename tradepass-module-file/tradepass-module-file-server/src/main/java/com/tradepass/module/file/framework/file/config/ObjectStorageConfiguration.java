package com.tradepass.module.file.framework.file.config;

import com.tradepass.module.file.framework.file.core.client.AliyunOssObjectStorageService;
import com.tradepass.module.file.framework.file.core.client.CloudBaseCosObjectStorageService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OssStorageProperties.class)
@Import({CloudBaseCosObjectStorageService.class, AliyunOssObjectStorageService.class})
public class ObjectStorageConfiguration { }
