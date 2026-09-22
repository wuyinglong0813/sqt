package com.tradepass.module.identity.framework.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Recreates system reference data after a clean database; never creates users or companies. */
@Component
public class SystemPermissionInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public SystemPermissionInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<String[]> permissions = List.of(
                new String[]{"supplier_view", "供方首页"},
                new String[]{"buyer_view", "需方首页"},
                new String[]{"counterparty_manage", "合作企业管理"},
                new String[]{"order_view", "订单查看"},
                new String[]{"order_create", "订单创建"},
                new String[]{"contract_template", "合同模板管理"},
                new String[]{"contract_sign", "合同发起与确认"},
                new String[]{"contract_view", "合同查看"},
                new String[]{"invoice_view", "发票查看"},
                new String[]{"reconciliation", "对账情况"},
                new String[]{"inventory_view", "库存查看"},
                new String[]{"member_manage", "成员管理"},
                new String[]{"auth_manage", "授权管理"},
                new String[]{"company_manage", "企业认证管理"},
                new String[]{"seal_manage", "电子章管理"},
                new String[]{"contract_attachment_upload", "合同资料上传"},
                new String[]{"sales_order_receive", "销售单接收"},
                new String[]{"inventory_receive", "销售单入库"},
                new String[]{"counterparty_view", "合作企业查看"});
        for (int i = 0; i < permissions.size(); i++) {
            String[] permission = permissions.get(i);
            jdbc.update("INSERT IGNORE INTO perm_def (code, label, sort_order) VALUES (?, ?, ?)",
                    permission[0], permission[1], i + 1);
        }
    }
}
