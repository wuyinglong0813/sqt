-- Generated from original V1–V36 DDL; for an EMPTY owned database only.
-- Historical data must be copied by the cutover tool after source V36.
SET @tradepass_previous_fk_checks = @@FOREIGN_KEY_CHECKS;
SET FOREIGN_KEY_CHECKS = 0;

-- V1__baseline_schema.sql
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    openid VARCHAR(64) NOT NULL UNIQUE,
    phone VARCHAR(32),
    nickname VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- V1__baseline_schema.sql
CREATE TABLE IF NOT EXISTS company (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    credit_code VARCHAR(32) NOT NULL UNIQUE,
    legal_person_name VARCHAR(64) NOT NULL,
    certification_status VARCHAR(32) NOT NULL DEFAULT 'NOT_SUBMITTED',
    real_name_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    face_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    seal_status VARCHAR(32) NOT NULL DEFAULT 'NOT_UPLOADED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- V1__baseline_schema.sql
CREATE TABLE IF NOT EXISTS company_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    is_legal_person TINYINT(1) NOT NULL DEFAULT 0,
    is_administrator TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    custom_permissions JSON,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_company_user (company_id, user_id)
);

-- V1__baseline_schema.sql
CREATE TABLE IF NOT EXISTS company_invite (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL UNIQUE,
    type VARCHAR(32) NOT NULL DEFAULT 'member',
    used TINYINT(1) NOT NULL DEFAULT 0,
    used_by BIGINT,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_code (code)
);

-- V1__baseline_schema.sql
CREATE TABLE IF NOT EXISTS role_def (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    permissions JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_company_role (company_id, name)
);

-- V1__baseline_schema.sql
CREATE TABLE IF NOT EXISTS perm_def (
    code VARCHAR(64) PRIMARY KEY,
    label VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0
);

-- V1__baseline_schema.sql
CREATE TABLE IF NOT EXISTS counterparty_relation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    counterparty_company_name VARCHAR(128) NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_company_counterparty (company_id, counterparty_company_name)
);

-- V2__security_and_tenant_boundaries.sql
ALTER TABLE company ADD COLUMN created_by BIGINT NULL AFTER legal_person_name;

-- V2__security_and_tenant_boundaries.sql
ALTER TABLE role_def ADD COLUMN code VARCHAR(64) NULL AFTER company_id;

-- V2__security_and_tenant_boundaries.sql
ALTER TABLE role_def ADD UNIQUE KEY uk_company_role_code (company_id, code);

-- V2__security_and_tenant_boundaries.sql
ALTER TABLE counterparty_relation
    ADD COLUMN counterparty_company_id BIGINT NULL AFTER company_id;

-- V2__security_and_tenant_boundaries.sql
CREATE TABLE auth_session (
    token_hash CHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    expires_at DATETIME NOT NULL,
    revoked TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_user (user_id),
    INDEX idx_session_expiry (expires_at)
);

-- V3__complete_trade_role_permissions.sql
ALTER TABLE company_invite
    ADD COLUMN relation_role VARCHAR(16) NULL AFTER type;

-- V4__trade_fulfillment_and_audit.sql
ALTER TABLE company
    ADD COLUMN registered_address VARCHAR(256) NULL AFTER legal_person_name,
    ADD COLUMN contact_phone VARCHAR(32) NULL AFTER registered_address,
    ADD COLUMN bank_name VARCHAR(128) NULL AFTER contact_phone,
    ADD COLUMN bank_account VARCHAR(64) NULL AFTER bank_name;

-- V4__trade_fulfillment_and_audit.sql
CREATE TABLE audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    biz_type VARCHAR(64) NOT NULL,
    biz_id VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    detail TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_company_time (company_id, created_at),
    INDEX idx_audit_biz (biz_type, biz_id)
);

-- V4__trade_fulfillment_and_audit.sql: system permission definitions
INSERT IGNORE INTO perm_def (code, label, sort_order) VALUES
    ('delivery_view', '送货单查看', 16),
    ('delivery_create', '创建送货单', 17),
    ('delivery_receipt', '签收送货单', 18);

-- V5__rollback_trade_fulfillment.sql: system permission definitions
DELETE FROM perm_def
WHERE code IN ('delivery_view', 'delivery_create', 'delivery_receipt');

-- V8__identity_roles_and_certification.sql
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

-- V8__identity_roles_and_certification.sql
ALTER TABLE role_def
    ADD COLUMN system_role TINYINT(1) NOT NULL DEFAULT 0 AFTER name;

-- V8__identity_roles_and_certification.sql
ALTER TABLE role_def
    MODIFY COLUMN code VARCHAR(64) NOT NULL;

-- V8__identity_roles_and_certification.sql: system permission definitions
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

-- V10__contract_files_memos_reconciliation_inventory.sql: system permission definitions
INSERT IGNORE INTO perm_def (code, label, sort_order) VALUES
    ('contract_attachment_upload', '合同资料上传', 16),
    ('sales_order_receive', '销售单接收', 17),
    ('inventory_receive', '销售单入库', 18);

