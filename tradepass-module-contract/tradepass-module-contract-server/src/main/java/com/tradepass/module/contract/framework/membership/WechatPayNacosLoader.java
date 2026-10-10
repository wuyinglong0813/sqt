package com.tradepass.module.contract.framework.membership;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.*;
import com.alibaba.nacos.api.config.listener.Listener;
import com.tradepass.module.contract.service.payment.WechatPayConfigStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.util.concurrent.*;

@Service
public class WechatPayNacosLoader {
    private static final Logger log=LoggerFactory.getLogger(WechatPayNacosLoader.class);
    private final WechatPayConfigStore settings;
    private final ObjectProvider<NacosConfigManager> managers;
    private final Environment environment;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"membership-payment-config"); t.setDaemon(true); return t;
    });
    private ConfigService config;
    private String dataId,group;
    private boolean listening;
    private final Listener listener=new Listener() {
        public Executor getExecutor() { return executor; }
        public void receiveConfigInfo(String ignored) { refresh(); }
    };
    public WechatPayNacosLoader(WechatPayConfigStore settings,ObjectProvider<NacosConfigManager> managers,Environment environment) {
        this.settings=settings; this.managers=managers; this.environment=environment;
    }
    @EventListener(ApplicationReadyEvent.class) public void start() {
        if (!environment.getProperty("spring.cloud.nacos.config.enabled",Boolean.class,false)) return;
        dataId=environment.getProperty("tradepass.payment.data-id","tradepass-payment.yaml");
        group=environment.getProperty("spring.cloud.nacos.config.group","TRADEPASS_CORE");
        executor.execute(this::refresh); executor.scheduleWithFixedDelay(this::refresh,30,30,TimeUnit.SECONDS);
    }
    private void refresh() {
        try {
            if (config==null) {
                var manager=managers.getIfAvailable();
                if (manager==null) throw new IllegalStateException();
                config=manager.getConfigService();
            }
            if (!listening) { config.addListener(dataId,group,listener); listening=true; }
            settings.apply(config.getConfig(dataId,group,5000));
        } catch(Exception e) {
            settings.failed();
            // Do not log source content, PEM, API keys, or exception text containing configuration values.
            log.warn("Payment configuration refresh failed; retained previous configuration ({})",e.getClass().getSimpleName());
        }
    }
    @PreDestroy public void close() {
        if (config!=null && listening) config.removeListener(dataId,group,listener);
        executor.shutdownNow();
    }
}
