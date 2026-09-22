CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    openid VARCHAR(64) NOT NULL UNIQUE,
    phone VARCHAR(32),
    nickname VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

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

CREATE TABLE IF NOT EXISTS role_def (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    permissions JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_company_role (company_id, name)
);

CREATE TABLE IF NOT EXISTS perm_def (
    code VARCHAR(64) PRIMARY KEY,
    label VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS contract_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    name VARCHAR(256) NOT NULL,
    category VARCHAR(64),
    content TEXT,
    created_by BIGINT,
    updated_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_template_company (company_id)
);

CREATE TABLE IF NOT EXISTS template_category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_company_cat (company_id, name),
    INDEX idx_category_company (company_id)
);

CREATE TABLE IF NOT EXISTS trade_contract (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    counterparty_name VARCHAR(128) NOT NULL,
    name VARCHAR(256) NOT NULL,
    template_name VARCHAR(128),
    amount DECIMAL(15,2) NOT NULL DEFAULT 0,
    start_date DATE,
    end_date DATE,
    terms TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    initiated_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_contract_company (company_id),
    INDEX idx_contract_counterparty (counterparty_name)
);

CREATE TABLE IF NOT EXISTS counterparty_relation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    counterparty_company_name VARCHAR(128) NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_company_counterparty (company_id, counterparty_company_name)
);

CREATE TABLE IF NOT EXISTS trade_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    direction VARCHAR(16) NOT NULL,
    counterparty_name VARCHAR(128) NOT NULL,
    order_no VARCHAR(64),
    amount DECIMAL(18,2) NOT NULL,
    order_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_company_dir (company_id, direction)
);
