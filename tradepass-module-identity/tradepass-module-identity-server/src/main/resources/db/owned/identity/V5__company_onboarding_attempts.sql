CREATE TABLE company_onboarding_attempt (
    id BIGINT NOT NULL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    applicant_user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    provider_request_id VARCHAR(64) NOT NULL,
    company_name VARCHAR(160) NOT NULL,
    credit_code VARCHAR(32) NOT NULL,
    legal_person_name VARCHAR(64) NOT NULL,
    registered_address VARCHAR(256) NULL,
    contact_phone VARCHAR(32) NULL,
    bank_name VARCHAR(128) NULL,
    bank_account VARCHAR(64) NULL,
    started_at DATETIME NULL,
    expires_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_onboarding_provider_request (provider_request_id),
    INDEX idx_onboarding_company_status (company_id, status),
    INDEX idx_onboarding_applicant_company (applicant_user_id, company_id, id)
);

ALTER TABLE fadada_corp_identity ADD COLUMN onboarding_attempt_id BIGINT NULL;
