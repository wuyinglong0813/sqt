CREATE TABLE company_certification_application (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    applicant_user_id BIGINT NOT NULL,
    provider_request_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED',
    review_reason VARCHAR(512),
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cert_provider_request (provider_request_id),
    INDEX idx_cert_applicant (applicant_user_id, created_at),
    INDEX idx_cert_company_status (company_id, status),
    CONSTRAINT fk_cert_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_cert_applicant FOREIGN KEY (applicant_user_id) REFERENCES sys_user(id)
);

ALTER TABLE role_def
    ADD COLUMN system_role TINYINT(1) NOT NULL DEFAULT 0 AFTER name;

UPDATE role_def
SET code = CONCAT('CUSTOM_', id)
WHERE code IS NULL OR code = '';

UPDATE role_def
SET system_role = 1
WHERE code IN ('LEGAL', 'ADMIN', 'SALES', 'PURCHASER', 'FINANCE');

ALTER TABLE role_def
    MODIFY COLUMN code VARCHAR(64) NOT NULL;

INSERT IGNORE INTO perm_def (code, label, sort_order) VALUES
    ('supplier_view', '供方首页', 1),
    ('buyer_view', '需方首页', 2),
    ('counterparty_manage', '合作企业管理', 3),
    ('order_view', '订单查看', 4),
    ('order_create', '订单创建', 5),
    ('contract_template', '合同模板管理', 6),
    ('contract_sign', '合同发起与确认', 7),
    ('contract_view', '合同查看', 8),
    ('invoice_view', '发票查看', 9),
    ('reconciliation', '订单金额汇总', 10),
    ('inventory_view', '库存查看', 11),
    ('member_manage', '成员管理', 12),
    ('auth_manage', '授权管理', 13),
    ('company_manage', '企业认证管理', 14),
    ('seal_manage', '电子章管理', 15);

INSERT INTO role_def (company_id, code, name, system_role, permissions)
SELECT c.id, 'LEGAL', '法人', 1, JSON_ARRAY('all') FROM company c
ON DUPLICATE KEY UPDATE system_role = 1;

INSERT INTO role_def (company_id, code, name, system_role, permissions)
SELECT c.id, 'ADMIN', '管理员', 1,
       JSON_ARRAY('member_manage', 'auth_manage', 'company_manage', 'seal_manage', 'contract_template')
FROM company c
ON DUPLICATE KEY UPDATE system_role = 1;

INSERT INTO role_def (company_id, code, name, system_role, permissions)
SELECT c.id, 'SALES', '销售员', 1,
       JSON_ARRAY('supplier_view', 'counterparty_manage', 'order_view', 'order_create', 'contract_sign', 'contract_view', 'reconciliation')
FROM company c
ON DUPLICATE KEY UPDATE system_role = 1;

INSERT INTO role_def (company_id, code, name, system_role, permissions)
SELECT c.id, 'PURCHASER', '采购员', 1,
       JSON_ARRAY('buyer_view', 'order_create', 'contract_view', 'order_view', 'contract_sign', 'reconciliation')
FROM company c
ON DUPLICATE KEY UPDATE system_role = 1;

INSERT INTO role_def (company_id, code, name, system_role, permissions)
SELECT c.id, 'FINANCE', '财务', 1, JSON_ARRAY('invoice_view', 'reconciliation')
FROM company c
ON DUPLICATE KEY UPDATE system_role = 1;
