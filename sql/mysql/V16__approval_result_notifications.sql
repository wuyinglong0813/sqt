CREATE TABLE approval_result_notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    recipient_company_id BIGINT NOT NULL,
    source_company_id BIGINT NOT NULL,
    result_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    contract_id BIGINT NULL,
    result_status VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    rejected_reason VARCHAR(500) NULL,
    read_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_approval_result_source (recipient_company_id, result_type, source_id),
    INDEX idx_approval_result_unread (recipient_company_id, read_at, created_at),
    INDEX idx_approval_result_company (recipient_company_id, source_company_id, created_at)
);
