package com.tradepass.module.file.framework.file.config;

import com.tradepass.module.file.framework.file.core.client.AliyunOssObjectStorageService;
import com.tradepass.module.file.framework.file.core.client.CloudBaseCosObjectStorageService;
import com.tradepass.module.file.framework.file.core.client.TencentCosObjectStorageService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OssStorageProperties.class, CosStorageProperties.class})
@Import({CloudBaseCosObjectStorageService.class, AliyunOssObjectStorageService.class, TencentCosObjectStorageService.class})
public class ObjectStorageConfiguration { }
