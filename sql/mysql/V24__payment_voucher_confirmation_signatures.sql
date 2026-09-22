ALTER TABLE contract_attachment
    ADD COLUMN signer_name VARCHAR(64) NULL AFTER confirmed_by,
    ADD COLUMN signed_at DATETIME NULL AFTER signer_name,
    ADD COLUMN signature_original_name VARCHAR(255) NULL AFTER signed_at,
    ADD COLUMN signature_content_type VARCHAR(128) NULL AFTER signature_original_name,
    ADD COLUMN signature_file_size BIGINT NULL AFTER signature_content_type,
    ADD COLUMN signature_data LONGBLOB NULL AFTER signature_file_size,
    ADD COLUMN signature_sha256 CHAR(64) NULL AFTER signature_data,
    ADD COLUMN signature_storage_provider VARCHAR(32) NULL AFTER signature_sha256,
    ADD COLUMN signature_storage_bucket VARCHAR(255) NULL AFTER signature_storage_provider,
    ADD COLUMN signature_object_key VARCHAR(1024) NULL AFTER signature_storage_bucket,
    ADD COLUMN signature_object_version_id VARCHAR(255) NULL AFTER signature_object_key,
    ADD COLUMN signature_etag VARCHAR(255) NULL AFTER signature_object_version_id,
    ADD COLUMN signature_encryption_algorithm VARCHAR(64) NULL AFTER signature_etag;

CREATE TABLE project_contract_prompt_preference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    dismissed_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_project_prompt_company_contract (company_id, contract_id),
    INDEX idx_project_prompt_contract (contract_id, company_id),
    CONSTRAINT fk_project_prompt_contract FOREIGN KEY (contract_id) REFERENCES trade_contract(id)
);
