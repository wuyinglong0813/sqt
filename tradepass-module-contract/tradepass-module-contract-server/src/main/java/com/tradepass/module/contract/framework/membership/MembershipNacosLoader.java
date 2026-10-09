package com.tradepass.module.contract.framework.membership;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.tradepass.module.contract.service.membership.MembershipPolicyStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

/** Watches one optional policy document without refreshing database connections or credentials. */
@Service
public class MembershipNacosLoader {
    private static final Logger log = LoggerFactory.getLogger(MembershipNacosLoader.class);
    private final MembershipPolicyStore store;
    private final ObjectProvider<NacosConfigManager> managers;
    private final Environment environment;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "membership-policy"); thread.setDaemon(true); return thread;
    });
    private ConfigService config;
    private String dataId;
    private String group;
    private boolean listening;
    private final Listener listener = new Listener() {
        @Override public Executor getExecutor() { return executor; }
        @Override public void receiveConfigInfo(String ignored) { refresh(); }
    };

    public MembershipNacosLoader(MembershipPolicyStore store, ObjectProvider<NacosConfigManager> managers, Environment environment) {
        this.store = store; this.managers = managers; this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!environment.getProperty("spring.cloud.nacos.config.enabled", Boolean.class, false)) {
            // Explicit local YAML is for development/tests; no policy defaults to no free signature grants.
            store.apply(environment.getProperty("tradepass.membership.local-yaml", ""), "LOCAL");
            return;
        }
        dataId = environment.getProperty("tradepass.membership.data-id", "tradepass-membership.yaml");
        group = environment.getProperty("spring.cloud.nacos.config.group", "TRADEPASS_CORE");
        executor.execute(this::refresh);
        executor.scheduleWithFixedDelay(this::refresh, 30, 30, TimeUnit.SECONDS);
    }

    private void refresh() {
        try {
            if (config == null) {
                NacosConfigManager manager = managers.getIfAvailable();
                if (manager == null) throw new IllegalStateException("Nacos 配置客户端尚未就绪");
                config = manager.getConfigService();
            }
            if (!listening) {
                config.addListener(dataId, group, listener);
                listening = true;
            }
            // Re-read current content: delayed notifications must not apply an obsolete policy.
            String content = config.getConfig(dataId, group, 5000);
            store.apply(content, content == null ? "NACOS_MISSING" : "NACOS");
        } catch (Exception e) {
            store.failed("会员配置同步失败，保留上次有效规则");
            log.warn("Membership policy refresh failed: {}", e.getClass().getSimpleName());
        }
    }

    @PreDestroy public void close() {
        if (config != null && listening) config.removeListener(dataId, group, listener);
        executor.shutdownNow();
    }
}
