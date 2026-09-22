package com.tradepass.module.identity.framework.config;

import com.tradepass.framework.mybatis.core.ApplicationIds;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;

import java.util.List;

/**
 * 仅开发环境启用的本地基础账号与模板数据。所有环境的数据库结构都只由 Flyway 管理。
 */
@Component
@DependsOn("identifierGenerator")
@ConditionalOnProperty(name = "tradepass.demo-data.enabled", havingValue = "true")
public class DatabaseInitializer {
    private final JdbcTemplate db;

    public DatabaseInitializer(JdbcTemplate db) {
        this.db = db;
    }

    @PostConstruct
    void initData() {
        seedBaseData();
        seedTemplateCategories();
        seedContractTemplates();
        seedBusinessDocumentTemplates();
        seedRoles();
    }

    private void seedBaseData() {
        db.execute("INSERT IGNORE INTO company (id, name, credit_code, legal_person_name, certification_status, real_name_status, face_status, seal_status) VALUES (1, '河北光屿行贸易有限公司', '91130100MA00000001', '满帅', 'VERIFIED', 'VERIFIED', 'VERIFIED', 'UPLOADED')");
        db.execute("INSERT IGNORE INTO company (id, name, credit_code, legal_person_name, certification_status, real_name_status, face_status, seal_status) VALUES (2, '上海远航进出口有限公司', '91310000MA00000002', '王海', 'VERIFIED', 'VERIFIED', 'VERIFIED', 'UPLOADED')");
        db.execute("INSERT IGNORE INTO sys_user (id, openid, phone, nickname) VALUES (1, 'dev-openid-001', '18800000001', '满帅')");
        db.execute("INSERT IGNORE INTO sys_user (id, openid, phone, nickname) VALUES (2, 'dev-openid-002', '18800000002', '张采购')");
        db.execute("INSERT IGNORE INTO sys_user (id, openid, phone, nickname) VALUES (3, 'dev-openid-003', '18800000003', '李销售')");
        db.execute("INSERT IGNORE INTO sys_user (id, openid, phone, nickname) VALUES (4, 'dev-openid-004', '18800000004', '王财务')");
        db.execute("INSERT IGNORE INTO sys_user (id, openid, phone, nickname) VALUES (5, 'dev-openid-005', '18800000005', '赵管理')");
        db.execute("INSERT IGNORE INTO sys_user (id, openid, phone, nickname) VALUES (14, 'dev-openid-014', '18800000014', '王海')");
        db.update("INSERT IGNORE INTO company_member (id, company_id, user_id, role_code, is_legal_person, is_administrator) VALUES (?, 1, 1, 'LEGAL', 1, 0)", ApplicationIds.next());
        db.update("INSERT IGNORE INTO company_member (id, company_id, user_id, role_code, is_legal_person, is_administrator) VALUES (?, 1, 2, 'PURCHASER', 0, 0)", ApplicationIds.next());
        db.update("INSERT IGNORE INTO company_member (id, company_id, user_id, role_code, is_legal_person, is_administrator) VALUES (?, 1, 3, 'SALES', 0, 0)", ApplicationIds.next());
        db.update("INSERT IGNORE INTO company_member (id, company_id, user_id, role_code, is_legal_person, is_administrator) VALUES (?, 1, 4, 'FINANCE', 0, 0)", ApplicationIds.next());
        db.update("INSERT IGNORE INTO company_member (id, company_id, user_id, role_code, is_legal_person, is_administrator) VALUES (?, 1, 5, 'ADMIN', 0, 1)", ApplicationIds.next());
        db.update("INSERT IGNORE INTO company_member (id, company_id, user_id, role_code, is_legal_person) VALUES (?, 2, 1, 'SALES', 0)", ApplicationIds.next());
        db.update("INSERT IGNORE INTO company_member (id, company_id, user_id, role_code, is_legal_person, is_administrator) VALUES (?, 2, 14, 'LEGAL', 1, 0)", ApplicationIds.next());
    }

    private void seedTemplateCategories() {
        seedTemplateCategory(1, "采购", 1);
        seedTemplateCategory(1, "供货", 2);
        seedTemplateCategory(1, "交易", 3);
        seedTemplateCategory(1, "物流", 4);
        seedTemplateCategory(1, "服务", 5);
        seedTemplateCategory(1, "其他", 6);
    }

