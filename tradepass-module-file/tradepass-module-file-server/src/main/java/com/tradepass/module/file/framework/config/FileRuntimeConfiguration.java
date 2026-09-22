package com.tradepass.module.file.framework.config;

import com.tradepass.framework.web.core.controller.ProbeController;
import com.tradepass.module.file.controller.internal.StorageInternalController;
import com.tradepass.framework.runtime.config.RuntimeWebConfiguration;
import com.tradepass.framework.runtime.config.TransportConfiguration;

import com.tradepass.framework.web.core.handler.GlobalExceptionHandler;
import com.tradepass.framework.web.core.interceptor.DevModeInterceptor;
import com.tradepass.framework.mybatis.config.IdentifierConfiguration;
import com.tradepass.framework.storage.config.StorageProperties;
import com.tradepass.module.file.controller.app.file.FileController;
import com.tradepass.module.file.framework.file.config.ObjectStorageConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"})
@Import({TransportConfiguration.class, RuntimeWebConfiguration.class, StorageInternalController.class,
        StorageProperties.class, ObjectStorageConfiguration.class, IdentifierConfiguration.class,
        GlobalExceptionHandler.class, DevModeInterceptor.class, FileController.class,
        com.tradepass.framework.web.core.controller.ProbeController.class})
public class FileRuntimeConfiguration { }
