package com.tradepass.module.contract.framework.config;

import com.tradepass.module.contract.framework.config.CallbackJobConfiguration;
import com.tradepass.module.contract.framework.config.CallbackMessagingConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;
@SpringBootConfiguration
@Import({com.tradepass.framework.runtime.config.BusinessRuntimeConfiguration.class,
        com.tradepass.module.contract.framework.config.CallbackMessagingConfiguration.class,
        com.tradepass.module.contract.framework.config.CallbackJobConfiguration.class})
public class ContractConfiguration { }