    private void seedContractTemplates() {
        seedContractTemplate(1, "标准采购合同模板", "采购");
        seedContractTemplate(1, "框架供货协议模板", "供货");
        seedContractTemplate(1, "单笔交易合同模板", "交易");
        seedContractTemplate(1, "物流服务合同模板", "物流");
    }

    private void seedBusinessDocumentTemplates() {
        seedBusinessDocumentTemplate(1, "SALES_ORDER", "标准销售单模板",
                "{\"columns\":[\"序号\",\"品名\",\"规格\",\"单位\",\"数量\",\"单价\",\"金额\",\"备注\"],\"blankRows\":8}");
        seedBusinessDocumentTemplate(1, "RETURN_ORDER", "标准退货单模板",
                "{\"columns\":[\"序号\",\"品名\",\"规格\",\"单位\",\"数量\",\"单价\",\"金额\",\"退货原因\"],\"blankRows\":8}");
    }

    private void seedRoles() {
        List.of(1L, 2L).forEach(companyId -> {
            seedRole(companyId, "法人", List.of("all"));
            seedRole(companyId, "管理员", List.of("member_manage", "auth_manage", "company_manage",
                    "seal_manage", "contract_template", "counterparty_view"));
            seedRole(companyId, "销售员", List.of("supplier_view", "counterparty_manage", "order_view", "order_create",
                    "contract_sign", "contract_view", "reconciliation", "contract_attachment_upload", "counterparty_view"));
            seedRole(companyId, "采购员", List.of("buyer_view", "order_create", "contract_view", "order_view",
                    "contract_sign", "reconciliation", "contract_attachment_upload", "sales_order_receive",
                    "inventory_view", "inventory_receive", "counterparty_view"));
            seedRole(companyId, "财务", List.of("invoice_view", "reconciliation", "contract_attachment_upload", "counterparty_view"));
        });
    }

    private void seedTemplateCategory(long companyId, String name, int sortOrder) {
        try { db.update("INSERT IGNORE INTO template_category (id, company_id, name, sort_order) VALUES (?, ?, ?, ?)", ApplicationIds.next(), companyId, name, sortOrder); } catch (Exception ignored) {}
    }

    private void seedContractTemplate(long companyId, String name, String category) {
        try {
            Integer count = db.queryForObject(
                    "SELECT COUNT(1) FROM contract_template WHERE company_id = ? AND name = ? AND category = ?",
                    Integer.class, companyId, name, category);
            if (count == null || count == 0) {
                db.update("INSERT INTO contract_template (id, company_id, name, category) VALUES (?, ?, ?, ?)", ApplicationIds.next(), companyId, name, category);
            }
        } catch (Exception ignored) {}
    }

    private void seedBusinessDocumentTemplate(long companyId, String type, String name, String content) {
        Integer count = db.queryForObject("""
                SELECT COUNT(1) FROM business_document_template
                WHERE company_id = ? AND document_type = ? AND name = ?
                """, Integer.class, companyId, type, name);
        if (count == null || count == 0) {
            db.update("""
                    INSERT INTO business_document_template
                    (id, company_id, document_type, name, content, created_by)
                    VALUES (?, ?, ?, ?, ?, 1)
                    """, ApplicationIds.next(), companyId, type, name, content);
        }
    }

    private void seedRole(long companyId, String name, List<String> permissions) {
        try {
            String json = "[\"" + String.join("\",\"", permissions) + "\"]";
            db.update("INSERT INTO role_def (id, company_id, code, name, system_role, permissions) VALUES (?, ?, ?, ?, 1, ?) ON DUPLICATE KEY UPDATE code = VALUES(code), system_role = 1, permissions = VALUES(permissions)",
                    ApplicationIds.next(), companyId, roleCode(name), name, json);
        } catch (Exception ignored) {}
    }

    private String roleCode(String name) {
        return switch (name) {
            case "法人" -> "LEGAL";
            case "管理员" -> "ADMIN";
            case "销售员" -> "SALES";
            case "采购员" -> "PURCHASER";
            case "财务" -> "FINANCE";
            default -> "CUSTOM";
        };
    }
}
