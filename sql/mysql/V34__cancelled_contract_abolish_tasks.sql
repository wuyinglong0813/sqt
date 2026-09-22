-- Keep cancelled provider task ids addressable when delayed callbacks arrive.
CREATE TABLE fadada_cancelled_abolish_task (
    sign_task_id VARCHAR(128) PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    original_sign_task_id VARCHAR(128) NOT NULL,
    cancelled_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_cancelled_abolish_contract (contract_id, version_no)
);