-- V22__fadada_personal_identity.sql
CREATE TABLE fadada_user_identity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    client_user_id VARCHAR(128) NOT NULL,
    open_user_id VARCHAR(128) NULL,
    local_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    binding_status VARCHAR(32) NOT NULL DEFAULT 'unauthorized',
    ident_status VARCHAR(32) NOT NULL DEFAULT 'unidentified',
    ident_process_status VARCHAR(32) NOT NULL DEFAULT 'no_start',
    auth_scopes VARCHAR(512) NOT NULL DEFAULT '["ident_info"]',
    ident_method VARCHAR(64) NULL,
    verified_name VARCHAR(64) NULL,
    failure_reason VARCHAR(512) NULL,
    ident_submitted_at DATETIME NULL,
    ident_verified_at DATETIME NULL,
    last_sync_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_fadada_user_identity_user (user_id),
    UNIQUE KEY uk_fadada_user_identity_client (client_user_id),
    UNIQUE KEY uk_fadada_user_identity_open (open_user_id),
    CONSTRAINT fk_fadada_user_identity_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
);

-- V23__fadada_company_seal_and_contract_signing.sql
CREATE TABLE fadada_corp_identity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    applicant_user_id BIGINT NOT NULL,
    client_corp_id VARCHAR(128) NOT NULL,
    open_corp_id VARCHAR(128) NULL,
    local_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    binding_status VARCHAR(32) NOT NULL DEFAULT 'unauthorized',
    ident_status VARCHAR(32) NOT NULL DEFAULT 'unidentified',
    auth_scopes VARCHAR(512) NOT NULL,
    ident_method VARCHAR(64) NULL,
    verified_name VARCHAR(160) NULL,
    verified_credit_code VARCHAR(64) NULL,
    verified_legal_rep_name VARCHAR(64) NULL,
    failure_reason VARCHAR(512) NULL,
    submitted_at DATETIME NULL,
    verified_at DATETIME NULL,
    last_sync_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_fadada_corp_company (company_id),
    UNIQUE KEY uk_fadada_corp_client (client_corp_id),
    UNIQUE KEY uk_fadada_corp_open (open_corp_id),
    CONSTRAINT fk_fadada_corp_company FOREIGN KEY (company_id) REFERENCES company(id),
    CONSTRAINT fk_fadada_corp_applicant FOREIGN KEY (applicant_user_id) REFERENCES sys_user(id)
);

-- V23__fadada_company_seal_and_contract_signing.sql
CREATE TABLE fadada_corp_seal (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    seal_id VARCHAR(64) NOT NULL,
    seal_name VARCHAR(160) NULL,
    category_type VARCHAR(64) NULL,
    seal_status VARCHAR(32) NOT NULL,
    last_sync_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_fadada_corp_seal (company_id, seal_id),
    INDEX idx_fadada_seal_status (company_id, seal_status),
    CONSTRAINT fk_fadada_seal_company FOREIGN KEY (company_id) REFERENCES company(id)
);

-- V26__application_generated_ids.sql
ALTER TABLE `audit_log` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `company` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `company_certification_application` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `company_invite` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `company_member` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `counterparty_relation` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `fadada_corp_identity` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `fadada_corp_seal` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `fadada_user_identity` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `role_def` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V26__application_generated_ids.sql
ALTER TABLE `sys_user` MODIFY COLUMN `id` BIGINT NOT NULL;

-- V28__administrator_counterparty_visibility.sql: system permission definitions
INSERT IGNORE INTO perm_def (code, label, sort_order)
VALUES ('counterparty_view', '合作企业查看', 19);

-- V30__member_multiple_roles.sql
ALTER TABLE company_member
    ADD COLUMN role_codes JSON NULL COMMENT 'Assigned role codes; NULL uses legacy role_code' AFTER role_code;

-- V31__member_removal_notices.sql
CREATE TABLE member_removal_notice (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    company_name VARCHAR(200) NOT NULL,
    removed_at DATETIME NOT NULL,
    acknowledged_at DATETIME NULL,
    KEY idx_removal_notice_user_company (user_id, company_id, id)
);

-- V32__company_certification_operator_identity.sql
ALTER TABLE fadada_corp_identity
    ADD COLUMN operator_type VARCHAR(32) NULL COMMENT '认证服务返回的经办人类型',
    ADD COLUMN operator_id VARCHAR(128) NULL COMMENT '认证服务返回的经办人标识';

-- V33__company_certification_recovery.sql
ALTER TABLE fadada_corp_identity
    ADD COLUMN provider_request_id VARCHAR(128) NULL COMMENT '当前经办人对应的本地认证申请',
    ADD COLUMN seal_sync_warning VARCHAR(512) NULL COMMENT '独立于企业认证的印章同步提示';

-- V35__phone_binding_lookup_index.sql
CREATE INDEX idx_sys_user_phone ON sys_user (phone);

CREATE TABLE undo_log (
    branch_id BIGINT NOT NULL,
    xid VARCHAR(128) NOT NULL,
    context VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB NOT NULL,
    log_status INT NOT NULL,
    log_created DATETIME(6) NOT NULL,
    log_modified DATETIME(6) NOT NULL,
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET FOREIGN_KEY_CHECKS = @tradepass_previous_fk_checks;
