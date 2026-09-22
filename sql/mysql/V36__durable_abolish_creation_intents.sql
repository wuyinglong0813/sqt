CREATE TABLE fadada_abolish_creation_intent (
    id VARCHAR(36) PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    original_sign_task_id VARCHAR(128) NOT NULL,
    previous_abolish_task_id VARCHAR(128) NULL,
    abolished_sign_task_id VARCHAR(128) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'UNCONFIRMED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at DATETIME NULL,
    INDEX idx_abolish_intent_contract (contract_id, version_no, status)
);
