package com.tradepass.module.settlement.framework.config;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;
@SpringBootConfiguration
@Import({com.tradepass.framework.runtime.config.BusinessRuntimeConfiguration.class})
public class SettlementConfiguration { }
