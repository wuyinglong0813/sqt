package com.tradepass.module.identity.framework.config;

import com.tradepass.module.identity.controller.internal.IdentityInternalController;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;
@SpringBootConfiguration
@Import({com.tradepass.framework.runtime.config.BusinessRuntimeConfiguration.class, com.tradepass.module.identity.controller.internal.IdentityInternalController.class})
public class IdentityConfiguration { }
