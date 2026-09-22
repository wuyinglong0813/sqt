CREATE TABLE project_ledger (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    project_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(500) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_ledger_no (company_id, project_no),
    UNIQUE KEY uk_project_ledger_name (company_id, name),
    INDEX idx_project_ledger_company_status (company_id, status, created_at)
);

CREATE TABLE project_contract_assignment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_contract_company_contract (company_id, contract_id),
    UNIQUE KEY uk_project_contract_project_contract (project_id, contract_id),
    INDEX idx_project_contract_project (company_id, project_id, created_at),
    CONSTRAINT fk_project_contract_project FOREIGN KEY (project_id) REFERENCES project_ledger(id),
    CONSTRAINT fk_project_contract_contract FOREIGN KEY (contract_id) REFERENCES trade_contract(id)
);
