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

CREATE TABLE fadada_contract_sign_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    contract_id BIGINT NOT NULL,
    version_no INT NOT NULL DEFAULT 1,
    sign_task_id VARCHAR(64) NULL,
    abolished_sign_task_id VARCHAR(64) NULL,
    source_file_id VARCHAR(64) NULL,
    doc_id VARCHAR(64) NULL,
    source_sha256 CHAR(64) NULL,
    provider_status VARCHAR(64) NOT NULL DEFAULT 'WAITING_AUTH',
    initiator_company_id BIGINT NOT NULL,
    counterparty_company_id BIGINT NOT NULL,
    initiator_actor_id VARCHAR(32) NOT NULL DEFAULT 'supplier',
    counterparty_actor_id VARCHAR(32) NOT NULL DEFAULT 'buyer',
    initiator_sign_status VARCHAR(32) NULL,
    counterparty_sign_status VARCHAR(32) NULL,
    last_error VARCHAR(512) NULL,
    prepared_at DATETIME NULL,
    finished_at DATETIME NULL,
    archived_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_fadada_contract_version (contract_id, version_no),
    UNIQUE KEY uk_fadada_sign_task (sign_task_id),
    UNIQUE KEY uk_fadada_abolish_task (abolished_sign_task_id),
    INDEX idx_fadada_contract_provider_status (provider_status, updated_at),
    CONSTRAINT fk_fadada_sign_contract FOREIGN KEY (contract_id) REFERENCES trade_contract(id)
);

ALTER TABLE contract_archive
    ADD COLUMN archive_source VARCHAR(32) NOT NULL DEFAULT 'LOCAL_GENERATED',
    ADD COLUMN provider_file_id VARCHAR(64) NULL;
