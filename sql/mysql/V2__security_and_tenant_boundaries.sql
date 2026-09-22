ALTER TABLE company ADD COLUMN created_by BIGINT NULL AFTER legal_person_name;

ALTER TABLE role_def ADD COLUMN code VARCHAR(64) NULL AFTER company_id;
UPDATE role_def SET code = CASE name
    WHEN '法人' THEN 'LEGAL'
    WHEN '管理员' THEN 'ADMIN'
    WHEN '销售员' THEN 'SALES'
    WHEN '采购员' THEN 'PURCHASER'
    WHEN '财务' THEN 'FINANCE'
    ELSE CONCAT('CUSTOM_', id)
END WHERE code IS NULL;
ALTER TABLE role_def ADD UNIQUE KEY uk_company_role_code (company_id, code);

ALTER TABLE counterparty_relation
    ADD COLUMN counterparty_company_id BIGINT NULL AFTER company_id;
UPDATE counterparty_relation r
JOIN company c ON c.name = r.counterparty_company_name
SET r.counterparty_company_id = c.id
WHERE r.counterparty_company_id IS NULL;

ALTER TABLE trade_contract
    ADD COLUMN counterparty_company_id BIGINT NULL AFTER company_id,
    ADD COLUMN client_request_id VARCHAR(64) NULL AFTER counterparty_name,
    ADD COLUMN approved_by BIGINT NULL AFTER initiated_by,
    ADD COLUMN approved_at DATETIME NULL AFTER approved_by,
    ADD UNIQUE KEY uk_contract_request (company_id, client_request_id),
    ADD INDEX idx_contract_counterparty_company (counterparty_company_id, status);
UPDATE trade_contract t
JOIN company c ON c.name = t.counterparty_name
SET t.counterparty_company_id = c.id
WHERE t.counterparty_company_id IS NULL;

CREATE TABLE auth_session (
    token_hash CHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    expires_at DATETIME NOT NULL,
    revoked TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_user (user_id),
    INDEX idx_session_expiry (expires_at)
);
