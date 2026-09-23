package com.tradepass.business;

import com.tradepass.module.contract.framework.config.CallbackJobConfiguration;
import com.tradepass.module.contract.framework.config.CallbackMessagingConfiguration;
import com.tradepass.module.file.controller.internal.StorageInternalController;
import com.tradepass.module.file.framework.file.config.ObjectStorageConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({com.tradepass.framework.runtime.config.BusinessRuntimeConfiguration.class,
        BusinessDatabaseConfiguration.class,
        CallbackMessagingConfiguration.class, CallbackJobConfiguration.class,
        ObjectStorageConfiguration.class, StorageInternalController.class})
public class BusinessConfiguration { }
